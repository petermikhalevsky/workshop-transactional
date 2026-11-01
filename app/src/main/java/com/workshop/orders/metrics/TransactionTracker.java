package com.workshop.orders.metrics;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Считает реально открытые транзакции БД. Вызывается из тела @Transactional-метода:
 * в этот момент транзакция уже началась, а соединение уже удерживается.
 */
@Component
public class TransactionTracker {

    private final OrderMetrics metrics;
    private final Set<Long> trackedThreads = ConcurrentHashMap.newKeySet();

    public TransactionTracker(OrderMetrics metrics) {
        this.metrics = metrics;
    }

    public void trackCurrentTransaction() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        long threadId = Thread.currentThread().getId();
        if (!trackedThreads.add(threadId)) {
            return;
        }
        metrics.transactionStarted();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                trackedThreads.remove(threadId);
                metrics.transactionFinished();
            }
        });
    }
}
