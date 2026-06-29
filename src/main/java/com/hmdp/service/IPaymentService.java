package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * 支付服务接口。
 * <p>
 * 核心方法：handlePaymentCallback —— 处理支付回调（微信/支付宝/模拟支付共用）。
 * 此方法内部使用分布式锁（lock:order:{orderId}），与 OrderTimeoutListener 争同一把锁，
 * 避免"支付成功"和"超时关单"同时修改同一条订单。
 */
public interface IPaymentService {

    /**
     * 处理支付回调：校验订单状态，将订单标记为"已支付"。
     *
     * @param orderId 订单 ID
     * @return 处理结果
     */
    Result handlePaymentCallback(Long orderId);
}
