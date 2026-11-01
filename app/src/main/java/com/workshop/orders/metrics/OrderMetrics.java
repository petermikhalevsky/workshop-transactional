package com.workshop.orders.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OrderMetrics {

    private final MeterRegistry registry;

    private final AtomicInteger inFlightOrders = new AtomicInteger();
    private final AtomicInteger activeTransactions = new AtomicInteger();
    private final AtomicInteger poolSaturated = new AtomicInteger();
    private final AtomicLong lastPaymentLatencyMs = new AtomicLong();
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong duplicateCount = new AtomicLong();

    public OrderMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("workshop.orders.in.flight", inFlightOrders, AtomicInteger::get)
                .description("Orders currently being processed")
                .register(registry);
        Gauge.builder("workshop.transactions.active", activeTransactions, AtomicInteger::get)
                .description("Database transactions open right now")
                .register(registry);
        Gauge.builder("workshop.pool.saturated", poolSaturated, AtomicInteger::get)
                .description("1 when all connections are busy and requests are queued")
                .register(registry);
        Gauge.builder("workshop.payment.last.latency", lastPaymentLatencyMs, AtomicLong::get)
                .description("Latency of the last payment provider call")
                .baseUnit("milliseconds")
                .register(registry);
    }

    public void orderStarted() {
        inFlightOrders.incrementAndGet();
    }

    public void orderFinished(String result) {
        inFlightOrders.decrementAndGet();
        Counter.builder("workshop.orders")
                .description("Orders by processing result")
                .tag("result", result)
                .register(registry)
                .increment();
        switch (result) {
            case "success" -> successCount.incrementAndGet();
            case "duplicate" -> duplicateCount.incrementAndGet();
            default -> failedCount.incrementAndGet();
        }
    }

    public Timer.Sample startOrderTimer() {
        return Timer.start(registry);
    }

    public void stopOrderTimer(Timer.Sample sample, String result) {
        sample.stop(Timer.builder("workshop.order.total")
                .description("End-to-end order processing time")
                .tag("result", result)
                .publishPercentileHistogram()
                .register(registry));
    }

    public Timer.Sample startPaymentTimer() {
        return Timer.start(registry);
    }

    public void stopPaymentTimer(Timer.Sample sample, String scope, long latencyMs) {
        sample.stop(Timer.builder("workshop.payment.call")
                .description("Payment provider call time")
                .tag("scope", scope)
                .publishPercentileHistogram()
                .register(registry));
        lastPaymentLatencyMs.set(latencyMs);
    }

    public void transactionStarted() {
        activeTransactions.incrementAndGet();
    }

    public void transactionFinished() {
        activeTransactions.decrementAndGet();
    }

    public void setPoolSaturated(boolean saturated) {
        poolSaturated.set(saturated ? 1 : 0);
    }

    public int inFlightOrders() {
        return inFlightOrders.get();
    }

    public int activeTransactions() {
        return activeTransactions.get();
    }

    public boolean poolSaturated() {
        return poolSaturated.get() == 1;
    }

    public long lastPaymentLatencyMs() {
        return lastPaymentLatencyMs.get();
    }

    public long successCount() {
        return successCount.get();
    }

    public long failedCount() {
        return failedCount.get();
    }

    public long duplicateCount() {
        return duplicateCount.get();
    }
}
