## RocketMq源码系列学习



入口：

DefaultMQProducer#send(Message)



```java
public class DefaultMQProducer extends ClientConfig implements MQProducer {
    ...
    @Override
    public SendResult send(
        Message msg) throws MQClientException, RemotingException, MQBrokerException, InterruptedException {
        //校验消息
        Validators.checkMessage(msg, this);
        //topic带上命名空间
        msg.setTopic(withNamespace(msg.getTopic()));
        //发送消息
        return this.defaultMQProducerImpl.send(msg);
    }
}
```

#### 发送前校验工作

```java
public class Validators {
	/**
     * 校验消息
     * @param msg 消息
     * @param defaultMQProducer 消息发送者
     * @throws MQClientException 异常
     */
    public static void checkMessage(Message msg, DefaultMQProducer defaultMQProducer)
        throws MQClientException {
        //如果消息为空，则抛出异常
        if (null == msg) {
            throw new MQClientException(ResponseCode.MESSAGE_ILLEGAL, "the message is null");
        }
        //校验topic，是否为空，是否为英文和数字等
        Validators.checkTopic(msg.getTopic());
        //校验该消息topic是否是不允许发送的topic,如rocketmq内部的topic:OFFSET_MOVED_EVENT
        Validators.isNotAllowedSendTopic(msg.getTopic());

        //校验body是否为空
        if (null == msg.getBody()) {
            throw new MQClientException(ResponseCode.MESSAGE_ILLEGAL, "the message body is null");
        }

        //校验body长度是否为0
        if (0 == msg.getBody().length) {
            throw new MQClientException(ResponseCode.MESSAGE_ILLEGAL, "the message body length is zero");
        }

        //校验body长度是否大于最大消息长度，默认4M
        if (msg.getBody().length > defaultMQProducer.getMaxMessageSize()) {
            throw new MQClientException(ResponseCode.MESSAGE_ILLEGAL,
                "the message body size over max value, MAX: " + defaultMQProducer.getMaxMessageSize());
        }
    }

    public static void checkTopic(String topic) throws MQClientException {
        //校验topic是否为空，是否有空格
        if (UtilAll.isBlank(topic)) {
            throw new MQClientException("The specified topic is blank", null);
        }

        //校验是否匹配正则表达式，英文和数字
        if (!regularExpressionMatcher(topic, PATTERN)) {
            throw new MQClientException(String.format(
                "The specified topic[%s] contains illegal characters, allowing only %s", topic,
                VALID_PATTERN_STR), null);
        }

        //校验长度是否超过最大主题长度
        if (topic.length() > TOPIC_MAX_LENGTH) {
            throw new MQClientException(
                String.format("The specified topic is longer than topic max length %d.", TOPIC_MAX_LENGTH), null);
        }
    }
}
```
#### topic带上命名空间

```java
public class ClientConfig {
    /**
     * 判断是否需要包装命名空间
     * @param resource origin topic
     * @return
     */
    public String withNamespace(String resource) {
        return NamespaceUtil.wrapNamespace(this.getNamespace(), resource);
    }
}
```

```java
public class NamespaceUtil {
    /**
     * topic包装命名空间
     * @param namespace 命名空间
     * @param resourceWithOutNamespace 原始topic
     * @return
     */
    public static String wrapNamespace(String namespace, String resourceWithOutNamespace) {
        //如果命名空间为空，或者原始topic为空，则返回原始topic
        if (StringUtils.isEmpty(namespace) || StringUtils.isEmpty(resourceWithOutNamespace)) {
            return resourceWithOutNamespace;
        }

        //如果是系统topic(包括rocketmq自己使用的topic,rmq_sys_开头的topic)，或者已经带上namespace,namespace开头即是，则返回原始topic
        if (isSystemResource(resourceWithOutNamespace) || isAlreadyWithNamespace(resourceWithOutNamespace, namespace)) {
            return resourceWithOutNamespace;
        }

        //如果有重试、死信队列的前缀，则去掉
        String resourceWithoutRetryAndDLQ = withOutRetryAndDLQ(resourceWithOutNamespace);
        StringBuilder stringBuilder = new StringBuilder();

        //如果这个原始topic有%RETRY%，则stringBuilder append一个%RETRY%
        if (isRetryTopic(resourceWithOutNamespace)) {
            stringBuilder.append(MixAll.RETRY_GROUP_TOPIC_PREFIX);
        }

        //如果这个原始topic有%DLQ%，则stringBuilder append一个%DLQ%
        if (isDLQTopic(resourceWithOutNamespace)) {
            stringBuilder.append(MixAll.DLQ_GROUP_TOPIC_PREFIX);
        }

        //返回%RETRY%DLQ%namespace%去掉重试、死信队列的前缀的原始topic
        return stringBuilder.append(namespace).append(NAMESPACE_SEPARATOR).append(resourceWithoutRetryAndDLQ).toString();

    }
}
```

