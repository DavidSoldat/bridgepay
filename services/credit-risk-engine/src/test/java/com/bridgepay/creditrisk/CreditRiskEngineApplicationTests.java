package com.bridgepay.creditrisk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Plain context-load smoke test. No Testcontainers needed: Lettuce (the
 * Redis client) connects lazily, so the context wires up fine even without a
 * reachable Redis - see ScoreControllerIntegrationTest for a test that
 * actually exercises the cache.
 */
@SpringBootTest
@ActiveProfiles("local")
class CreditRiskEngineApplicationTests {

    @Test
    void contextLoads() {
    }
}
