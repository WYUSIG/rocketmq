## RocketMq源码学习

>消息的接收与存储发生在broker端


### broker接收消息

> org.apache.rocketmq.broker.processor.SendMessageProcessor.asyncProcessRequest

```java
    /**
     * 异步处理请求
     */
    @Override
    public void asyncProcessRequest(ChannelHandlerContext ctx, RemotingCommand request, RemotingResponseCallback responseCallback) throws Exception {
        asyncProcessRequest(ctx, request).thenAcceptAsync(responseCallback::callback, this.brokerController.getSendMessageExecutor());
    }

    /**
     * 异步处理请求
     * @param ctx netty的ChannelHandlerContext
     * @param request 请求
     */
    public CompletableFuture<RemotingCommand> asyncProcessRequest(ChannelHandlerContext ctx,
                                                                  RemotingCommand request) throws RemotingCommandException {
        final SendMessageContext mqtraceContext;
        switch (request.getCode()) {
            //如果是消费者的消费反馈消息
            case RequestCode.CONSUMER_SEND_MSG_BACK:
                return this.asyncConsumerSendMsgBack(ctx, request);
            default:
                //解析出SendMessageRequestHeader
                SendMessageRequestHeader requestHeader = parseRequestHeader(request);
                if (requestHeader == null) {
                    return CompletableFuture.completedFuture(null);
                }
                mqtraceContext = buildMsgContext(ctx, requestHeader);
                //发送消息钩子 发送消息前回调
                this.executeSendMessageHookBefore(ctx, request, mqtraceContext);
                if (requestHeader.isBatch()) {
                    //批量消息处理
                    return this.asyncSendBatchMessage(ctx, request, mqtraceContext, requestHeader);
                } else {
                    //普通异步处理消息
                    return this.asyncSendMessage(ctx, request, mqtraceContext, requestHeader);
                }
        }
    }
```


##### 事务与普通消息的存储判断

```java
//SendMessageProcessor.java
private CompletableFuture<RemotingCommand> asyncSendMessage(ChannelHandlerContext ctx, RemotingCommand request,
                                                                SendMessageContext mqtraceContext,
                                                                SendMessageRequestHeader requestHeader) {
        //构造响应体
        final RemotingCommand response = preSend(ctx, request, requestHeader);
        final SendMessageResponseHeader responseHeader = (SendMessageResponseHeader)response.readCustomHeader();

        /**
         * 不是-1证明已经有结果，直接异步完成
         */
        if (response.getCode() != -1) {
            return CompletableFuture.completedFuture(response);
        }

        final byte[] body = request.getBody();

        //message queue id
        int queueIdInt = requestHeader.getQueueId();
        TopicConfig topicConfig = this.brokerController.getTopicConfigManager().selectTopicConfig(requestHeader.getTopic());

        if (queueIdInt < 0) {
            //如果queueId小于0，则随机选择一个写message queue
            queueIdInt = randomQueueId(topicConfig.getWriteQueueNums());
        }

        /**
         * broker扩展的消息，基于Message
         */
        MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
        msgInner.setTopic(requestHeader.getTopic());
        msgInner.setQueueId(queueIdInt);

        //如果重试超出一定次数直接放进死信队列
        if (!handleRetryAndDLQ(requestHeader, response, request, msgInner, topicConfig)) {
            //如果是重试消息，但是没有消费者组或者创建topic失败，直接返回
            return CompletableFuture.completedFuture(response);
        }

        //把消息各项属性赋值到broker扩展的消息msgInner
        msgInner.setBody(body);
        msgInner.setFlag(requestHeader.getFlag());
        //String->Map<String, String>
        MessageAccessor.setProperties(msgInner, MessageDecoder.string2messageProperties(requestHeader.getProperties()));
        //String类型的消息拓展属性
        msgInner.setPropertiesString(requestHeader.getProperties());
        msgInner.setBornTimestamp(requestHeader.getBornTimestamp());
        msgInner.setBornHost(ctx.channel().remoteAddress());
        //存储地址
        msgInner.setStoreHost(this.getStoreHost());
        msgInner.setReconsumeTimes(requestHeader.getReconsumeTimes() == null ? 0 : requestHeader.getReconsumeTimes());
        //broker集群名称
        String clusterName = this.brokerController.getBrokerConfig().getBrokerClusterName();
        //把broker集群名称添加到属性
        MessageAccessor.putProperty(msgInner, MessageConst.PROPERTY_CLUSTER, clusterName);
        //因为加了属性，所以再次把消息拓展属性编码成String
        msgInner.setPropertiesString(MessageDecoder.messageProperties2String(msgInner.getProperties()));

        //存储消息结果
        CompletableFuture<PutMessageResult> putMessageResult = null;
        Map<String, String> origProps = MessageDecoder.string2messageProperties(requestHeader.getProperties());
        String transFlag = origProps.get(MessageConst.PROPERTY_TRANSACTION_PREPARED);
        //如果是事务消息
        if (transFlag != null && Boolean.parseBoolean(transFlag)) {
            //如果禁止事务消息
            if (this.brokerController.getBrokerConfig().isRejectTransactionMessage()) {
                response.setCode(ResponseCode.NO_PERMISSION);
                response.setRemark(
                        "the broker[" + this.brokerController.getBrokerConfig().getBrokerIP1()
                                + "] sending transaction message is forbidden");
                return CompletableFuture.completedFuture(response);
            }
            //事务消息保存
            putMessageResult = this.brokerController.getTransactionalMessageService().asyncPrepareMessage(msgInner);
        } else {
            //普通消息保存
            putMessageResult = this.brokerController.getMessageStore().asyncPutMessage(msgInner);
        }
        //处理保存结果
        return handlePutMessageResultFuture(putMessageResult, response, request, msgInner, responseHeader, mqtraceContext, ctx, queueIdInt);
    }
```

