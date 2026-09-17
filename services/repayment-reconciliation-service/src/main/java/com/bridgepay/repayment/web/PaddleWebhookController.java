package com.bridgepay.repayment.web;

import com.bridgepay.repayment.client.PaddleWebhookEnvelope;
import com.bridgepay.repayment.service.PaddleWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Deliberately not under /internal/ (ClusterIP-only in this project) or
 * /api/v1/ (JWT-gated through the gateway) - this is the one endpoint in the
 * platform a third party (Paddle) must reach over the public internet, with
 * HMAC signature verification as its access control instead of either.
 */
@RestController
public class PaddleWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaddleWebhookController.class);

    private final PaddleSignatureVerifier signatureVerifier;
    private final PaddleWebhookService webhookService;
    private final ObjectMapper objectMapper;

    public PaddleWebhookController(PaddleSignatureVerifier signatureVerifier,
                                    PaddleWebhookService webhookService,
                                    ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhooks/paddle")
    public ResponseEntity<Void> receive(@RequestHeader("Paddle-Signature") String signature,
                                         @RequestBody String rawBody) {
        if (!signatureVerifier.verify(signature, rawBody)) {
            log.warn("Rejected Paddle webhook with an invalid signature");
            return ResponseEntity.badRequest().build();
        }

        PaddleWebhookEnvelope envelope = objectMapper.readValue(rawBody, PaddleWebhookEnvelope.class);
        webhookService.handle(envelope.eventType(), envelope.data());
        return ResponseEntity.ok().build();
    }
}
