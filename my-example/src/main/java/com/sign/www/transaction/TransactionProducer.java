package com.sign.www.transaction;

import com.sign.www.util.ProducerUtil;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.common.RemotingHelper;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.*;

public class TransactionProducer {

    public static void main(String[] args) throws MQClientException, InterruptedException, UnsupportedEncodingException {
        TransactionListener transactionListener = new TransactionListenerImpl();
        TransactionMQProducer producer = new TransactionMQProducer("TransactionProducerGroup1");
        producer.setNamesrvAddr("localhost:9876");
        producer.setTransactionListener(transactionListener);
        producer.start();
        for (int i = 0; i < 10; i++) {
            Message msg = new Message("TopicTransaction", "TagA",
                            ("Hello RocketMQ " + i).getBytes(RemotingHelper.DEFAULT_CHARSET));
//            TransactionSendResult sendResult =
            msg.setTransactionId(String.valueOf(i));
                    producer.sendMessageInTransaction(msg, "A");
//            ProducerUtil.printfSendResult(sendResult);
        }
    }
}