##### 死信队列判断

```java
    /**
     * SendMessageProcessor.java
     * 处理重试和死信消息
     */
    private boolean handleRetryAndDLQ(SendMessageRequestHeader requestHeader, RemotingCommand response,
                                      RemotingCommand request,
                                      MessageExt msg, TopicConfig topicConfig) {
        String newTopic = requestHeader.getTopic();
        //如果topic不是空且以%RETRY%开头
        if (null != newTopic && newTopic.startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
            //去掉%RETRY%前缀
            String groupName = newTopic.substring(MixAll.RETRY_GROUP_TOPIC_PREFIX.length());
            //获取订阅组配置
            SubscriptionGroupConfig subscriptionGroupConfig =
                this.brokerController.getSubscriptionGroupManager().findSubscriptionGroupConfig(groupName);
            if (null == subscriptionGroupConfig) {
                //没有消费者组订阅
                response.setCode(ResponseCode.SUBSCRIPTION_GROUP_NOT_EXIST);
                response.setRemark(
                    "subscription group not exist, " + groupName + " " + FAQUrl.suggestTodo(FAQUrl.SUBSCRIPTION_GROUP_NOT_EXIST));
                return false;
            }

            //获取订阅组配置最大重试次数
            int maxReconsumeTimes = subscriptionGroupConfig.getRetryMaxTimes();
            //如果请求的版本大于3.4.9，最大重试次数取请求自带的
            if (request.getVersion() >= MQVersion.Version.V3_4_9.ordinal()) {
                maxReconsumeTimes = requestHeader.getMaxReconsumeTimes();
            }
            //已重试次数默认0
            int reconsumeTimes = requestHeader.getReconsumeTimes() == null ? 0 : requestHeader.getReconsumeTimes();
            //如果已达到最大重试次数，进入死信队列
            if (reconsumeTimes >= maxReconsumeTimes) {
                //topic加上死信队列前缀%DLQ%
                newTopic = MixAll.getDLQTopic(groupName);
                //随机挑选一个死信MessageQueue
                int queueIdInt = Math.abs(this.random.nextInt() % 99999999) % DLQ_NUMS_PER_GROUP;
                //获取这个加了死信前缀的topic的配置，获取不到就会创建topic
                topicConfig = this.brokerController.getTopicConfigManager().createTopicInSendMessageBackMethod(newTopic,
                    DLQ_NUMS_PER_GROUP,
                    PermName.PERM_WRITE, 0
                );
                msg.setTopic(newTopic);
                msg.setQueueId(queueIdInt);
                if (null == topicConfig) {
                    //创建topic失败
                    response.setCode(ResponseCode.SYSTEM_ERROR);
                    response.setRemark("topic[" + newTopic + "] not exist");
                    return false;
                }
            }
        }
        int sysFlag = requestHeader.getSysFlag();
        if (TopicFilterType.MULTI_TAG == topicConfig.getTopicFilterType()) {
            sysFlag |= MessageSysFlag.MULTI_TAGS_FLAG;
        }
        msg.setSysFlag(sysFlag);
        return true;
    }
```


### 存储消息

#### 存储消息前的简单校验

