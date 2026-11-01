package com.workshop.orders.application;

import com.workshop.orders.domain.AuditEventEntity;
import com.workshop.orders.domain.OrderEntity;
import com.workshop.orders.domain.OrderStatus;
import com.workshop.orders.domain.PaymentAttemptEntity;
import com.workshop.orders.external.PaymentAuthorization;
import com.workshop.orders.external.PaymentDeclinedException;
import com.workshop.orders.external.PaymentGateway;
import com.workshop.orders.metrics.OrderMetrics;
import com.workshop.orders.metrics.TransactionTracker;
import com.workshop.orders.repository.AuditEventRepository;
import com.workshop.orders.repository.OrderRepository;
import com.workshop.orders.repository.PaymentAttemptRepository;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final PaymentAttemptRepository paymentAttempts;
    private final AuditEventRepository auditEvents;
    private final PaymentGateway paymentGateway;
    private final OrderMetrics metrics;
    private final TransactionTracker transactionTracker;

    public OrderService(OrderRepository orders,
                        PaymentAttemptRepository paymentAttempts,
                        AuditEventRepository auditEvents,
                        PaymentGateway paymentGateway,
                        OrderMetrics metrics,
                        TransactionTracker transactionTracker) {
        this.orders = orders;
        this.paymentAttempts = paymentAttempts;
        this.auditEvents = auditEvents;
        this.paymentGateway = paymentGateway;
        this.metrics = metrics;
        this.transactionTracker = transactionTracker;
    }

    /**
     * Оформление заказа. Транзакция открывается здесь и охватывает весь сценарий:
     * запись заказа, вызов платёжного провайдера и запись результата.
     */
    @Transactional
    public OrderResult placeOrder(PlaceOrderCommand command) {
        metrics.orderStarted();
        Timer.Sample total = metrics.startOrderTimer();
        long startedAt = System.nanoTime();
        String result = "failed";

        try {
            transactionTracker.trackCurrentTransaction();

            var existing = orders.findByIdempotencyKey(command.idempotencyKey());
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

            OrderEntity order = orders.save(new OrderEntity(
                    orderId, command.customerId(), command.amount(),
                    command.idempotencyKey(), OrderStatus.PENDING));
            auditEvents.save(new AuditEventEntity(orderId, "ORDER_CREATED",
                    "Order created and awaiting payment"));

            Timer.Sample paymentSample = metrics.startPaymentTimer();
            long paymentStartedAt = System.nanoTime();
            PaymentAuthorization authorization;
            try {
                authorization = paymentGateway.authorize(orderId, command.amount());
            } finally {
                long paymentLatencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - paymentStartedAt);
                metrics.stopPaymentTimer(paymentSample, "inside_transaction", paymentLatencyMs);
            }

            paymentAttempts.save(new PaymentAttemptEntity(orderId, authorization.providerReference(),
                    authorization.status(), authorization.latencyMs(), Instant.now()));
            order.markPaid();
            auditEvents.save(new AuditEventEntity(orderId, "ORDER_PAID",
                    "Payment approved: " + authorization.providerReference()));

            result = "success";
            log.info("Order {} paid in {}ms", orderId, elapsedMs(startedAt));
            return new OrderResult(orderId, OrderStatus.PAID, order.getAmount(),
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

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
