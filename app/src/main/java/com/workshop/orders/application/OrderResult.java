package com.workshop.orders.application;

import com.workshop.orders.domain.OrderStatus;

import java.math.BigDecimal;

public record OrderResult(
        String orderId,
        OrderStatus status,
        BigDecimal amount,
        long totalMs,
        String providerReference,
        boolean duplicate
) {
}
