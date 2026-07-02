package dev.pushkin.jvmresearch.enterprise.kafka;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic orderStatusTopic(SandboxProperties properties) {
        return TopicBuilder.name(properties.kafka().orderStatusTopic())
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic businessEventTopic(SandboxProperties properties) {
        return TopicBuilder.name(properties.kafka().businessEventTopic())
                .partitions(6)
                .replicas(1)
                .build();
    }
}
