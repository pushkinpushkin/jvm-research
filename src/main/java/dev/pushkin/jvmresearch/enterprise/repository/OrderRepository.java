package dev.pushkin.jvmresearch.enterprise.repository;

import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderRepository extends MongoRepository<OrderDocument, String> {

    List<OrderDocument> findByStatusOrderByUpdatedAtAsc(OrderStatus status, Pageable pageable);

    long countByCreatedAtAfter(Instant createdAt);
}
