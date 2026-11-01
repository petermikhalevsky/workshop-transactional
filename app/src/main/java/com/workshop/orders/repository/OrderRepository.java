package com.workshop.orders.repository;

import com.workshop.orders.domain.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    Optional<OrderEntity> findByPublicId(String publicId);

    Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey);
}
