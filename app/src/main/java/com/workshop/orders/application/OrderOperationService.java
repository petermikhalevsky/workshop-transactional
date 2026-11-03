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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;


/**
 * Отдельный сервис для выполнения отдельных методов в транзакции
 */
@Service
public class OrderOperationService {

    private static final Logger log = LoggerFactory.getLogger(OrderOperationService.class);
    private final AuditEventRepository auditEvents;
    private final OrderRepository orders;
    private final PaymentAttemptRepository paymentAttempts;
    private final TransactionTracker transactionTracker;

    public OrderOperationService(AuditEventRepository auditEvents, OrderRepository orders, PaymentAttemptRepository paymentAttempts, TransactionTracker transactionTracker) {
        this.auditEvents = auditEvents;
        this.orders = orders;

        this.paymentAttempts = paymentAttempts;
        this.transactionTracker = transactionTracker;
    }


    /**
     * Создание нового заказа
     * @param orderId - идентификатор заказа
     * @param command - исходные данные заказа
     */
    @Transactional
    public void createOrder(String orderId, PlaceOrderCommand command) {
        transactionTracker.trackCurrentTransaction();
        log.info("Placing order {}: customerId={}, amount={}",
                orderId, command.customerId(), command.amount());

        orders.save(new OrderEntity(
                orderId, command.customerId(), command.amount(),
                command.idempotencyKey(), OrderStatus.PENDING));
        auditEvents.save(new AuditEventEntity(orderId, "ORDER_CREATED",
                "Order created and awaiting payment"));
    }



    /**
     * Установка статуса оплаты в PAID
     * Отметка в журнале аудита
     * @param idempotencyKey - уникальный клю заказа
     * @param authorization - результат оплаты заказа
     */
    @Transactional
    public void markPaidOrder(String idempotencyKey, PaymentAuthorization authorization) {
        transactionTracker.trackCurrentTransaction();
        OrderEntity order = findOrder(idempotencyKey);
        paymentAttempts.save(new PaymentAttemptEntity(order.getPublicId(), authorization.providerReference(),
                authorization.status(), authorization.latencyMs(), Instant.now()));
        order.markPaid();
        auditEvents.save(new AuditEventEntity(order.getPublicId(), "ORDER_PAID",
                "Payment approved: " + authorization.providerReference()));
    }

    /**
     * Установка статуса оплаты в FAILED
     * Отметка в журнале аудита
     * @param idempotencyKey - уникальный клю заказа
     * @param reason - причина ошибки
     */

    @Transactional
    public void markFailedOrder(String idempotencyKey, String reason) {
        transactionTracker.trackCurrentTransaction();
        OrderEntity order = findOrder(idempotencyKey);
        order.markFailed(reason);
        auditEvents.save(new AuditEventEntity(order.getPublicId(), "ORDER_FAILED",
                "Payment failed: " + reason));
    }


    private OrderEntity findOrder(String idempotencyKey) {
        Optional<OrderEntity> opOrder = orders.findByIdempotencyKey(idempotencyKey);
        if (opOrder.isEmpty()) {
            throw new NoSuchElementException("Заказ не найден");
        }
        return opOrder.get();
    }
}
