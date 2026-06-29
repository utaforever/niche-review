package com.hmdp.mq;

import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class RocketMqSmokeTest {

    private static final String NAME_SERVER = "127.0.0.1:9876";
    private static final String TOPIC = "hmdp-test-topic";

    @Test
    void shouldSendAndPullMessage() throws Exception {
        String suffix = String.valueOf(System.currentTimeMillis());
        String producerGroup = "hmdp_smoke_producer_" + suffix;
        String consumerGroup = "hmdp_smoke_consumer_" + suffix;
        String body = "rocketmq-smoke-" + UUID.randomUUID();

        DefaultMQProducer producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(NAME_SERVER);
        producer.start();

        DefaultMQPullConsumer consumer = new DefaultMQPullConsumer(consumerGroup);
        consumer.setNamesrvAddr(NAME_SERVER);
        consumer.start();

        try {
            Message message = new Message(TOPIC, body.getBytes(StandardCharsets.UTF_8));
            SendResult sendResult = producer.send(message);
            System.out.println("RocketMQ send result: " + sendResult);

            PullResult pullResult = consumer.pullBlockIfNotFound(
                    sendResult.getMessageQueue(),
                    null,
                    sendResult.getQueueOffset(),
                    1
            );
            System.out.println("RocketMQ pull result: " + pullResult);

            Assertions.assertEquals(PullStatus.FOUND, pullResult.getPullStatus());
            Assertions.assertFalse(pullResult.getMsgFoundList().isEmpty());

            MessageExt received = pullResult.getMsgFoundList().get(0);
            String receivedBody = new String(received.getBody(), StandardCharsets.UTF_8);
            Assertions.assertEquals(body, receivedBody);
        } finally {
            consumer.shutdown();
            producer.shutdown();
        }
    }
}