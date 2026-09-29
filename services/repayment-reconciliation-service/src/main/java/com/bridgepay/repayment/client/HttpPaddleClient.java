package com.bridgepay.repayment.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Talks to Paddle's real Billing API (sandbox or live, per paddle.base-url).
 * Verified against Paddle's own developer docs, not guessed: subscriptions
 * are only ever created as a side effect of a transaction completing via
 * checkout - there is no direct "create subscription" call - and every
 * response wraps its payload under a top-level "data" key.
 */
@Component
public class HttpPaddleClient implements PaddleClient {

    private static final String CURRENCY_CODE = "USD";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public HttpPaddleClient(RestClient.Builder restClientBuilder,
                             @Value("${paddle.base-url}") String baseUrl,
                             @Value("${paddle.api-key}") String apiKey) {
        // Forces HTTP/1.1: the JDK HttpClient's default h2c upgrade negotiation
        // does not play well with every server implementation (observed against
        // WireMock's Jetty engine in tests) - HTTP/1.1 is all Paddle's API needs.
        HttpClient jdkHttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(jdkHttpClient))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                // a 4xx is Paddle up and refusing (e.g. cancelling an already-paid transaction), not Paddle down
                .ignoreExceptions(HttpClientErrorException.class)
                .build();
        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("paddle");
    }

    @Override
    public String findOrCreateCustomer(String email, String name) {
        return execute(() -> {
            CustomerListEnvelope existing = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/customers").queryParam("email", email).build())
                    .retrieve()
                    .body(CustomerListEnvelope.class);
            if (existing != null && existing.data() != null && !existing.data().isEmpty()) {
                return existing.data().get(0).id();
            }

            CustomerEnvelope created = restClient.post()
                    .uri("/customers")
                    .body(new CreateCustomerRequest(email, name))
                    .retrieve()
                    .body(CustomerEnvelope.class);
            return created.data().id();
        }, "find-or-create customer for " + email);
    }

    @Override
    public PaddleTransactionResult createInstallmentTransaction(String customerId, BigDecimal installmentAmount) {
        return execute(() -> {
            CreateTransactionRequest request = new CreateTransactionRequest(customerId, "automatic", List.of(
                    new TransactionItem(1, new TransactionPrice(
                            "BridgePay installment plan",
                            new BillingCycle("week", 1),
                            "account_setting",
                            new UnitPrice(toMinorUnits(installmentAmount), CURRENCY_CODE),
                            new InlineProduct("BridgePay installment plan", "standard")))));

            TransactionEnvelope response = restClient.post()
                    .uri("/transactions")
                    .body(request)
                    .retrieve()
                    .body(TransactionEnvelope.class);
            TransactionData data = response.data();
            return new PaddleTransactionResult(data.id(), data.checkout() != null ? data.checkout().url() : null);
        }, "create installment transaction for customer " + customerId);
    }

    @Override
    public void cancelSubscription(String subscriptionId) {
        execute(() -> {
            restClient.post()
                    .uri("/subscriptions/{id}/cancel", subscriptionId)
                    .body(new CancelSubscriptionRequest("immediately"))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        }, "cancel subscription " + subscriptionId);
    }

    @Override
    public void cancelTransaction(String transactionId) {
        execute(() -> {
            restClient.patch()
                    .uri("/transactions/{id}", transactionId)
                    .body(new UpdateTransactionStatusRequest("canceled"))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        }, "cancel transaction " + transactionId);
    }

    @Override
    public Optional<PaddleWebhookData> findCompletedTransaction(String transactionId) {
        return execute(() -> {
            TransactionData data = restClient.get()
                    .uri("/transactions/{id}", transactionId)
                    .retrieve()
                    .body(TransactionEnvelope.class)
                    .data();
            return "completed".equals(data.status())
                    ? Optional.of(new PaddleWebhookData(data.id(), data.subscriptionId()))
                    : Optional.<PaddleWebhookData>empty();
        }, "get transaction " + transactionId);
    }

    private <T> T execute(Supplier<T> call, String description) {
        try {
            return circuitBreaker.executeSupplier(call);
        } catch (Exception ex) {
            throw new PaddleUnavailableException("Paddle call failed: " + description, ex);
        }
    }

    private static String toMinorUnits(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).toBigInteger().toString();
    }

    private record CreateCustomerRequest(String email, String name) {
    }

    private record CustomerData(String id, String email) {
    }

    private record CustomerEnvelope(CustomerData data) {
    }

    private record CustomerListEnvelope(List<CustomerData> data) {
    }

    private record CreateTransactionRequest(@JsonProperty("customer_id") String customerId,
                                             @JsonProperty("collection_mode") String collectionMode,
                                             List<TransactionItem> items) {
    }

    private record TransactionItem(int quantity, TransactionPrice price) {
    }

    private record TransactionPrice(String description,
                                     @JsonProperty("billing_cycle") BillingCycle billingCycle,
                                     @JsonProperty("tax_mode") String taxMode,
                                     @JsonProperty("unit_price") UnitPrice unitPrice,
                                     InlineProduct product) {
    }

    // Paddle rejects a non-catalog price unless it names a catalog product_id or carries an inline
    // product; inline keeps the sandbox free of manual catalog setup.
    private record InlineProduct(String name, @JsonProperty("tax_category") String taxCategory) {
    }

    private record BillingCycle(String interval, int frequency) {
    }

    private record UnitPrice(String amount, @JsonProperty("currency_code") String currencyCode) {
    }

    private record TransactionData(String id, String status,
                                    @JsonProperty("subscription_id") String subscriptionId,
                                    Checkout checkout) {
    }

    private record Checkout(String url) {
    }

    private record TransactionEnvelope(TransactionData data) {
    }

    private record CancelSubscriptionRequest(@JsonProperty("effective_from") String effectiveFrom) {
    }

    private record UpdateTransactionStatusRequest(String status) {
    }
}
