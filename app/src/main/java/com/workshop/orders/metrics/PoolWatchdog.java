package com.workshop.orders.metrics;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class PoolWatchdog {

    private static final Logger log = LoggerFactory.getLogger(PoolWatchdog.class);

    private final HikariDataSource dataSource;
    private final OrderMetrics metrics;
    private final AtomicBoolean wasSaturated = new AtomicBoolean(false);

    public PoolWatchdog(HikariDataSource dataSource, OrderMetrics metrics) {
        this.dataSource = dataSource;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.workshop.watchdog-interval-ms:2000}")
    public void samplePool() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        if (pool == null) {
            return;
        }

        int active = pool.getActiveConnections();
        int idle = pool.getIdleConnections();
        int pending = pool.getThreadsAwaitingConnection();
        int max = dataSource.getMaximumPoolSize();

        boolean saturated = active >= max && pending > 0;
        metrics.setPoolSaturated(saturated);

        if (saturated && wasSaturated.compareAndSet(false, true)) {
            log.warn("=== CONNECTION POOL EXHAUSTED: active={}, idle={}, pending={}, max={} ===",
                    active, idle, pending, max);
            log.warn("=== New requests are waiting for a connection. Check what keeps transactions open. ===");
        } else if (!saturated && wasSaturated.compareAndSet(true, false)) {
            log.info("=== CONNECTION POOL RECOVERED: active={}, idle={}, pending={}, max={} ===",
                    active, idle, pending, max);
        }
    }
}
