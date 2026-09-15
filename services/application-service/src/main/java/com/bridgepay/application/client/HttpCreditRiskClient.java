package com.bridgepay.application.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Wraps the synchronous call to the Credit Risk Engine's /internal/score
 * endpoint in a circuit breaker from Resilience4j's core (framework-agnostic)
 * module - see the pom.xml comment for why the Spring Boot integration module
 * isn't used here. On any failure (network, 5xx, timeout, or an open circuit),
 * this defaults to MANUAL_REVIEW rather than guessing in either direction.
 */
@Component
public class HttpCreditRiskClient implements CreditRiskClient {

    private static final Logger log = LoggerFactory.getLogger(HttpCreditRiskClient.class);

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public HttpCreditRiskClient(RestClient.Builder restClientBuilder,
                                 @Value("${bridgepay.credit-risk-engine.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .build();
        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("credit-risk-engine");
    }

    @Override
    public ScoreResult score(ScoreRequest request) {
        Supplier<ScoreResult> call = () -> restClient.post()
                .uri("/internal/score")
                .body(request)
                .retrieve()
                .body(ScoreResult.class);

        try {
            return circuitBreaker.executeSupplier(call);
        } catch (Exception ex) {
            log.warn("Credit Risk Engine call failed, defaulting to MANUAL_REVIEW: {}", ex.getMessage());
            return ScoreResult.unavailableFallback();
        }
    }
}
