package com.workshop.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "payment_attempts")
public class PaymentAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_public_id", nullable = false, length = 36)
    private String orderPublicId;

    @Column(name = "provider_reference", nullable = false, length = 80)
    private String providerReference;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentAttemptEntity() {
    }

    public PaymentAttemptEntity(String orderPublicId, String providerReference,
                                String status, long latencyMs, Instant createdAt) {
        this.orderPublicId = orderPublicId;
        this.providerReference = providerReference;
        this.status = status;
        this.latencyMs = latencyMs;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getOrderPublicId() {
        return orderPublicId;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getStatus() {
        return status;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
