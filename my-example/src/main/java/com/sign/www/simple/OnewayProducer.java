package com.sign.www.simple;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 发出去就不管
 * OneWay Mode
 */
public class OnewayProducer {

    public static void main(String[] args) throws MQClientException, RemotingException, InterruptedException {
        DefaultMQProducer producer = new DefaultMQProducer("producerGroup3");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String s = scanner.next();
            Message message = new Message("TopicA", "Tag1", s.getBytes(StandardCharsets.UTF_8));
            producer.sendOneway(message);
        }
    }
}
