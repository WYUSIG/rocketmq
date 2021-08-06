package com.sign.www.schedule;

import com.sign.www.util.ProducerUtil;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;

import java.nio.charset.StandardCharsets;

/**
 * @see org.apache.rocketmq.store.config.MessageStoreConfig#messageDelayLevel
 */
public class ScheduledMessageProducer {

    public static void main(String[] args) throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
        DefaultMQProducer producer = new DefaultMQProducer("scheduledProducerGroup1");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();
        Message message = new Message("TopicSchedule", "TagA", "Hello Schedule".getBytes(StandardCharsets.UTF_8));
        message.setDelayTimeLevel(3);
        SendResult sendResult = producer.send(message);
        ProducerUtil.printfSendResult(sendResult);
    }
}
