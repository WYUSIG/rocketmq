package com.sign.www.simple;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.util.StatusPrinter;
import com.sign.www.util.ProducerUtil;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 同步发送
 */
public class SyncProducer {

    public static void main(String[] args) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("producerGroup1");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();
        Scanner sc = new Scanner(System.in);
        while (true) {
            String s = sc.next();
            try {
                Message message = new Message("TopicA", s.getBytes(StandardCharsets.UTF_8));
                SendResult sendResult = producer.send(message);
                ProducerUtil.printfSendResult(sendResult);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
