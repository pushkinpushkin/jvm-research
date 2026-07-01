package dev.pushkin.jvmresearch.enterprise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sandbox")
public record SandboxProperties(
        Flow flow,
        Chaos chaos,
        Scheduler scheduler,
        Kafka kafka,
        External external
) {

    public record Flow(
            int historyItems,
            int payloadEntries,
            int payloadValueSize
    ) {
    }

    public record Chaos(
            boolean enabled,
            int externalApiTimeoutPercent,
            int externalApiErrorPercent,
            int externalApiMalformedPercent,
            int externalApiSlowPercent,
            int mongoConflictPercent,
            int kafkaDuplicatePercent
    ) {
    }

    public record Scheduler(
            boolean retryEnabled,
            long retryFixedDelayMs,
            int retryBatchSize,
            boolean statusPollingEnabled,
            long statusPollingFixedDelayMs,
            int statusPollingBatchSize
    ) {
    }

    public record Kafka(
            String orderStatusTopic,
            String businessEventTopic
    ) {
    }

    public record External(
            String baseUrl
    ) {
    }
}
