package com.workshop.orders.application;

import com.workshop.orders.domain.OrderEntity;
import com.workshop.orders.domain.OrderStatus;
import com.workshop.orders.external.PaymentAuthorization;
import com.workshop.orders.external.PaymentDeclinedException;
import com.workshop.orders.external.PaymentGateway;
import com.workshop.orders.metrics.OrderMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionBoundaryTest {

    @Mock
    private OrderPersistenceService persistence;

    @Mock
    private PaymentGateway paymentGateway;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                persistence, paymentGateway, new OrderMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void paymentGatewayIsCalledWithoutAnOpenTransaction() {
        when(persistence.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());

        OrderEntity paid = new OrderEntity(
                "order-1", "customer-1", BigDecimal.TEN, "key-1", OrderStatus.PENDING);
        paid.markPaid();
        when(persistence.markPaid(anyString(), any(PaymentAuthorization.class))).thenReturn(paid);

        AtomicBoolean transactionWasOpen = new AtomicBoolean(true);
        when(paymentGateway.authorize(anyString(), eq(BigDecimal.TEN))).thenAnswer(invocation -> {
            transactionWasOpen.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new PaymentAuthorization("provider-1", "AUTHORIZED", 800);
        });

        OrderResult result = orderService.placeOrder(
                new PlaceOrderCommand("customer-1", BigDecimal.TEN, "key-1"));

        assertThat(transactionWasOpen.get()).isFalse();
        assertThat(result.status()).isEqualTo(OrderStatus.PAID);
        assertThat(result.providerReference()).isEqualTo("provider-1");
        assertThat(result.duplicate()).isFalse();

        verify(persistence).createPending(anyString(), any(PlaceOrderCommand.class));
        verify(persistence).markPaid(anyString(), any(PaymentAuthorization.class));
    }

    @Test
    void failedPaymentIsRecordedAndRethrown() {
        when(persistence.findByIdempotencyKey("key-2")).thenReturn(Optional.empty());
        when(paymentGateway.authorize(anyString(), any(BigDecimal.class)))
                .thenThrow(new PaymentDeclinedException("card declined"));

        assertThatThrownBy(() -> orderService.placeOrder(
                new PlaceOrderCommand("customer-2", BigDecimal.ONE, "key-2")))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining("card declined");

        verify(persistence).markFailed(anyString(), contains("card declined"));
    }

    @Test
    void orchestratorIsNotTransactional() throws NoSuchMethodException {
        assertThat(OrderService.class
                .getMethod("placeOrder", PlaceOrderCommand.class)
                .isAnnotationPresent(Transactional.class))
                .isFalse();
    }
}
