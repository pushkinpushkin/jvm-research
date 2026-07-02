package dev.pushkin.jvmresearch.enterprise.config;

import java.time.Duration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration
public class RestClientConfig {

    @Bean
    RestClientCustomizer enterpriseSandboxRestClientCustomizer() {
        return builder -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(1));
            requestFactory.setReadTimeout(Duration.ofSeconds(2));
            builder.requestFactory(requestFactory);
        };
    }
}
