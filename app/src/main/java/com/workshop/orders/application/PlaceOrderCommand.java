package com.workshop.orders.application;

import java.math.BigDecimal;

public record PlaceOrderCommand(
        String customerId,
        BigDecimal amount,
        String idempotencyKey
) {
}
