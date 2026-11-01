package com.workshop.orders.external;

public record PaymentResponse(
        String providerReference,
        String status
) {
}
