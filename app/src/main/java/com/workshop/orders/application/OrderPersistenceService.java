package com.workshop.orders.application;

import com.workshop.orders.domain.AuditEventEntity;
import com.workshop.orders.domain.OrderEntity;
import com.workshop.orders.domain.OrderStatus;
import com.workshop.orders.domain.PaymentAttemptEntity;
import com.workshop.orders.external.PaymentAuthorization;
import com.workshop.orders.metrics.TransactionTracker;
import com.workshop.orders.repository.AuditEventRepository;
import com.workshop.orders.repository.OrderRepository;
import com.workshop.orders.repository.PaymentAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Короткие транзакции только вокруг операций с БД.
 *
 * Внешний вызов сюда не попадает: метод {@code markPaid} вызывается уже после
 * ответа платёжного шлюза. Поэтому соединение удерживается миллисекунды, а не
 * сотни миллисекунд внешнего I/O.
 */
@Service
public class OrderPersistenceService {

    private final OrderRepository orders;
    private final PaymentAttemptRepository paymentAttempts;
    private final AuditEventRepository auditEvents;
    private final TransactionTracker transactionTracker;

    public OrderPersistenceService(OrderRepository orders,
                                   PaymentAttemptRepository paymentAttempts,
                                   AuditEventRepository auditEvents,
                                   TransactionTracker transactionTracker) {
        this.orders = orders;
        this.paymentAttempts = paymentAttempts;
        this.auditEvents = auditEvents;
        this.transactionTracker = transactionTracker;
    }

    @Transactional(readOnly = true)
    public Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey) {
        return orders.findByIdempotencyKey(idempotencyKey);
    }

    @Transactional
    public void createPending(String orderId, PlaceOrderCommand command) {
        transactionTracker.trackCurrentTransaction();
        orders.save(new OrderEntity(
                orderId, command.customerId(), command.amount(),
                command.idempotencyKey(), OrderStatus.PENDING));
        auditEvents.save(new AuditEventEntity(orderId, "ORDER_CREATED",
                "Order created and awaiting payment"));
    }

    @Transactional
    public OrderEntity markPaid(String orderId, PaymentAuthorization authorization) {
        transactionTracker.trackCurrentTransaction();
        OrderEntity order = orders.findByPublicId(orderId).orElseThrow();
        paymentAttempts.save(new PaymentAttemptEntity(orderId, authorization.providerReference(),
                authorization.status(), authorization.latencyMs(), Instant.now()));
        order.markPaid();
        auditEvents.save(new AuditEventEntity(orderId, "ORDER_PAID",
                "Payment approved: " + authorization.providerReference()));
        return order;
    }

    @Transactional
    public void markFailed(String orderId, String reason) {
        transactionTracker.trackCurrentTransaction();
        orders.findByPublicId(orderId).ifPresent(order -> {
            order.markFailed(reason);
            auditEvents.save(new AuditEventEntity(orderId, "ORDER_FAILED",
                    "Payment failed: " + reason));
        });
    }
}
