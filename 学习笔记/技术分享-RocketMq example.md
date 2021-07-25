## 每周技术分享

2021-07-25



[TOC]



### 1、RocketMq由来

ActiveMq->Notify

Kafka->MetaQ

MetaQ->RocketMq

ps：

Kafka的《The Metamorphosis》



### 2、RocketMq简单示例



#### 2.1、同步发送

生产者：

```java
public class SyncProducer {

    public static void main(String[] args) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("producerGroup1");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();
        for (int i = 0; i < 100; i++) {
            Message message = new Message("TopicA", ("Hello World" + i).getBytes(StandardCharsets.UTF_8));
            SendResult sendResult = producer.send(message);
            ProducerUtil.printfSendResult(sendResult);
        }
        Scanner sc = new Scanner(System.in);
        while (true) {
            String s = sc.next();
            try {
                Message message = new Message("TopicTest", s.getBytes(StandardCharsets.UTF_8));
                SendResult sendResult = producer.send(message);
                ProducerUtil.printfSendResult(sendResult);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
```

消费者：

简单示例的消费者示例代码都一样，后面就不重复

```java
public class Consumer {

    public static void main(String[] args) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("consumerGroup1");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("TopicA", "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            ConsumerUtil.printfMessages(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }
}
```



#### 2.2、异步发送

```java
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
```



#### 2.3、OneWay Mode发送

OneWay Mode：与UDP类似，这个方法在返回之前不会等待来自代理的确认。显然，它有最大的吞吐量，但潜在的消息丢失。

```java
public class OnewayProducer {

    public static void main(String[] args) throws MQClientException, RemotingException, InterruptedException {
        DefaultMQProducer producer = new DefaultMQProducer("producerGroup3");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String s = scanner.next();
            Message message = new Message("TopicA", "Tag1", s.getBytes(StandardCharsets.UTF_8));
            producer.sendOneway(message);
        }
    }
}
```





### 3、批量发送



#### 3.1、批量发送示例

生产者：

```java
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
```

消费者：(跟普通消费者一样)

```java
public class BatchConsumer {

    public static void main(String[] args) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("batchConsumerGroup1");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("TopicBatch", "TagA");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            ConsumerUtil.printfMessages(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }
}
```



#### 3.2、批量发送注意事项

同一批次的消息应该具有：相同的主题，没有延迟消费消息。

此外，一批消息的总大小不应超过 1MiB。

```java
public class ListSplitter implements Iterator<List<Message>> {
    private final int SIZE_LIMIT = 1000 * 1000;
    private final List<Message> messages;
    private int currIndex;

    public ListSplitter(List<Message> messages) {
        this.messages = messages;
    }

    @Override
    public boolean hasNext() {
        return currIndex < messages.size();
    }

    @Override
    public List<Message> next() {
        int nextIndex = currIndex;
        int totalSize = 0;
        for (; nextIndex < messages.size(); nextIndex++) {
            Message message = messages.get(nextIndex);
            int tmpSize = message.getTopic().length() + message.getBody().length;
            Map<String, String> properties = message.getProperties();
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                tmpSize += entry.getKey().length() + entry.getValue().length();
            }
            tmpSize = tmpSize + 20; //for log overhead
            if (tmpSize > SIZE_LIMIT) {
                //it is unexpected that single message exceeds the SIZE_LIMIT
                //here just let it go, otherwise it will block the splitting process
                if (nextIndex - currIndex == 0) {
                    //if the next sublist has no element, add this one and then break, otherwise just break
                    nextIndex++;
                }
                break;
            }
            if (tmpSize + totalSize > SIZE_LIMIT) {
                break;
            } else {
                totalSize += tmpSize;
            }

        }
        List<Message> subList = messages.subList(currIndex, nextIndex);
        currIndex = nextIndex;
        return subList;
    }
}
```



### 4、广播消息

生产者：(普通生产者)

```java
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
```

消费者：(为了让同一个消费者组里面的消费者也能都收到消息)

```java
public class BroadcastConsumer1 {

    public static void main(String[] args) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("broadcastConsumerGroup1");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setMessageModel(MessageModel.BROADCASTING);
        consumer.subscribe("TopicBroadcast", "TagA");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            ConsumerUtil.printfMessages(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }
}

public class BroadcastConsumer2 {

    public static void main(String[] args) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("broadcastConsumerGroup1");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setMessageModel(MessageModel.BROADCASTING);
        consumer.subscribe("TopicBroadcast", "TagA");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            ConsumerUtil.printfMessages(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }
}
```



### 5、顺序消费

前提知识：需要顺序消费就必须发送到同一个MessageQueue下

生产者：

```java
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

```

消费者：(普通消费者)

```java
public class OrderedConsumer {

    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("orderConsumerGroup1");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("TopicOrder", "TagA || TagC || TagD");
        consumer.registerMessageListener((MessageListenerOrderly) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                byte[] body = msg.getBody();
                System.out.println(Thread.currentThread().getName() + "消费了：" + new String(body));
            }
            return ConsumeOrderlyStatus.SUCCESS;
        });
        consumer.start();
        System.out.printf("Consumer Started.%n");
    }
}
```



### 6、延迟消费

生产者：

```java
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
```

org.apache.rocketmq.store.config.MessageStoreConfig#messageDelayLevel

| DelayTimeLevel | 时间 |
| -------------- | ---- |
| 1              | 1s   |
| 2              | 5s   |
| 3              | 10s  |
| 4              | 30s  |
| 5              | 1m   |
| 6              | 2m   |
| 7              | 3m   |
| 8              | 4m   |
| 9              | 5m   |
| 10             | 6m   |
| 11             | 7m   |
| 12             | 8m   |
| 13             | 9m   |
| 14             | 10m  |
| 15             | 20m  |
| 16             | 30m  |
| 17             | 1h   |
| 18             | 2h   |

消费者：(普通消费者)

```java
public class ScheduledMessageConsumer {

    public static void main(String[] args) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("scheduledConsumerGroup1");
        consumer.subscribe("TopicSchedule", "TagA");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.registerMessageListener((MessageListenerConcurrently)(msgs, context) -> {
            ConsumerUtil.printfMessages(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }
}
```



### 7、过滤器消费



#### 7.1、基于Tag

生产者：

```java
public class FilterProducer1 {

    public static void main(String[] args) throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
        DefaultMQProducer producer = new DefaultMQProducer("filterProducerGroup2");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();
        for (int i = 0; i < 5; i++) {
            Message message = new Message("TopicFilter", "Tag" + i, ("Hello Rocket" + i).getBytes(StandardCharsets.UTF_8));
            SendResult sendResult = producer.send(message);
            ProducerUtil.printfSendResult(sendResult);
        }
    }
}
```

消费者：

```java
public class FilterConsumer1 {

    public static void main(String[] args) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("filterConsumerGroup2");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("TopicFilter", "Tag0 || Tag2");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            ConsumerUtil.printfMessages(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }
}
```



#### 7.2、基于自定义消息属性

生产者：

```java
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
```

消费者：

```java
public class FilterConsumer2 {

    public static void main(String[] args) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("filterConsumerGroup1");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("TopicFilter", MessageSelector.bySql("a between 1 and 3"));
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            ConsumerUtil.printfMessages(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }
}
```





