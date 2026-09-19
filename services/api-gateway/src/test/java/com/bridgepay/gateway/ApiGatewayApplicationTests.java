package com.bridgepay.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class ApiGatewayApplicationTests {

    @Test
    void contextLoads() {
        // Proves the whole dependency set (Spring Cloud Gateway Server MVC +
        // Spring Security's OAuth2 resource server autoconfig, excluded here
        // via the local profile + our own JWT stack) resolves and boots
        // together on Spring Boot 4.1.1 - unverified before this task.
    }
}
