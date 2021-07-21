package com.sign.www.schedule;

import com.sign.www.util.ConsumerUtil;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;

public class ScheduledMessageConsumer {

    public static void main(String[] args) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("scheduledConsumerGroup1");
        consumer.subscribe("TopicSchedule", "TagA");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.registerMessageListener((MessageListenerConcurrently)(msgs, context) -> {
            ConsumerUtil.printfMessages(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }
}
