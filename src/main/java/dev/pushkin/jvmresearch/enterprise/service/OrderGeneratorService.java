package dev.pushkin.jvmresearch.enterprise.service;

import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OrderGeneratorService {

    private final OrderRepository repository;
    private final PayloadFactory payloadFactory;

    public OrderGeneratorService(OrderRepository repository, PayloadFactory payloadFactory) {
        this.repository = repository;
        this.payloadFactory = payloadFactory;
    }

    public GenerateOrdersResponse generate(int count) {
        List<OrderDocument> batch = new ArrayList<>(Math.min(count, 1_000));
        int saved = 0;
        for (int i = 0; i < count; i++) {
            String orderId = "order-" + i;
            String clientType = i % 3 == 0 ? "LLC" : "INDIVIDUAL";
            batch.add(OrderDocument.newOrder(orderId, clientType, "client-" + i, payloadFactory.createPayload(orderId)));

            if (batch.size() == 1_000) {
                repository.saveAll(batch);
                saved += batch.size();
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            repository.saveAll(batch);
            saved += batch.size();
        }

        return new GenerateOrdersResponse(count, saved);
    }
}
