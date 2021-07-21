package com.sign.www.order;

import com.sign.www.util.ProducerUtil;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;

import java.nio.charset.StandardCharsets;

/**
 * 顺序消息-生产者
 */
public class OrderedProducer {

    public static void main(String[] args) throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
        DefaultMQProducer producer = new DefaultMQProducer("orderProducerGroup1");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();
        for (int i = 0; i < 100; i++) {
            int mqIndex = 0;
            Message message = new Message("TopicOrder", "TagA", ("Hello Rocket" + i).getBytes(StandardCharsets.UTF_8));
            SendResult sendResult = producer.send(message, (mqs, msg, arg) -> {
                Integer id = (Integer) arg;
                int index = id % mqs.size();
                return mqs.get(index);
            }, mqIndex);
            ProducerUtil.printfSendResult(sendResult);
        }
        producer.shutdown();
    }
}
