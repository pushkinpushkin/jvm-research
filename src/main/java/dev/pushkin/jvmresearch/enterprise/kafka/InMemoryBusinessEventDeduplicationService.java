package dev.pushkin.jvmresearch.enterprise.kafka;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InMemoryBusinessEventDeduplicationService {

    private final Map<String, Instant> processedEventIds = new ConcurrentHashMap<>();

    public boolean markProcessed(BusinessEvent event) {
        if (event == null) {
            return false;
        }
        return markProcessed(event.eventId());
    }

    public boolean markProcessed(String eventId) {
        if (!StringUtils.hasText(eventId)) {
            return false;
        }
        return processedEventIds.putIfAbsent(eventId, Instant.now()) == null;
    }

    public boolean isProcessed(String eventId) {
        return StringUtils.hasText(eventId) && processedEventIds.containsKey(eventId);
    }

    public int size() {
        return processedEventIds.size();
    }
}
