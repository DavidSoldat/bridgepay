package com.bridgepay.repayment.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class HttpApplicantClient implements ApplicantClient {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public HttpApplicantClient(RestClient.Builder restClientBuilder,
                                @Value("${bridgepay.applicant-service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .build();
        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("applicant-service");
    }

    @Override
    public ApplicantProfile fetchProfile(UUID applicantId) {
        Supplier<ApplicantProfile> call = () -> restClient.get()
                .uri("/internal/applicants/{id}", applicantId)
                .retrieve()
                .body(ApplicantProfile.class);

        try {
            return circuitBreaker.executeSupplier(call);
        } catch (Exception ex) {
            throw new ApplicantUnavailableException("Applicant Service call failed for " + applicantId, ex);
        }
    }

    @Override
    public void setPaddleCustomerId(UUID applicantId, String paddleCustomerId) {
        Supplier<Void> call = () -> {
            restClient.patch()
                    .uri("/internal/applicants/{id}/paddle-customer", applicantId)
                    .body(Map.of("paddleCustomerId", paddleCustomerId))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        };

        try {
            circuitBreaker.executeSupplier(call);
        } catch (Exception ex) {
            throw new ApplicantUnavailableException("Applicant Service call failed for " + applicantId, ex);
        }
    }
}
