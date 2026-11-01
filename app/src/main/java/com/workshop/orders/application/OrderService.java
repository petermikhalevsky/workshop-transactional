package com.workshop.orders.application;

import com.workshop.orders.domain.OrderEntity;
import com.workshop.orders.external.PaymentAuthorization;
import com.workshop.orders.external.PaymentDeclinedException;
import com.workshop.orders.external.PaymentGateway;
import com.workshop.orders.metrics.OrderMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Оркестратор оформления заказа. Собственной транзакции у него нет:
 * {@link OrderPersistenceService} открывает короткие транзакции только вокруг
 * запросов к БД, а платёжный шлюз вызывается между ними.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderPersistenceService persistence;
    private final PaymentGateway paymentGateway;
    private final OrderMetrics metrics;

    public OrderService(OrderPersistenceService persistence,
                        PaymentGateway paymentGateway,
                        OrderMetrics metrics) {
        this.persistence = persistence;
        this.paymentGateway = paymentGateway;
        this.metrics = metrics;
    }

    public OrderResult placeOrder(PlaceOrderCommand command) {
        metrics.orderStarted();
        Timer.Sample total = metrics.startOrderTimer();
        long startedAt = System.nanoTime();
        String result = "failed";

        try {
            var existing = persistence.findByIdempotencyKey(command.idempotencyKey());
            if (existing.isPresent()) {
                result = "duplicate";
                OrderEntity order = existing.get();
                log.info("Duplicate request for idempotencyKey={}, returning order {}",
                        command.idempotencyKey(), order.getPublicId());
                return new OrderResult(order.getPublicId(), order.getStatus(), order.getAmount(),
                        elapsedMs(startedAt), null, true);
            }

            String orderId = UUID.randomUUID().toString();
            log.info("Placing order {}: customerId={}, amount={}",
                    orderId, command.customerId(), command.amount());

            persistence.createPending(orderId, command);

            Timer.Sample paymentSample = metrics.startPaymentTimer();
            long paymentStartedAt = System.nanoTime();
            PaymentAuthorization authorization;
            try {
                authorization = paymentGateway.authorize(orderId, command.amount());
            } catch (RuntimeException e) {
                markFailedQuietly(orderId, e);
                throw e;
            } finally {
                long paymentLatencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - paymentStartedAt);
                metrics.stopPaymentTimer(paymentSample, "outside_transaction", paymentLatencyMs);
            }

            OrderEntity paid = persistence.markPaid(orderId, authorization);
            result = "success";
            log.info("Order {} paid in {}ms", orderId, elapsedMs(startedAt));
            return new OrderResult(paid.getPublicId(), paid.getStatus(), paid.getAmount(),
                    elapsedMs(startedAt), authorization.providerReference(), false);
        } catch (PaymentDeclinedException e) {
            log.warn("Payment declined: {}", e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.warn("Could not place the order: {}", e.getMessage());
            throw e;
        } finally {
            metrics.stopOrderTimer(total, result);
            metrics.orderFinished(result);
        }
    }

    private void markFailedQuietly(String orderId, RuntimeException cause) {
        try {
            persistence.markFailed(orderId, cause.getMessage());
        } catch (RuntimeException failure) {
            cause.addSuppressed(failure);
            log.warn("Could not mark order {} as failed: {}", orderId, failure.getMessage());
        }
    }

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
