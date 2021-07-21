package com.sign.www.broadcast;

import com.sign.www.util.ProducerUtil;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class BroadcastProducer {

    public static void main(String[] args) throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
        DefaultMQProducer producer = new DefaultMQProducer("broadCastProducerGroup1");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String s = scanner.next();
            Message message = new Message("TopicBroadcast", "TagA", s.getBytes(StandardCharsets.UTF_8));
            SendResult sendResult = producer.send(message);
            ProducerUtil.printfSendResult(sendResult);
        }
    }
}
