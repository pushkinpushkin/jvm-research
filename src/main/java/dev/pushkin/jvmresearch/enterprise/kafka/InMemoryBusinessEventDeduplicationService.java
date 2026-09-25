package dev.pushkin.jvmresearch.enterprise.kafka;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Best-effort cache; Mongo event receipts are authoritative inside their retention window. */
@Component
public class InMemoryBusinessEventDeduplicationService {
    private final Map<String, Long> processedEventIds = new LinkedHashMap<>();
    private final int capacity;
    private final long ttlMillis;
    private final Clock clock;

    public InMemoryBusinessEventDeduplicationService() {
        this(10_000, Duration.ofHours(1), Clock.systemUTC());
    }

    InMemoryBusinessEventDeduplicationService(int capacity, Duration ttl, Clock clock) {
        if (capacity < 1 || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.ttlMillis = ttl.toMillis();
        this.clock = clock;
    }

    public boolean markProcessed(BusinessEvent event) {
        return event != null && markProcessed(event.eventId());
    }

    public synchronized boolean markProcessed(String id) {
        purge();
        if (!StringUtils.hasText(id) || processedEventIds.containsKey(id)) return false;
        while (processedEventIds.size() >= capacity)
            processedEventIds.remove(processedEventIds.keySet().iterator().next());
        processedEventIds.put(id, clock.millis());
        return true;
    }

    public synchronized boolean isProcessed(String id) {
        purge();
        return processedEventIds.containsKey(id);
    }

    public synchronized int size() {
        purge();
        return processedEventIds.size();
    }

    private void purge() {
        long cutoff = clock.millis() - ttlMillis;
        var iterator = processedEventIds.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() > cutoff) break;
            iterator.remove();
        }
    }
}
