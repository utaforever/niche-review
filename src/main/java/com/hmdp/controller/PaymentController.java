package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IPaymentService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Slf4j
@RestController
@RequestMapping("/payment")
public class PaymentController {

    @Resource
    private IPaymentService paymentService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 模拟支付回调，开发测试用。
     */
    @PostMapping("/simulate/{id}")
    public Result simulatePayment(@PathVariable("id") Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            return Result.fail("订单不属于当前用户");
        }
        if (!VoucherOrder.STATUS_UNPAID.equals(order.getStatus())) {
            return Result.fail("订单状态不正确，无法支付");
        }

        log.info("收到模拟支付回调，orderId={}, userId={}", orderId, userId);
        return paymentService.handlePaymentCallback(orderId);
    }

    /**
     * 微信支付回调，真实场景由微信服务器调用。
     */
    @PostMapping("/callback/wechat/{id}")
    public String wechatPayCallback(@PathVariable("id") Long orderId) {
        log.info("收到微信支付回调，orderId={}", orderId);
        Result result = paymentService.handlePaymentCallback(orderId);
        return result.getSuccess() ? "SUCCESS" : "FAIL";
    }

    /**
     * 支付宝支付回调，真实场景由支付宝服务器调用。
     */
    @PostMapping("/callback/alipay/{id}")
    public String alipayCallback(@PathVariable("id") Long orderId) {
        log.info("收到支付宝支付回调，orderId={}", orderId);
        Result result = paymentService.handlePaymentCallback(orderId);
        return result.getSuccess() ? "success" : "fail";
    }
}