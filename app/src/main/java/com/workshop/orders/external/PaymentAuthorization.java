package com.workshop.orders.external;

public record PaymentAuthorization(
        String providerReference,
        String status,
        long latencyMs
) {
}
