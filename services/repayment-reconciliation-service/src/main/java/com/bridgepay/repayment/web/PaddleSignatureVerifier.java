package com.bridgepay.repayment.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * Verified against Paddle's own docs, not guessed: header is
 * "Paddle-Signature: ts=<unix>;h1=<hex>", the signed payload is the literal
 * string "{ts}:{rawBody}", hashed with HMAC-SHA256 against the webhook
 * secret. Comparison is timing-safe (MessageDigest.isEqual), same guard
 * Paddle's own docs recommend. A ts more than 5 s from now is refused, so a
 * captured request can't be replayed later; Paddle signs each retry afresh.
 */
@Component
public class PaddleSignatureVerifier {

    private static final long TOLERANCE_SECONDS = 5;

    private final String webhookSecret;
    private final Clock clock;

    @Autowired
    public PaddleSignatureVerifier(@Value("${paddle.webhook-secret}") String webhookSecret) {
        this(webhookSecret, Clock.systemUTC());
    }

    PaddleSignatureVerifier(String webhookSecret, Clock clock) {
        this.webhookSecret = webhookSecret;
        this.clock = clock;
    }

    public boolean verify(String signatureHeader, String rawBody) {
        if (signatureHeader == null) {
            return false;
        }
        Map<String, String> parts = parseHeader(signatureHeader);
        String timestamp = parts.get("ts");
        String providedHash = parts.get("h1");
        if (timestamp == null || providedHash == null || !isFresh(timestamp)) {
            return false;
        }

        String expectedHash = hmacSha256Hex(timestamp + ":" + rawBody, webhookSecret);
        return MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.UTF_8),
                providedHash.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isFresh(String timestamp) {
        try {
            return Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp)) <= TOLERANCE_SECONDS;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static Map<String, String> parseHeader(String header) {
        Map<String, String> parts = new HashMap<>();
        for (String segment : header.split(";")) {
            String[] keyValue = segment.split("=", 2);
            if (keyValue.length == 2) {
                parts.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return parts;
    }

    private static String hmacSha256Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", ex);
        }
    }
}
