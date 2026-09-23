package dev.pushkin.jvmresearch.enterprise;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.external.ExternalFnsResponse;
import dev.pushkin.jvmresearch.enterprise.external.ExternalSignStatusResponse;
import dev.pushkin.jvmresearch.enterprise.kafka.BusinessEvent;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// RestClient response types are not inferred from MVC signatures.
// BusinessEvent is also selected by Kafka's JSON type header / configuration.
@RegisterReflectionForBinding({ExternalFnsResponse.class, ExternalSignStatusResponse.class, BusinessEvent.class})
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan(basePackageClasses = SandboxProperties.class)
public class EnterpriseSandboxApplication {

    public static void main(String[] args) {
        new SpringApplication(EnterpriseSandboxApplication.class).run(args);
    }
}
