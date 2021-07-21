package com.sign.www.util;

import org.apache.rocketmq.client.producer.SendResult;

public class ProducerUtil {

    public static void printfSendResult(SendResult sendResult) {
        System.out.println("sendStatus: " + sendResult.getSendStatus() + ", msgId: " + sendResult.getMsgId() + ", messageQueue: " + sendResult.getMessageQueue());
    }
}
