package com.workshop.orders.dto;

import com.workshop.orders.domain.OrderStatus;

import java.math.BigDecimal;

public record OrderResponse(
        String orderId,
        OrderStatus status,
        BigDecimal amount,
        long totalMs,
        String providerReference,
        boolean duplicate
) {
}