#### org.apache.rocketmq.client.impl.producer.DefaultMQProducerImpl#sendDefaultImpl

```java
	private SendResult sendDefaultImpl(
        Message msg,
        final CommunicationMode communicationMode,
        final SendCallback sendCallback,
        final long timeout
    ) throws MQClientException, RemotingException, MQBrokerException, InterruptedException {
        //检测producer状态是不是ServiceState.RUNNING
        this.makeSureStateOK();
        //再次校验消息
        Validators.checkMessage(msg, this.defaultMQProducer);
        //生成invokeID
        final long invokeID = random.nextLong();
        //开始时间戳
        long beginTimestampFirst = System.currentTimeMillis();
        long beginTimestampPrev = beginTimestampFirst;
        long endTimestamp = beginTimestampFirst;
        //获取该主题的路由信息
        TopicPublishInfo topicPublishInfo = this.tryToFindTopicPublishInfo(msg.getTopic());
        if (topicPublishInfo != null && topicPublishInfo.ok()) {
            boolean callTimeout = false;
            //选择的MessageQueue
            MessageQueue mq = null;
            Exception exception = null;
            SendResult sendResult = null;
            //总发送次数
            int timesTotal = communicationMode == CommunicationMode.SYNC ? 1 + this.defaultMQProducer.getRetryTimesWhenSendFailed() : 1;
            int times = 0;
            //记录每次发送的broker名称
            String[] brokersSent = new String[timesTotal];
            //发送不成功，就会循环发送到最大发送次数
            for (; times < timesTotal; times++) {
                //最近发送选择的broker名称
                String lastBrokerName = null == mq ? null : mq.getBrokerName();
                //负载均衡得到一个MessageQueue
                MessageQueue mqSelected = this.selectOneMessageQueue(topicPublishInfo, lastBrokerName);
                if (mqSelected != null) {
                    mq = mqSelected;
                    //记录发送选择的broker名称
                    brokersSent[times] = mq.getBrokerName();
                    try {
                        //开始时间戳
                        beginTimestampPrev = System.currentTimeMillis();
                        if (times > 0) {
                            //Reset topic with namespace during resend.
                            //重试发送多次需要重新设置topic，因为可能带上重试、死信队列标志
                            msg.setTopic(this.defaultMQProducer.withNamespace(msg.getTopic()));
                        }
                        //准备发送，计算已花费时间
                        long costTime = beginTimestampPrev - beginTimestampFirst;
                        //判断是否超时
                        if (timeout < costTime) {
                            callTimeout = true;
                            break;
                        }

                        //发送，拿到结果sendResult
                        sendResult = this.sendKernelImpl(msg, mq, communicationMode, sendCallback, topicPublishInfo, timeout - costTime);
                        //结束时间戳
                        endTimestamp = System.currentTimeMillis();
                        //更新broker延时状态，为MessageQueue负载均衡提供数据
                        this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, false);
                        switch (communicationMode) {
                            case ASYNC:
                                //异步发送，直接返回
                                return null;
                            case ONEWAY:
                                //只发生一次，立即返回
                                return null;
                            case SYNC:
                                //同步发送，如果发送不成功且允许换另一个broker继续发送，则循环继续
                                if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                                    if (this.defaultMQProducer.isRetryAnotherBrokerWhenNotStoreOK()) {
                                        continue;
                                    }
                                }

                                //成功返回
                                return sendResult;
                            default:
                                break;
                        }
                    } catch (RemotingException e) {
                        //打印异常，更新Broker可用性信息，更新继续循环
                        endTimestamp = System.currentTimeMillis();
                        this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, true);
                        log.warn(String.format("sendKernelImpl exception, resend at once, InvokeID: %s, RT: %sms, Broker: %s", invokeID, endTimestamp - beginTimestampPrev, mq), e);
                        log.warn(msg.toString());
                        exception = e;
                        continue;
                    } catch (MQClientException e) {
                        endTimestamp = System.currentTimeMillis();
                        this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, true);
                        log.warn(String.format("sendKernelImpl exception, resend at once, InvokeID: %s, RT: %sms, Broker: %s", invokeID, endTimestamp - beginTimestampPrev, mq), e);
                        log.warn(msg.toString());
                        exception = e;
                        continue;
                    } catch (MQBrokerException e) {
                        endTimestamp = System.currentTimeMillis();
                        this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, true);
                        log.warn(String.format("sendKernelImpl exception, resend at once, InvokeID: %s, RT: %sms, Broker: %s", invokeID, endTimestamp - beginTimestampPrev, mq), e);
                        log.warn(msg.toString());
                        exception = e;
                        switch (e.getResponseCode()) {
                            //topic不存在、服务不可用、系统错误、没有权限、...都进行重试
                            case ResponseCode.TOPIC_NOT_EXIST:
                            case ResponseCode.SERVICE_NOT_AVAILABLE:
                            case ResponseCode.SYSTEM_ERROR:
                            case ResponseCode.NO_PERMISSION:
                            case ResponseCode.NO_BUYER_ID:
                            case ResponseCode.NOT_IN_CURRENT_UNIT:
                                continue;
                            default:
                                if (sendResult != null) {
                                    return sendResult;
                                }

                                throw e;
                        }
                    } catch (InterruptedException e) {
                        //中断异常
                        endTimestamp = System.currentTimeMillis();
                        this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, false);
                        log.warn(String.format("sendKernelImpl exception, throw exception, InvokeID: %s, RT: %sms, Broker: %s", invokeID, endTimestamp - beginTimestampPrev, mq), e);
                        log.warn(msg.toString());

                        log.warn("sendKernelImpl exception", e);
                        log.warn(msg.toString());
                        throw e;
                    }
                } else {
                    break;
                }
            }

            //返回发送结果
            if (sendResult != null) {
                return sendResult;
            }

            String info = String.format("Send [%d] times, still failed, cost [%d]ms, Topic: %s, BrokersSent: %s",
                times,
                System.currentTimeMillis() - beginTimestampFirst,
                msg.getTopic(),
                Arrays.toString(brokersSent));

            info += FAQUrl.suggestTodo(FAQUrl.SEND_MSG_FAILED);

            MQClientException mqClientException = new MQClientException(info, exception);
            if (callTimeout) {
                throw new RemotingTooMuchRequestException("sendDefaultImpl call timeout");
            }

            //根据不同情况，设置不同的code
            if (exception instanceof MQBrokerException) {
                mqClientException.setResponseCode(((MQBrokerException) exception).getResponseCode());
            } else if (exception instanceof RemotingConnectException) {
                //10001
                mqClientException.setResponseCode(ClientErrorCode.CONNECT_BROKER_EXCEPTION);
            } else if (exception instanceof RemotingTimeoutException) {
                //10002
                mqClientException.setResponseCode(ClientErrorCode.ACCESS_BROKER_TIMEOUT);
            } else if (exception instanceof MQClientException) {
                //10003
                mqClientException.setResponseCode(ClientErrorCode.BROKER_NOT_EXIST_EXCEPTION);
            }

            throw mqClientException;
        }

        //校验NameServer
        validateNameServerSetting();

        //消息路由找不到异常
        throw new MQClientException("No route info of this topic: " + msg.getTopic() + FAQUrl.suggestTodo(FAQUrl.NO_TOPIC_ROUTE_INFO),
            null).setResponseCode(ClientErrorCode.NOT_FOUND_TOPIC_EXCEPTION);
    }
```

