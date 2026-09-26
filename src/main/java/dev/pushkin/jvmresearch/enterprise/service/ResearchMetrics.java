package dev.pushkin.jvmresearch.enterprise.service;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Only fixed application-owned metric names are accepted by callers; no per-order tags. */
@Component
public class ResearchMetrics {
    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    public ResearchMetrics(MeterRegistry registry) {
        for (String name :
                new String[] {
                    "http_processed",
                    "http_expected_fault",
                    "http_unexpected",
                    "scheduler_runs",
                    "scheduler_expected_fault",
                    "scheduler_errors",
                    "outbox_errors",
                    "events_enqueued",
                    "events_published",
                    "events_consumed",
                    "events_duplicate",
                    "consumer_errors",
                    "outbox_deliveries",
                    "synthetic_duplicates",
                    "mongo_expected_fault",
                    "external_ok",
                    "external_slow",
                    "external_long_delay",
                    "external_error",
                    "external_bad_response"
                }) {
            AtomicLong count = new AtomicLong();
            counts.put(name, count);
            registry.gauge("research." + name, count);
        }
    }

    public void increment(String name) {
        counts.get(name).incrementAndGet();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new java.util.TreeMap<>();
        counts.forEach((key, value) -> result.put(key, value.get()));
        return result;
    }
}
