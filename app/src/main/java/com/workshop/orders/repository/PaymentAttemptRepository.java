package com.workshop.orders.repository;

import com.workshop.orders.domain.PaymentAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, Long> {

    List<PaymentAttemptEntity> findByOrderPublicId(String orderPublicId);
}
