package com.sign.www.filter;

import com.sign.www.util.ProducerUtil;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;

import java.nio.charset.StandardCharsets;

public class FilterProducer2 {

    public static void main(String[] args) throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
        DefaultMQProducer producer = new DefaultMQProducer("filterProducerGroup1");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();
        for (int i = 0; i < 5; i++) {
            Message message = new Message("TopicFilter", "TagA", ("Hello Rocket" + i).getBytes(StandardCharsets.UTF_8));
            message.putUserProperty("a", String.valueOf(i));
            SendResult sendResult = producer.send(message);
            ProducerUtil.printfSendResult(sendResult);
        }
    }
}
