package com.bridgepay.creditrisk.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Wraps the call to the Mock Credit Bureau's bureau-profile lookup in a
 * Resilience4j core (framework-agnostic) circuit breaker - no
 * Boot 4-compatible annotation integration module exists yet. See
 * HttpCreditRiskClient in application-service for the pattern this mirrors.
 */
@Component
public class HttpBureauClient implements BureauClient {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public HttpBureauClient(RestClient.Builder restClientBuilder,
                             @Value("${bridgepay.mock-credit-bureau.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .build();
        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("mock-credit-bureau");
    }

    @Override
    public BureauProfile fetchProfile(UUID applicantId) {
        Supplier<BureauProfile> call = () -> restClient.get()
                .uri("/internal/bureau-profile/{applicantId}", applicantId)
                .retrieve()
                .body(BureauProfile.class);

        try {
            return circuitBreaker.executeSupplier(call);
        } catch (Exception ex) {
            throw new BureauUnavailableException("Mock Credit Bureau call failed for applicant " + applicantId, ex);
        }
    }
}
