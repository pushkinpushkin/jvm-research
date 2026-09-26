package dev.pushkin.jvmresearch.enterprise.kafka;

import lombok.RequiredArgsConstructor;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BusinessEventListener {
    private final EventProcessor processor;

    @KafkaListener(topics = "${sandbox.kafka.business-event-topic}")
    public void onBusinessEvent(BusinessEvent event) {
        processor.process(event, false);
    }
}
