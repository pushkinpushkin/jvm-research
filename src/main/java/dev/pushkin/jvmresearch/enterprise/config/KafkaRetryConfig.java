package dev.pushkin.jvmresearch.enterprise.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

@Configuration
public class KafkaRetryConfig {
    @Bean
    DefaultErrorHandler researchKafkaErrorHandler() {
        var handler =
                new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        // No log-and-drop recovery: poison records keep lag nonzero and invalidate the run.
        handler.setClassifications(Map.of(Exception.class, true), true);
        return handler;
    }
}