```java
    /**
     * 异步保存消息 DefaultMessageStore.java
     * @param msg MessageInstance to store
     * @return
     */
    @Override
    public CompletableFuture<PutMessageResult> asyncPutMessage(MessageExtBrokerInner msg) {
        //检查存储器是否已启动
        PutMessageStatus checkStoreStatus = this.checkStoreStatus();
        if (checkStoreStatus != PutMessageStatus.PUT_OK) {
            return CompletableFuture.completedFuture(new PutMessageResult(checkStoreStatus, null));
        }
        //简单校验消息，topic是否过长，消息扩展属性是否过长
        PutMessageStatus msgCheckStatus = this.checkMessage(msg);
        if (msgCheckStatus == PutMessageStatus.MESSAGE_ILLEGAL) {
            return CompletableFuture.completedFuture(new PutMessageResult(msgCheckStatus, null));
        }

        //开始时间
        long beginTime = this.getSystemClock().now();
        //异步存储消息
        CompletableFuture<PutMessageResult> putResultFuture = this.commitLog.asyncPutMessage(msg);

        putResultFuture.thenAccept((result) -> {
            //消耗时间
            long elapsedTime = this.getSystemClock().now() - beginTime;
            if (elapsedTime > 500) {
                //存储消耗时间过长警告
                log.warn("putMessage not in lock elapsed time(ms)={}, bodyLength={}", elapsedTime, msg.getBody().length);
            }
            //更新存储时间各级别个数、最大存储时间
            this.storeStatsService.setPutMessageEntireTimeMax(elapsedTime);

            if (null == result || !result.isOk()) {
                //存储失败次数+1
                this.storeStatsService.getPutMessageFailedTimes().incrementAndGet();
            }
        });

        //返回异步结果
        return putResultFuture;
    }
```

#### 存储消息

