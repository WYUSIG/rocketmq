package com.sign.www.simple;

import com.sign.www.util.ProducerUtil;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 异步发送
 */
public class AsyncProducer {

    public static void main(String[] args) throws MQClientException, RemotingException, InterruptedException {
        DefaultMQProducer producer = new DefaultMQProducer("producerGroup2");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();
        producer.setRetryTimesWhenSendAsyncFailed(0);
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String s = scanner.next();
            Message message = new Message("TopicA", "Tag1", s.getBytes(StandardCharsets.UTF_8));
            producer.send(message, new SendCallback() {

                @Override
                public void onSuccess(SendResult sendResult) {
                    ProducerUtil.printfSendResult(sendResult);
                }

                @Override
                public void onException(Throwable e) {
                    e.printStackTrace();
                }
            });
        }
    }
}
