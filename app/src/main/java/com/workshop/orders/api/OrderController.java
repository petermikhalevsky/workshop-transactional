package com.workshop.orders.api;

import com.workshop.orders.application.OrderResult;
import com.workshop.orders.application.OrderService;
import com.workshop.orders.application.PlaceOrderCommand;
import com.workshop.orders.domain.OrderEntity;
import com.workshop.orders.dto.OrderResponse;
import com.workshop.orders.dto.PlaceOrderRequest;
import com.workshop.orders.repository.OrderRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orders;
    private final JdbcTemplate jdbcTemplate;
    private final String transactionScope;

    public OrderController(OrderService orderService,
                           OrderRepository orders,
                           JdbcTemplate jdbcTemplate,
                           @Value("${app.workshop.transaction-scope:wide}") String transactionScope) {
        this.orderService = orderService;
        this.orders = orders;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionScope = transactionScope;
    }

    @PostMapping("/orders")
    public OrderResponse placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        OrderResult result = orderService.placeOrder(new PlaceOrderCommand(
                request.customerId(), request.amount(), request.idempotencyKey()));
        return new OrderResponse(result.orderId(), result.status(), result.amount(),
                result.totalMs(), result.providerReference(), result.duplicate());
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse getOrder(@PathVariable String orderId) {
        OrderEntity order = orders.findByPublicId(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));
        return new OrderResponse(order.getPublicId(), order.getStatus(), order.getAmount(),
                0, null, false);
    }

    /**
     * Дешёвый liveness-эндпоинт: не ходит в БД, поэтому отвечает даже при исчерпанном пуле.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "order-service");
        body.put("transactionScope", transactionScope);
        return body;
    }

    /**
     * Сброс данных перед следующим прогоном. Только для стенда.
     */
    @PostMapping("/orders/reset")
    public ResponseEntity<Map<String, String>> reset() {
        jdbcTemplate.execute("TRUNCATE TABLE order_audit, payment_attempts, orders RESTART IDENTITY");
        return ResponseEntity.ok(Map.of("status", "reset"));
    }
}