```java
//CommitLog.java
public CompletableFuture<PutMessageResult> asyncPutMessage(final MessageExtBrokerInner msg) {
        // Set the storage time 存储时间
        msg.setStoreTimestamp(System.currentTimeMillis());
        // Set the message body BODY CRC (consider the most appropriate setting
        // on the client)  crc32校验码，长度校验
        msg.setBodyCRC(UtilAll.crc32(msg.getBody()));
        // Back to Results
        AppendMessageResult result = null;

        //
        StoreStatsService storeStatsService = this.defaultMessageStore.getStoreStatsService();

        String topic = msg.getTopic();
        int queueId = msg.getQueueId();

        //事务标志
        final int tranType = MessageSysFlag.getTransactionValue(msg.getSysFlag());
        //如果不是事务消息或者是事务commit消息
        if (tranType == MessageSysFlag.TRANSACTION_NOT_TYPE
                || tranType == MessageSysFlag.TRANSACTION_COMMIT_TYPE) {
            // Delay Delivery 如果是延迟消息
            if (msg.getDelayTimeLevel() > 0) {
                //如果延迟等级大于最大延迟等级，重新赋值为最大延迟等级
                if (msg.getDelayTimeLevel() > this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel()) {
                    msg.setDelayTimeLevel(this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel());
                }

                //重新设置topic为SCHEDULE_TOPIC_XXXX
                topic = TopicValidator.RMQ_SYS_SCHEDULE_TOPIC;
                /**
                 * 根据延迟等级获取MessageQueueId
                 * @see org.apache.rocketmq.store.config.MessageStoreConfig#messageDelayLevel
                 * 1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
                 */
                queueId = ScheduleMessageService.delayLevel2QueueId(msg.getDelayTimeLevel());

                // Backup real topic, queueId 真实topic
                MessageAccessor.putProperty(msg, MessageConst.PROPERTY_REAL_TOPIC, msg.getTopic());
                //真实的MessageQueueId
                MessageAccessor.putProperty(msg, MessageConst.PROPERTY_REAL_QUEUE_ID, String.valueOf(msg.getQueueId()));
                //因为加了两个属性，重新编码属性为String
                msg.setPropertiesString(MessageDecoder.messageProperties2String(msg.getProperties()));

                msg.setTopic(topic);
                msg.setQueueId(queueId);
            }
        }

        //已消耗时间
        long elapsedTimeInLock = 0;
        MappedFile unlockMappedFile = null;
        //获取最后一个MappedFile(顺序写特性)
        MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile();

        //存储消息需要先获取锁
        putMessageLock.lock(); //spin or ReentrantLock ,depending on store config
        try {
            //锁开始时间
            long beginLockTimestamp = this.defaultMessageStore.getSystemClock().now();
            this.beginTimeInLock = beginLockTimestamp;

            // Here settings are stored timestamp, in order to ensure an orderly
            // global
            msg.setStoreTimestamp(beginLockTimestamp);

            if (null == mappedFile || mappedFile.isFull()) {
                //如果获取mappedFile为空，则创建
                mappedFile = this.mappedFileQueue.getLastMappedFile(0); // Mark: NewFile may be cause noise
            }
            if (null == mappedFile) {
                //如果创建mappedFile失败
                log.error("create mapped file1 error, topic: " + msg.getTopic() + " clientAddr: " + msg.getBornHostString());
                beginTimeInLock = 0;
                return CompletableFuture.completedFuture(new PutMessageResult(PutMessageStatus.CREATE_MAPEDFILE_FAILED, null));
            }

            //往mappedFile后面添加消息
            result = mappedFile.appendMessage(msg, this.appendMessageCallback);
            switch (result.getStatus()) {
                case PUT_OK:
                    //存储成功
                    break;
                case END_OF_FILE:
                    //文件已经存不下
                    unlockMappedFile = mappedFile;
                    // Create a new file, re-write the message
                    //创建新的mapperFile
                    mappedFile = this.mappedFileQueue.getLastMappedFile(0);
                    if (null == mappedFile) {
                        // XXX: warn and notify me
                        //mappedFile创建失败
                        log.error("create mapped file2 error, topic: " + msg.getTopic() + " clientAddr: " + msg.getBornHostString());
                        beginTimeInLock = 0;
                        return CompletableFuture.completedFuture(new PutMessageResult(PutMessageStatus.CREATE_MAPEDFILE_FAILED, result));
                    }
                    //往mappedFile后面添加消息
                    result = mappedFile.appendMessage(msg, this.appendMessageCallback);
                    break;
                case MESSAGE_SIZE_EXCEEDED:
                    //消息过长
                case PROPERTIES_SIZE_EXCEEDED:
                    //消息属性过长
                    beginTimeInLock = 0;
                    return CompletableFuture.completedFuture(new PutMessageResult(PutMessageStatus.MESSAGE_ILLEGAL, result));
                case UNKNOWN_ERROR:
                    //未知错误
                    beginTimeInLock = 0;
                    return CompletableFuture.completedFuture(new PutMessageResult(PutMessageStatus.UNKNOWN_ERROR, result));
                default:
                    beginTimeInLock = 0;
                    return CompletableFuture.completedFuture(new PutMessageResult(PutMessageStatus.UNKNOWN_ERROR, result));
            }

            elapsedTimeInLock = this.defaultMessageStore.getSystemClock().now() - beginLockTimestamp;
            beginTimeInLock = 0;
        } finally {
            putMessageLock.unlock();
        }

        if (elapsedTimeInLock > 500) {
            //花费时间超过500ms,日志警告
            log.warn("[NOTIFYME]putMessage in lock cost time(ms)={}, bodyLength={} AppendMessageResult={}", elapsedTimeInLock, msg.getBody().length, result);
        }

        if (null != unlockMappedFile && this.defaultMessageStore.getMessageStoreConfig().isWarmMapedFileEnable()) {
            this.defaultMessageStore.unlockMappedFile(unlockMappedFile);
        }

        //存储成功结果
        PutMessageResult putMessageResult = new PutMessageResult(PutMessageStatus.PUT_OK, result);

        // Statistics 统计
        //该主题存储消息数++
        storeStatsService.getSinglePutMessageTopicTimesTotal(msg.getTopic()).incrementAndGet();
        //该主题存储消息长度数增加
        storeStatsService.getSinglePutMessageTopicSizeTotal(topic).addAndGet(result.getWroteBytes());

        //刷盘
        CompletableFuture<PutMessageStatus> flushResultFuture = submitFlushRequest(result, msg);
        //从节点复制消息
        CompletableFuture<PutMessageStatus> replicaResultFuture = submitReplicaRequest(result, msg);
        return flushResultFuture.thenCombine(replicaResultFuture, (flushStatus, replicaStatus) -> {
            if (flushStatus != PutMessageStatus.PUT_OK) {
                putMessageResult.setPutMessageStatus(flushStatus);
            }
            if (replicaStatus != PutMessageStatus.PUT_OK) {
                putMessageResult.setPutMessageStatus(replicaStatus);
                if (replicaStatus == PutMessageStatus.FLUSH_SLAVE_TIMEOUT) {
                    log.error("do sync transfer other node, wait return, but failed, topic: {} tags: {} client address: {}",
                            msg.getTopic(), msg.getTags(), msg.getBornHostNameString());
                }
            }
            return putMessageResult;
        });
    }
```



