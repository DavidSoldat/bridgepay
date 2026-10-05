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

    @Test
    void findCompletedTransaction_returnsIdAndSubscription_whenCompleted(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/transactions/txn_1"))
                .willReturn(okJson("{ \"data\": { \"id\": \"txn_1\", \"status\": \"completed\", \"subscription_id\": \"sub_1\" } }")));

        assertThat(client(wm).findCompletedTransaction("txn_1"))
                .contains(new PaddleWebhookData("txn_1", "sub_1"));
    }

    @Test
    void findCompletedTransaction_isEmpty_whenNotCompletedYet(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/transactions/txn_1"))
                .willReturn(okJson("{ \"data\": { \"id\": \"txn_1\", \"status\": \"ready\", \"subscription_id\": null } }")));

        assertThat(client(wm).findCompletedTransaction("txn_1")).isEmpty();
    }

    @Test
    void chargeNow_chargesTheSavedCardImmediately_forQuantityInstallments(WireMockRuntimeInfo wm) {
        stubFor(post(urlPathEqualTo("/subscriptions/sub_1/charge"))
                .withRequestBody(matchingJsonPath("$.effective_from", equalTo("immediately")))
                .withRequestBody(matchingJsonPath("$.on_payment_failure", equalTo("prevent_change")))
                .withRequestBody(matchingJsonPath("$.items[0].quantity", equalTo("3")))
                .withRequestBody(matchingJsonPath("$.items[0].price.unit_price.amount", equalTo("1744")))
                .withRequestBody(matchingJsonPath("$.items[0].price.unit_price.currency_code", equalTo("USD")))
                .withRequestBody(matchingJsonPath("$.items[0].price.tax_mode", equalTo("account_setting")))
                .withRequestBody(matchingJsonPath("$.items[0].price.product.name", equalTo("BridgePay installment plan")))
                .withRequestBody(matchingJsonPath("$.items[0].price.product.tax_category", equalTo("standard")))
                .willReturn(okJson("{ \"data\": { \"id\": \"sub_1\", \"status\": \"active\" } }")));

        client(wm).chargeNow("sub_1", new BigDecimal("17.44"), 3);

        verify(1, postRequestedFor(urlPathEqualTo("/subscriptions/sub_1/charge")));
    }

    @Test
    void chargeNow_turnsAPaddleRefusalIntoPaymentRefused(WireMockRuntimeInfo wm) {
        stubFor(post(urlPathEqualTo("/subscriptions/sub_1/charge"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{ \"error\": { \"code\": \"subscription_update_when_past_due\" } }")));

        assertThatThrownBy(() -> client(wm).chargeNow("sub_1", new BigDecimal("17.44"), 1))
                .isInstanceOf(PaddlePaymentRefusedException.class)
                .hasMessageContaining("subscription_update_when_past_due");
    }

    @Test
    void chargeNow_reportsPaddleBeingDown_asUnavailable(WireMockRuntimeInfo wm) {
        stubFor(post(urlPathEqualTo("/subscriptions/sub_1/charge")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client(wm).chargeNow("sub_1", new BigDecimal("17.44"), 1))
                .isInstanceOf(PaddleUnavailableException.class);
    }

    @Test
    void findLatestChargeTransaction_asksForCompletedOneTimeCharges_andReadsTheQuantity(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/transactions"))
                .withQueryParam("subscription_id", equalTo("sub_1"))
                .withQueryParam("origin", equalTo("subscription_charge"))
                .withQueryParam("status", equalTo("completed"))
                .withQueryParam("order_by", equalTo("created_at[DESC]"))
                .willReturn(okJson("""
                        { "data": [
                          { "id": "txn_new", "status": "completed", "subscription_id": "sub_1", "origin": "subscription_charge",
                            "items": [ { "quantity": 3, "price": { "id": "pri_1" } } ] },
                          { "id": "txn_old", "status": "completed", "subscription_id": "sub_1",
                            "items": [ { "quantity": 1 } ] } ] }
                        """)));

        assertThat(client(wm).findLatestChargeTransaction("sub_1"))
                .contains(new PaddleWebhookData("txn_new", "sub_1", java.util.List.of(new PaddleWebhookData.Item(3)),
                        "subscription_charge"));
    }

    @Test
    void findLatestChargeTransaction_isEmpty_whenThereAreNone(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/transactions")).willReturn(okJson("{ \"data\": [] }")));

        assertThat(client(wm).findLatestChargeTransaction("sub_1")).isEmpty();
    }

    @Test
    void refundTransaction_createsAFullRefundAdjustment_whenNoneExists(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/adjustments"))
                .withQueryParam("transaction_id", equalTo("txn_1"))
                .withQueryParam("action", equalTo("refund"))
                .withQueryParam("status", equalTo("pending_approval,approved"))
                .willReturn(okJson("{ \"data\": [] }")));
        stubFor(post(urlPathEqualTo("/adjustments"))
                .withRequestBody(matchingJsonPath("$.action", equalTo("refund")))
                .withRequestBody(matchingJsonPath("$.type", equalTo("full")))
                .withRequestBody(matchingJsonPath("$.transaction_id", equalTo("txn_1")))
                .withRequestBody(matchingJsonPath("$.reason", equalTo("Merchant refund")))
                .willReturn(okJson("{ \"data\": { \"id\": \"adj_1\", \"status\": \"pending_approval\" } }")));

        assertThat(client(wm).refundTransaction("txn_1")).isEqualTo("adj_1");

        verify(1, postRequestedFor(urlPathEqualTo("/adjustments")));
    }

    @Test
    void refundTransaction_reusesAnExistingRefund_insteadOfRefundingTwice(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/adjustments"))
                .withQueryParam("transaction_id", equalTo("txn_2"))
                .willReturn(okJson("{ \"data\": [ { \"id\": \"adj_existing\", \"status\": \"approved\" } ] }")));

        assertThat(client(wm).refundTransaction("txn_2")).isEqualTo("adj_existing");

        verify(0, postRequestedFor(urlPathEqualTo("/adjustments")));
    }

    @Test
    void refundTransaction_surfacesPaddlesRefusal(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathEqualTo("/adjustments")).willReturn(okJson("{ \"data\": [] }")));
        stubFor(post(urlPathEqualTo("/adjustments"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{ \"error\": { \"code\": \"transaction_not_completed\" } }")));

        assertThatThrownBy(() -> client(wm).refundTransaction("txn_3"))
                .isInstanceOf(PaddleUnavailableException.class);
    }
}
