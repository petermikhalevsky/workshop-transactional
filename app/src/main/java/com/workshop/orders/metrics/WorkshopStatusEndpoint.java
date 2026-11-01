package com.workshop.orders.metrics;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Endpoint(id = "workshop")
public class WorkshopStatusEndpoint {

    private final HikariDataSource dataSource;
    private final OrderMetrics metrics;
    private final String transactionScope;

    public WorkshopStatusEndpoint(HikariDataSource dataSource,
                                  OrderMetrics metrics,
                                  @Value("${app.workshop.transaction-scope:wide}") String transactionScope) {
        this.dataSource = dataSource;
        this.metrics = metrics;
        this.transactionScope = transactionScope;
    }

    @ReadOperation
    public Map<String, Object> status() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();

        Map<String, Object> poolState = new LinkedHashMap<>();
        poolState.put("active", pool == null ? -1 : pool.getActiveConnections());
        poolState.put("idle", pool == null ? -1 : pool.getIdleConnections());
        poolState.put("pending", pool == null ? -1 : pool.getThreadsAwaitingConnection());
        poolState.put("total", pool == null ? -1 : pool.getTotalConnections());
        poolState.put("max", dataSource.getMaximumPoolSize());

        Map<String, Object> orders = new LinkedHashMap<>();
        orders.put("success", metrics.successCount());
        orders.put("failed", metrics.failedCount());
        orders.put("duplicate", metrics.duplicateCount());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactionScope", transactionScope);
        result.put("poolSaturated", metrics.poolSaturated());
        result.put("inFlightOrders", metrics.inFlightOrders());
        result.put("activeTransactions", metrics.activeTransactions());
        result.put("lastPaymentLatencyMs", metrics.lastPaymentLatencyMs());
        result.put("orders", orders);
        result.put("pool", poolState);
        return result;
    }
}
