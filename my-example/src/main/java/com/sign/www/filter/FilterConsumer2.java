package com.sign.www.filter;

import com.sign.www.util.ConsumerUtil;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.MessageSelector;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;

public class FilterConsumer2 {

    public static void main(String[] args) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("filterConsumerGroup1");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("TopicFilter", MessageSelector.bySql("a between 1 and 3"));
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            ConsumerUtil.printfMessages(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }
}
