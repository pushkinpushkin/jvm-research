package dev.pushkin.jvmresearch.enterprise.kafka;

import lombok.RequiredArgsConstructor;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderStatusChangedListener {
    private final EventProcessor processor;

    @KafkaListener(topics = "${sandbox.kafka.order-status-topic}")
    public void onOrderStatusChanged(BusinessEvent event) {
        processor.process(event, true);
    }
}
