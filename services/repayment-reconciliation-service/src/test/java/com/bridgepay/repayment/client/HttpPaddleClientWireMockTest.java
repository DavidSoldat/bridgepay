package com.bridgepay.repayment.client;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Paddle can't run in a container, so this stubs its real HTTP API shape
 * (confirmed against Paddle's own docs) with WireMock instead of hitting the
 * real sandbox - covers request/response wire format, not just compilation.
 */
@WireMockTest
class HttpPaddleClientWireMockTest {

    private HttpPaddleClient client(WireMockRuntimeInfo wm) {
        return new HttpPaddleClient(RestClient.builder(), wm.getHttpBaseUrl(), "test-api-key");
    }

    @Test
    void findOrCreateCustomer_reusesAnExistingCustomer_withoutCreatingANewOne(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/customers"))
                .withQueryParam("email", equalTo("ana@example.com"))
                .willReturn(okJson("""
                        { "data": [ { "id": "ctm_existing", "email": "ana@example.com" } ] }
                        """)));

        String customerId = client(wm).findOrCreateCustomer("ana@example.com", "Ana Doe");

        assertThat(customerId).isEqualTo("ctm_existing");
        verify(0, postRequestedFor(urlPathEqualTo("/customers")));
    }

    @Test
    void findOrCreateCustomer_createsANewCustomer_whenNoneExists(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/customers"))
                .withQueryParam("email", equalTo("new@example.com"))
                .willReturn(okJson("{ \"data\": [] }")));
        stubFor(post(urlPathEqualTo("/customers"))
                .withRequestBody(matchingJsonPath("$.email", equalTo("new@example.com")))
                .willReturn(okJson("""
                        { "data": { "id": "ctm_new", "email": "new@example.com" } }
                        """)));

        String customerId = client(wm).findOrCreateCustomer("new@example.com", "New Person");

        assertThat(customerId).isEqualTo("ctm_new");
    }

    @Test
    void createInstallmentTransaction_sendsAWeeklyNonCatalogPrice_andParsesTheCheckoutUrl(WireMockRuntimeInfo wm) {
        stubFor(post(urlPathEqualTo("/transactions"))
                .withRequestBody(matchingJsonPath("$.customer_id", equalTo("ctm_1")))
                .withRequestBody(matchingJsonPath("$.collection_mode", equalTo("automatic")))
                .withRequestBody(matchingJsonPath("$.items[0].price.billing_cycle.interval", equalTo("week")))
                .withRequestBody(matchingJsonPath("$.items[0].price.unit_price.amount", equalTo("5000")))
                // A non-catalog price must carry a product_id or an inline product (real sandbox 400 otherwise).
                .withRequestBody(matchingJsonPath("$.items[0].price.product.name", equalTo("BridgePay installment plan")))
                .withRequestBody(matchingJsonPath("$.items[0].price.product.tax_category", equalTo("standard")))
                .willReturn(okJson("""
                        { "data": { "id": "txn_1", "subscription_id": null,
                          "checkout": { "url": "https://sandbox.paddle.com/checkout/txn_1" } } }
                        """)));

        PaddleTransactionResult result = client(wm).createInstallmentTransaction("ctm_1", new BigDecimal("50.00"));

        assertThat(result.transactionId()).isEqualTo("txn_1");
        assertThat(result.checkoutUrl()).isEqualTo("https://sandbox.paddle.com/checkout/txn_1");
    }

    @Test
    void cancelSubscription_postsAnImmediateCancellation(WireMockRuntimeInfo wm) {
        stubFor(post(urlPathEqualTo("/subscriptions/sub_1/cancel"))
                .withRequestBody(matchingJsonPath("$.effective_from", equalTo("immediately")))
                .willReturn(okJson("{ \"data\": { \"id\": \"sub_1\", \"status\": \"canceled\" } }")));

        client(wm).cancelSubscription("sub_1");

        verify(1, postRequestedFor(urlPathEqualTo("/subscriptions/sub_1/cancel")));
    }

    @Test
    void cancelTransaction_patchesTheStatusToCanceled(WireMockRuntimeInfo wm) {
        stubFor(patch(urlPathEqualTo("/transactions/txn_1"))
                .withRequestBody(matchingJsonPath("$.status", equalTo("canceled")))
                .willReturn(okJson("{ \"data\": { \"id\": \"txn_1\", \"status\": \"canceled\" } }")));

        client(wm).cancelTransaction("txn_1");

        verify(1, patchRequestedFor(urlPathEqualTo("/transactions/txn_1")));
    }

    @Test
    void cancelTransaction_surfacesPaddlesRefusal(WireMockRuntimeInfo wm) {
        stubFor(patch(urlPathEqualTo("/transactions/txn_done"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{ \"error\": { \"code\": \"transaction_immutable\" } }")));

        assertThatThrownBy(() -> client(wm).cancelTransaction("txn_done"))
                .isInstanceOf(PaddleUnavailableException.class);
    }

    @Test
    void paddleRefusals_doNotTripTheCircuitBreaker_forOtherCalls(WireMockRuntimeInfo wm) {
        stubFor(patch(urlPathEqualTo("/transactions/txn_done"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{ \"error\": { \"code\": \"transaction_immutable\" } }")));
        stubFor(post(urlPathEqualTo("/subscriptions/sub_1/cancel"))
                .willReturn(okJson("{ \"data\": { \"id\": \"sub_1\", \"status\": \"canceled\" } }")));
        HttpPaddleClient paddle = client(wm);

        // e.g. the expiry job retrying orders that were paid while webhooks weren't arriving
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> paddle.cancelTransaction("txn_done")).isInstanceOf(PaddleUnavailableException.class);
        }

        paddle.cancelSubscription("sub_1");
        verify(1, postRequestedFor(urlPathEqualTo("/subscriptions/sub_1/cancel")));
    }

    @Test
    void wrapsAnyFailure_inPaddleUnavailableException(WireMockRuntimeInfo wm) {
        // no stub registered for this path -> WireMock's default 404, which the
        // circuit breaker's executeSupplier surfaces as a generic failure
        assertThatThrownBy(() -> client(wm).createInstallmentTransaction("ctm_1", new BigDecimal("50.00")))
                .isInstanceOf(PaddleUnavailableException.class);
    }
}
