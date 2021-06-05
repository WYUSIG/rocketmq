## RocketMq源码系列学习



入口：

DefaultMQProducer#send(Message)



#### 发送前校验与topic准备工作

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

```java
public class Validators {
	/**
     * 校验消息
     * @param msg 消息
     * @param defaultMQProducer 消息发送者
     * @throws MQClientException
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

