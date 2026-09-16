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

@Component
public class HttpRepaymentHistoryClient implements RepaymentHistoryClient {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public HttpRepaymentHistoryClient(RestClient.Builder restClientBuilder,
                                       @Value("${bridgepay.repayment-reconciliation.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .build();
        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("repayment-reconciliation");
    }

    @Override
    public RepaymentHistory fetchHistory(UUID applicantId) {
        Supplier<RepaymentHistory> call = () -> restClient.get()
                .uri("/internal/repayment-history/{applicantId}", applicantId)
                .retrieve()
                .body(RepaymentHistory.class);

        try {
            return circuitBreaker.executeSupplier(call);
        } catch (Exception ex) {
            throw new RepaymentHistoryUnavailableException(
                    "Repayment Reconciliation call failed for applicant " + applicantId, ex);
        }
    }
}
