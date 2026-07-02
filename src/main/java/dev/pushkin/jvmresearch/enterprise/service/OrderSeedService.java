package dev.pushkin.jvmresearch.enterprise.service;

import dev.pushkin.jvmresearch.enterprise.domain.ClientType;
import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSeedService {

    private final OrderRepository repository;
    private final PayloadBuilder payloadBuilder;

    public GenerateOrdersResponse generate(int count) {
        log.info("Synthetic order generation started count={}", count);
        List<OrderDocument> batch = new ArrayList<>(Math.min(count, 1_000));
        int saved = 0;
        for (int i = 0; i < count; i++) {
            String orderId = "order-" + i;
            ClientType clientType = i % 3 == 0 ? ClientType.LLC : ClientType.INDIVIDUAL;
            batch.add(OrderDocument.newOrder(orderId, clientType, "client-" + i, payloadBuilder.createPayload(orderId)));

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

        log.info("Synthetic order generation finished requested={} saved={}", count, saved);
        return new GenerateOrdersResponse(count, saved);
    }
}
