package com.workshop.orders.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PaymentGateway.class);

    private final RestClient paymentRestClient;

    public PaymentGateway(RestClient paymentRestClient) {
        this.paymentRestClient = paymentRestClient;
    }

    public PaymentAuthorization authorize(String orderId, BigDecimal amount) {
        long startedAt = System.nanoTime();
        try {
            PaymentResponse response = paymentRestClient.post()
                    .uri("/v1/payments/authorize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("orderId", orderId, "amount", amount))
                    .retrieve()
                    .body(PaymentResponse.class);

            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (response == null || !"APPROVED".equalsIgnoreCase(response.status())) {
                throw new PaymentDeclinedException("Payment provider declined the operation");
            }
            log.info("Payment approved: orderId={}, providerReference={}, latency={}ms",
                    orderId, response.providerReference(), latencyMs);
            return new PaymentAuthorization(response.providerReference(), response.status(), latencyMs);
        } catch (RestClientResponseException e) {
            throw new PaymentDeclinedException(
                    "Payment provider returned " + e.getStatusCode().value());
        }
    }
}
