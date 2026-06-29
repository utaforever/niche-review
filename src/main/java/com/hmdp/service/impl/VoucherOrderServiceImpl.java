package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

import static com.hmdp.utils.MqConstants.ORDER_PAYMENT_TIMEOUT_MILLIS;
import static com.hmdp.utils.MqConstants.ORDER_TIMEOUT_TOPIC;
import static com.hmdp.utils.MqConstants.VOUCHER_ORDER_TOPIC;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );

        int r = result == null ? 1 : result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        String message = JSONUtil.toJsonStr(voucherOrder);
        try {
            SendResult sendResult = rocketMQTemplate.syncSend(VOUCHER_ORDER_TOPIC, message);
            log.info("Voucher order message sent, sendStatus={}, order={}", sendResult.getSendStatus(), message);
        } catch (Exception e) {
            log.error("Failed to send voucher order message, rollback Redis reservation. orderId={}, voucherId={}, userId={}",
                    orderId, voucherId, userId, e);
            rollbackSeckillReservation(voucherId, userId);
            return Result.fail("系统繁忙，请稍后重试");
        }

        try {
            rocketMQTemplate.syncSendDelayTimeMills(
                    ORDER_TIMEOUT_TOPIC,
                    String.valueOf(orderId),
                    ORDER_PAYMENT_TIMEOUT_MILLIS
            );
            log.info("Order timeout message sent, orderId={}, delayMillis={}", orderId, ORDER_PAYMENT_TIMEOUT_MILLIS);
        } catch (Exception e) {
            log.error("Failed to send order timeout message, orderId={}", orderId, e);
        }

        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public void closeTimeoutOrder(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null) {
            throw new RuntimeException("Order not found while closing timeout order, orderId=" + orderId);
        }

        if (!VoucherOrder.STATUS_UNPAID.equals(order.getStatus())) {
            log.info("Order status is not unpaid, skip close. orderId={}, currentStatus={}", orderId, order.getStatus());
            return;
        }

        boolean orderUpdated = update()
                .set("status", VoucherOrder.STATUS_CANCELLED)
                .set("update_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("status", VoucherOrder.STATUS_UNPAID)
                .update();
        if (!orderUpdated) {
            throw new RuntimeException("Failed to close timeout order, orderId=" + orderId);
        }

        boolean stockUpdated = seckillVoucherService
                .update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        if (!stockUpdated) {
            throw new RuntimeException("Failed to rollback MySQL voucher stock, orderId=" + orderId);
        }

        rollbackSeckillReservation(order.getVoucherId(), order.getUserId());
        log.info("Timeout order closed, orderId={}, voucherId={}, userId={}",
                orderId, order.getVoucherId(), order.getUserId());
    }

    private void rollbackSeckillReservation(Long voucherId, Long userId) {
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        String orderKey = SECKILL_ORDER_KEY + voucherId;
        stringRedisTemplate.opsForValue().increment(stockKey, 1);
        stringRedisTemplate.opsForSet().remove(orderKey, userId.toString());
        log.info("Redis seckill reservation rolled back, voucherId={}, userId={}", voucherId, userId);
    }

    @Override
    @Transactional
    public void voucherOrder(VoucherOrder voucherOrder) {
        VoucherOrder oldOrder = getById(voucherOrder.getId());
        if (oldOrder != null) {
            log.info("Duplicate voucher order message ignored, orderId={}, userId={}, voucherId={}",
                    voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId());
            return;
        }

        Long userId = voucherOrder.getUserId();

        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (count > 0) {
            log.info("User already has voucher order, ignore duplicate message. userId={}, voucherId={}",
                    userId, voucherOrder.getVoucherId());
            return;
        }

        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            throw new RuntimeException("Failed to deduct MySQL voucher stock, voucherId="
                    + voucherOrder.getVoucherId() + ", orderId=" + voucherOrder.getId());
        }

        voucherOrder.setStatus(VoucherOrder.STATUS_UNPAID);
        boolean saved = save(voucherOrder);
        if (!saved) {
            throw new RuntimeException("Failed to save voucher order, orderId=" + voucherOrder.getId());
        }

        log.info("Voucher order created, orderId={}, userId={}, voucherId={}",
                voucherOrder.getId(), userId, voucherOrder.getVoucherId());
    }
}
