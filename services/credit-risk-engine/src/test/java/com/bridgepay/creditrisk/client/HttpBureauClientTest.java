package com.bridgepay.creditrisk.client;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpBureauClientTest {

    @Test
    void fetchProfile_throwsBureauUnavailable_whenMockCreditBureauIsUnreachable() {
        // Port 1 is a reserved/unused port - connection should fail fast.
        HttpBureauClient client = new HttpBureauClient(RestClient.builder(), "http://localhost:1");

        assertThatThrownBy(() -> client.fetchProfile(UUID.randomUUID()))
                .isInstanceOf(BureauUnavailableException.class);
    }
}
