package dev.pushkin.jvmresearch.enterprise.external;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.service.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

class ExternalFaultTest {
    private record Fixture(ExternalApiClient client, MockRestServiceServer server) {}

    private Fixture fixture(int delay, int error, int invalid) {
        var config =
                new SandboxProperties(
                        null,
                        new SandboxProperties.Profile(true, delay, error, invalid, 0, 0, 0),
                        null,
                        null,
                        new SandboxProperties.External("http://external"));
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(
                new ExternalApiClient(
                        builder,
                        config,
                        new TrafficProfile(config),
                        new ResearchMetrics(new SimpleMeterRegistry())),
                server);
    }

    @Test
    void injected503IsExpectedBut404IsUnexpected() {
        var f = fixture(0, 100, 0);
        f.server
                .expect(requestTo("http://external/external/fns-data/id?mode=error"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(ExpectedExternalFault.class, () -> f.client.getFnsData("id"));
        f.server.verify();
        var missing = fixture(0, 100, 0);
        missing.server.expect(anything()).andRespond(withStatus(HttpStatus.NOT_FOUND));
        var exception = assertThrows(RuntimeException.class, () -> missing.client.getFnsData("id"));
        assertFalse(exception instanceof ExpectedExternalFault);
    }

    @Test
    void malformedJsonIsExpectedOnlyInBadResponseMode() {
        var f = fixture(0, 0, 100);
        f.server
                .expect(anything())
                .andRespond(withSuccess("{invalid-json", MediaType.APPLICATION_JSON));
        assertThrows(ExpectedExternalFault.class, () -> f.client.getFnsData("id"));
        f.server.verify();
        var normal = fixture(0, 0, 0);
        normal.server
                .expect(anything())
                .andRespond(withSuccess("{invalid-json", MediaType.APPLICATION_JSON));
        var exception = assertThrows(RuntimeException.class, () -> normal.client.getFnsData("id"));
        assertFalse(exception instanceof ExpectedExternalFault);
    }

    @Test
    void injectedReadTimeoutIsExpected() {
        var f = fixture(100, 0, 0);
        f.server
                .expect(anything())
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        assertThrows(ExpectedExternalFault.class, () -> f.client.getSignStatus("id"));
        f.server.verify();
    }
}
