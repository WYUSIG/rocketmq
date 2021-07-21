package com.sign.www.util;

import org.apache.rocketmq.common.message.MessageExt;

import java.util.List;

public class ConsumerUtil {

    public static void printfMessages(List<MessageExt> messageExtList) {
        for (MessageExt messageExt : messageExtList) {
            byte[] body = messageExt.getBody();
            System.out.println(new String(body));
        }
    }
}
