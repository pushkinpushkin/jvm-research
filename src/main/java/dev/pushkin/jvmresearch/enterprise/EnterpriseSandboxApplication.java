package dev.pushkin.jvmresearch.enterprise;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan(basePackageClasses = SandboxProperties.class)
public class EnterpriseSandboxApplication {

    public static void main(String[] args) {
        new SpringApplication(EnterpriseSandboxApplication.class).run(args);
    }
}
