package com.workshop.orders.dto;

public record ErrorResponse(
        String code,
        String message,
        String orderId
) {
    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }
}
