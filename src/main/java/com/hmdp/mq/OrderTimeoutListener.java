package com.hmdp.mq;

import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.MqConstants.ORDER_TIMEOUT_CONSUMER_GROUP;
import static com.hmdp.utils.MqConstants.ORDER_TIMEOUT_TOPIC;
import static com.hmdp.utils.RedisConstants.ORDER_LOCK_KEY;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = ORDER_TIMEOUT_TOPIC,
        consumerGroup = ORDER_TIMEOUT_CONSUMER_GROUP
)
public class OrderTimeoutListener implements RocketMQListener<String> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public void onMessage(String orderIdStr) {
        log.info("收到订单超时延迟消息，orderId={}", orderIdStr);

        Long orderId;
        try {
            orderId = Long.parseLong(orderIdStr);
        } catch (NumberFormatException e) {
            log.error("超时消息中的订单ID格式错误，orderIdStr={}", orderIdStr, e);
            return;
        }

        RLock lock = redissonClient.getLock(ORDER_LOCK_KEY + orderId);
        boolean isLock = false;
        try {
            isLock = lock.tryLock(1, 30, TimeUnit.SECONDS);
            if (!isLock) {
                log.warn("获取订单锁失败，稍后重试，orderId={}", orderId);
                throw new RuntimeException("获取订单锁失败，orderId=" + orderId);
            }
            voucherOrderService.closeTimeoutOrder(orderId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取订单锁被中断，orderId=" + orderId, e);
        } finally {
            if (isLock && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}