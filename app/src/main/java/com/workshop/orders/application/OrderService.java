package com.workshop.orders.application;

import com.workshop.orders.domain.OrderEntity;
import com.workshop.orders.domain.OrderStatus;
import com.workshop.orders.external.PaymentAuthorization;
import com.workshop.orders.external.PaymentDeclinedException;
import com.workshop.orders.external.PaymentGateway;
import com.workshop.orders.metrics.OrderMetrics;
import com.workshop.orders.repository.OrderRepository;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final PaymentGateway paymentGateway;
    private final OrderMetrics metrics;
    private final OrderOperationService orderOperationService;

    public OrderService(OrderRepository orders,
                        PaymentGateway paymentGateway,
                        OrderMetrics metrics,
                        OrderOperationService orderOperationService) {
        this.orders = orders;
        this.paymentGateway = paymentGateway;
        this.metrics = metrics;

        this.orderOperationService = orderOperationService;
    }

    /**
     * Оформление заказа. Транзакция не открывается здесь
     */
    public OrderResult placeOrder(PlaceOrderCommand command) {
        metrics.orderStarted();
        Timer.Sample total = metrics.startOrderTimer();
        long startedAt = System.nanoTime();
        String result = "failed";

        try {
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
            // транзакция создания заказа
            orderOperationService.createOrder(orderId, command);

            Timer.Sample paymentSample = metrics.startPaymentTimer();
            long paymentStartedAt = System.nanoTime();
            PaymentAuthorization authorization;
            try {
                authorization = paymentGateway.authorize(orderId, command.amount());
            } catch (RuntimeException e) {
                orderOperationService.markFailedOrder(command.idempotencyKey(), e.getMessage());
                log.warn("Could not authorize the order: {}", e.getMessage());
                throw e;
            } finally {
                long paymentLatencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - paymentStartedAt);
                metrics.stopPaymentTimer(paymentSample, "outside_transaction", paymentLatencyMs);
            }
            // транзакция обновления статуса заказа
            orderOperationService.markPaidOrder(command.idempotencyKey(), authorization);

            result = "success";
            log.info("Order {} paid in {}ms", orderId, elapsedMs(startedAt));
            return new OrderResult(orderId, OrderStatus.PAID, command.amount(),
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
