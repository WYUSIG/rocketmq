package com.sign.www.batch;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;

import java.util.ArrayList;
import java.util.List;

public class BatchMessageProducer {

    public static void main(String[] args) throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer("batchProducerGroup1");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();
        List<Message> messageList = new ArrayList<>();

    }
}
