package dev.pushkin.jvmresearch.enterprise.repository;

import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.domain.OrderStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface OrderRepository extends MongoRepository<OrderDocument, String> {

    List<OrderDocument> findByStatusOrderByUpdatedAtAsc(OrderStatus status, Pageable pageable);

    @org.springframework.data.mongodb.repository.Query("{'pendingEvents.0': {$exists: true}}")
    List<OrderDocument> findPendingEvents(Pageable pageable);

    @org.springframework.data.mongodb.repository.Query(
            "{'status': 'FAILED', 'fnsProcess.attempts': {$lt: 3}}")
    List<OrderDocument> findRetryable(Pageable pageable);

    long countByStatus(OrderStatus status);

    long countByCreatedAtAfter(Instant createdAt);
}
