package com.sign.www.order;

import com.sign.www.util.ConsumerUtil;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;

public class OrderedConsumer {

    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("orderConsumerGroup1");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("TopicOrder", "TagA || TagC || TagD");
        consumer.registerMessageListener((MessageListenerOrderly) (msgs, context) -> {
            ConsumerUtil.printfMessages(msgs);
            return ConsumeOrderlyStatus.SUCCESS;
        });
        consumer.start();
        System.out.printf("Consumer Started.%n");
    }
}