#### 获取路由信息

```java
//org.apache.rocketmq.client.impl.producer.DefaultMQProducerImpl#tryToFindTopicPublishInfo
	/**
     * 根据topic获取路由信息
     * @param topic 主题
     * @return
     */
    private TopicPublishInfo tryToFindTopicPublishInfo(final String topic) {
        //根据topic作为key从topicPublishInfoTable(ConcurrentMap)获取该主题的路由信息
        TopicPublishInfo topicPublishInfo = this.topicPublishInfoTable.get(topic);
        //如果获取不到，或者messageQueueList为空
        if (null == topicPublishInfo || !topicPublishInfo.ok()) {
            //如果topicPublishInfoTable里面还没有key为该topic的键值对，则put进行一个
            this.topicPublishInfoTable.putIfAbsent(topic, new TopicPublishInfo());
            //从NameServer更新该topic的路由信息
            this.mQClientFactory.updateTopicRouteInfoFromNameServer(topic);
            //更新完再次获取
            topicPublishInfo = this.topicPublishInfoTable.get(topic);
        }

        if (topicPublishInfo.isHaveTopicRouterInfo() || topicPublishInfo.ok()) {
            //如果topicPublishInfo已经有路由信息，并且messageQueueList不为空，则直接返回
            return topicPublishInfo;
        } else {
            //否则
            this.mQClientFactory.updateTopicRouteInfoFromNameServer(topic, true, this.defaultMQProducer);
            topicPublishInfo = this.topicPublishInfoTable.get(topic);
            return topicPublishInfo;
        }
    }
```

