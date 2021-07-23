package com.sign.www.batch;

import com.sign.www.util.ProducerUtil;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BatchMessageProducer {

    public static void main(String[] args) throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer("batchProducerGroup1");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();
        List<Message> messageList = new ArrayList<>();
        messageList.add(new Message("TopicBatch", "TagA", "A".getBytes(StandardCharsets.UTF_8)));
        messageList.add(new Message("TopicBatch", "TagA", "B".getBytes(StandardCharsets.UTF_8)));
        messageList.add(new Message("TopicBatch", "TagA", "C".getBytes(StandardCharsets.UTF_8)));
        ListSplitter splitter = new ListSplitter(messageList);
        while (splitter.hasNext()) {
            try {
                List<Message>  listItem = splitter.next();
                SendResult sendResult = producer.send(listItem);
                ProducerUtil.printfSendResult(sendResult);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
