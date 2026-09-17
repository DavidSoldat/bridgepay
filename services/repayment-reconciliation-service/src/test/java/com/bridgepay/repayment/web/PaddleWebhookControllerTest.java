package com.bridgepay.repayment.web;

import com.bridgepay.repayment.service.PaddleWebhookService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Drives real HMAC-signed (and tampered) payloads through the actual
 * signature verifier end-to-end, proving that security-critical path works
 * rather than just compiles.
 */
@ExtendWith(MockitoExtension.class)
class PaddleWebhookControllerTest {

    private static final String SECRET = "whsec_test_secret";

    @Mock
    private PaddleWebhookService webhookService;

    private final PaddleSignatureVerifier verifier = new PaddleSignatureVerifier(SECRET);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String sign(String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal((timestamp + ":" + body).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    void receive_processesAndAccepts_aCorrectlySignedWebhook() throws Exception {
        PaddleWebhookController controller = new PaddleWebhookController(verifier, webhookService, objectMapper);
        String body = "{\"event_id\":\"evt_1\",\"event_type\":\"transaction.completed\",\"data\":{\"id\":\"txn_1\",\"subscription_id\":\"sub_1\"}}";
        String signature = "ts=1700000000;h1=" + sign("1700000000", body);

        ResponseEntity<Void> response = controller.receive(signature, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(webhookService).handle("transaction.completed",
                new com.bridgepay.repayment.client.PaddleWebhookData("txn_1", "sub_1"));
    }

    @Test
    void receive_rejectsAndSkipsProcessing_aTamperedWebhook() throws Exception {
        PaddleWebhookController controller = new PaddleWebhookController(verifier, webhookService, objectMapper);
        String signedBody = "{\"event_type\":\"transaction.completed\"}";
        String signature = "ts=1700000000;h1=" + sign("1700000000", signedBody);
        String tamperedBody = "{\"event_type\":\"subscription.canceled\"}";

        ResponseEntity<Void> response = controller.receive(signature, tamperedBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(webhookService);
    }
}