```java
//org.apache.rocketmq.client.impl.factory.MQClientInstance#updateTopicRouteInfoFromNameServer(java.lang.String, boolean, org.apache.rocketmq.client.producer.DefaultMQProducer)
	/**
     * 从NameServer更新topic路由信息
     * @param topic 主题
     * @param isDefault 是否默认
     * @param defaultMQProducer Producer
     * @return
     */
    public boolean updateTopicRouteInfoFromNameServer(final String topic, boolean isDefault,
        DefaultMQProducer defaultMQProducer) {
        try {
            //加锁
            if (this.lockNamesrv.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                try {
                    TopicRouteData topicRouteData;
                    if (isDefault && defaultMQProducer != null) {
                        //尝试创建主题
                        topicRouteData = this.mQClientAPIImpl.getDefaultTopicRouteInfoFromNameServer(defaultMQProducer.getCreateTopicKey(),
                            1000 * 3);
                        if (topicRouteData != null) {
                            //创建主题成功
                            for (QueueData data : topicRouteData.getQueueDatas()) {
                                int queueNums = Math.min(defaultMQProducer.getDefaultTopicQueueNums(), data.getReadQueueNums());
                                data.setReadQueueNums(queueNums);
                                data.setWriteQueueNums(queueNums);
                            }
                        }
                    } else {
                        //不创建主题，直接获取
                        topicRouteData = this.mQClientAPIImpl.getTopicRouteInfoFromNameServer(topic, 1000 * 3);
                    }
                    if (topicRouteData != null) {
                        TopicRouteData old = this.topicRouteTable.get(topic);
                        boolean changed = topicRouteDataIsChange(old, topicRouteData);
                        if (!changed) {
                            changed = this.isNeedUpdateTopicRouteInfo(topic);
                        } else {
                            log.info("the topic[{}] route info changed, old[{}] ,new[{}]", topic, old, topicRouteData);
                        }

                        if (changed) {
                            TopicRouteData cloneTopicRouteData = topicRouteData.cloneTopicRouteData();

                            for (BrokerData bd : topicRouteData.getBrokerDatas()) {
                                this.brokerAddrTable.put(bd.getBrokerName(), bd.getBrokerAddrs());
                            }

                            // Update Pub info
                            {
                                TopicPublishInfo publishInfo = topicRouteData2TopicPublishInfo(topic, topicRouteData);
                                publishInfo.setHaveTopicRouterInfo(true);
                                Iterator<Entry<String, MQProducerInner>> it = this.producerTable.entrySet().iterator();
                                while (it.hasNext()) {
                                    Entry<String, MQProducerInner> entry = it.next();
                                    MQProducerInner impl = entry.getValue();
                                    if (impl != null) {
                                        impl.updateTopicPublishInfo(topic, publishInfo);
                                    }
                                }
                            }

                            // Update sub info
                            {
                                Set<MessageQueue> subscribeInfo = topicRouteData2TopicSubscribeInfo(topic, topicRouteData);
                                Iterator<Entry<String, MQConsumerInner>> it = this.consumerTable.entrySet().iterator();
                                while (it.hasNext()) {
                                    Entry<String, MQConsumerInner> entry = it.next();
                                    MQConsumerInner impl = entry.getValue();
                                    if (impl != null) {
                                        impl.updateTopicSubscribeInfo(topic, subscribeInfo);
                                    }
                                }
                            }
                            log.info("topicRouteTable.put. Topic = {}, TopicRouteData[{}]", topic, cloneTopicRouteData);
                            this.topicRouteTable.put(topic, cloneTopicRouteData);
                            return true;
                        }
                    } else {
                        //更新失败
                        log.warn("updateTopicRouteInfoFromNameServer, getTopicRouteInfoFromNameServer return null, Topic: {}. [{}]", topic, this.clientId);
                    }
                } catch (MQClientException e) {
                    if (!topic.startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                        log.warn("updateTopicRouteInfoFromNameServer Exception", e);
                    }
                } catch (RemotingException e) {
                    log.error("updateTopicRouteInfoFromNameServer Exception", e);
                    throw new IllegalStateException(e);
                } finally {
                    this.lockNamesrv.unlock();
                }
            } else {
                log.warn("updateTopicRouteInfoFromNameServer tryLock timeout {}ms. [{}]", LOCK_TIMEOUT_MILLIS, this.clientId);
            }
        } catch (InterruptedException e) {
            log.warn("updateTopicRouteInfoFromNameServer Exception", e);
        }

        return false;
    }
```



