package com.sign.www.simple;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class SyncProducer {

    public static void main(String[] args) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("test1");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();
        for (int i = 0; i < 100; i++) {
            Message message = new Message("TopicTest", ("Hello World" + i).getBytes(StandardCharsets.UTF_8));
            SendResult sendResult = producer.send(message);
            printfSendResult(sendResult);
        }
        while (true) {
            Scanner sc = new Scanner(System.in);
            String s = sc.next();
            try {
                Message message = new Message("TopicTest", s.getBytes(StandardCharsets.UTF_8));
                SendResult sendResult = producer.send(message);
                printfSendResult(sendResult);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void printfSendResult(SendResult sendResult) {
        System.out.println("sendStatus: " + sendResult.getSendStatus() + ", msgId: " + sendResult.getMsgId() + ", messageQueue: " + sendResult.getMessageQueue());
    }
}
