package com.workshop.orders.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PlaceOrderRequest(
        @NotBlank(message = "customerId is required")
        @Size(max = 64, message = "customerId must be at most 64 characters")
        String customerId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        @Digits(integer = 17, fraction = 2, message = "amount supports at most 2 decimal places")
        BigDecimal amount,

        @NotBlank(message = "idempotencyKey is required")
        @Size(max = 80, message = "idempotencyKey must be at most 80 characters")
        String idempotencyKey
) {
}