#### Rpc远程请求-获取路由信息示例

```java
//org.apache.rocketmq.client.impl.MQClientAPIImpl#getTopicRouteInfoFromNameServer(java.lang.String, long, boolean)	
/**
     * 根据topic从NameServer获取路由信息
     */
    public TopicRouteData getTopicRouteInfoFromNameServer(final String topic, final long timeoutMillis,
        boolean allowTopicNotExist) throws MQClientException, InterruptedException, RemotingTimeoutException, RemotingSendRequestException, RemotingConnectException {
        //获取路由信息头部
        GetRouteInfoRequestHeader requestHeader = new GetRouteInfoRequestHeader();
        //头部设置topic
        requestHeader.setTopic(topic);

        //通过code和header构造出RemotingCommand
        RemotingCommand request = RemotingCommand.createRequestCommand(RequestCode.GET_ROUTEINFO_BY_TOPIC, requestHeader);

        //执行rpc
        RemotingCommand response = this.remotingClient.invokeSync(null, request, timeoutMillis);
        //断言response不能为空
        assert response != null;
        switch (response.getCode()) {
            //如果topic不存在
            case ResponseCode.TOPIC_NOT_EXIST: {
                if (allowTopicNotExist) {
                    log.warn("get Topic [{}] RouteInfoFromNameServer is not exist value", topic);
                }

                break;
            }
            //如果获取topic路由信息成功
            case ResponseCode.SUCCESS: {
                byte[] body = response.getBody();
                if (body != null) {
                    //解码
                    return TopicRouteData.decode(body, TopicRouteData.class);
                }
            }
            default:
                break;
        }

        throw new MQClientException(response.getCode(), response.getRemark());
    }
```

```java
//org.apache.rocketmq.remoting.netty.NettyRemotingClient#invokeSync	
/**
     * 远程rpc请求
     */
    @Override
    public RemotingCommand invokeSync(String addr, final RemotingCommand request, long timeoutMillis)
        throws InterruptedException, RemotingConnectException, RemotingSendRequestException, RemotingTimeoutException {
        //开始时间戳
        long beginStartTime = System.currentTimeMillis();
        //获取 channel
        final Channel channel = this.getAndCreateChannel(addr);
        if (channel != null && channel.isActive()) {
            try {
                //执行rpcHook.doBeforeRequest，执行前回调
                doBeforeRpcHooks(addr, request);
                //计算花费时间
                long costTime = System.currentTimeMillis() - beginStartTime;
                if (timeoutMillis < costTime) {
                    throw new RemotingTimeoutException("invokeSync call timeout");
                }
                //执行rpc请求，返回结果
                RemotingCommand response = this.invokeSyncImpl(channel, request, timeoutMillis - costTime);
                //执行rpcHook.doAfterResponse
                doAfterRpcHooks(RemotingHelper.parseChannelRemoteAddr(channel), request, response);
                return response;
            } catch (RemotingSendRequestException e) {
                log.warn("invokeSync: send request exception, so close the channel[{}]", addr);
                this.closeChannel(addr, channel);
                throw e;
            } catch (RemotingTimeoutException e) {
                if (nettyClientConfig.isClientCloseSocketIfTimeout()) {
                    this.closeChannel(addr, channel);
                    log.warn("invokeSync: close socket because of timeout, {}ms, {}", timeoutMillis, addr);
                }
                log.warn("invokeSync: wait response timeout exception, the channel[{}]", addr);
                throw e;
            }
        } else {
            //如果channel为空或者连接已断开，则在lockChannelTables中remove掉
            this.closeChannel(addr, channel);
            throw new RemotingConnectException(addr);
        }
    }
```

更多细节请阅读https://github.com/WYUSIG/rocketmq.git sign-learn分支中文注释