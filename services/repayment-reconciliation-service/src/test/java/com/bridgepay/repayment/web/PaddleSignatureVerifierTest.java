package com.bridgepay.repayment.web;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PaddleSignatureVerifierTest {

    private static final String SECRET = "whsec_test_secret";

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
    void verify_acceptsACorrectlySignedPayload() throws Exception {
        PaddleSignatureVerifier verifier = new PaddleSignatureVerifier(SECRET);
        String body = "{\"event_type\":\"transaction.completed\"}";
        String h1 = sign("1700000000", body);

        assertThat(verifier.verify("ts=1700000000;h1=" + h1, body)).isTrue();
    }

    @Test
    void verify_rejectsATamperedBody() throws Exception {
        PaddleSignatureVerifier verifier = new PaddleSignatureVerifier(SECRET);
        String signedBody = "{\"event_type\":\"transaction.completed\"}";
        String h1 = sign("1700000000", signedBody);
        String tamperedBody = "{\"event_type\":\"transaction.payment_failed\"}";

        assertThat(verifier.verify("ts=1700000000;h1=" + h1, tamperedBody)).isFalse();
    }

    @Test
    void verify_rejectsAWrongSecret() throws Exception {
        String body = "{}";
        String h1 = sign("1700000000", body);
        PaddleSignatureVerifier verifierWithDifferentSecret = new PaddleSignatureVerifier("a-different-secret");

        assertThat(verifierWithDifferentSecret.verify("ts=1700000000;h1=" + h1, body)).isFalse();
    }

    @Test
    void verify_rejectsAMalformedHeader() {
        PaddleSignatureVerifier verifier = new PaddleSignatureVerifier(SECRET);

        assertThat(verifier.verify("not-a-valid-header", "{}")).isFalse();
        assertThat(verifier.verify(null, "{}")).isFalse();
    }
}
