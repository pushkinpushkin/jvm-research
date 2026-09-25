package dev.pushkin.jvmresearch.enterprise.repository;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;

import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
public class OrderBounds implements BeforeConvertCallback<OrderDocument> {
    public static final int EVENT_IDS = 1024;
    public static final int OUTBOX = 256;
    private final SandboxProperties properties;

    public OrderBounds(SandboxProperties properties) {
        this.properties = properties;
    }

    @Override
    public OrderDocument onBeforeConvert(OrderDocument order, String collection) {
        int max = properties.flow().historyItems();
        if (max < 1) throw new IllegalArgumentException("history-items must be positive");
        if (order.getHistory().size() > max)
            order.setHistory(
                    new ArrayList<>(
                            order.getHistory()
                                    .subList(
                                            order.getHistory().size() - max,
                                            order.getHistory().size())));
        if (order.getProcessedBusinessEvents().size() > EVENT_IDS)
            order.setProcessedBusinessEvents(
                    new ArrayList<>(
                            order.getProcessedBusinessEvents()
                                    .subList(
                                            order.getProcessedBusinessEvents().size() - EVENT_IDS,
                                            order.getProcessedBusinessEvents().size())));
        if (order.getPendingEvents().size() > OUTBOX)
            throw new IllegalStateException("Outbox capacity exceeded");
        return order;
    }
}
