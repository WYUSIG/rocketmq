package com.sign.www.simple;

import com.sign.www.util.ConsumerUtil;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;

public class Consumer {

    public static void main(String[] args) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("consumerGroup1");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("TopicA", "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            ConsumerUtil.printfMessages(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }
}
