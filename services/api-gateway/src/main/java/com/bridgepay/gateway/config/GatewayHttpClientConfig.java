package com.bridgepay.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;

/**
 * Spring Cloud Gateway Server MVC proxies every route through a RestClient
 * built by its own gatewayRestClientCustomizer, which picks up any
 * ClientHttpRequestFactory bean on the context (Boot's standard
 * RestClientCustomizer extension point). With no Apache/Jetty/Reactor-Netty
 * HTTP client on the classpath, Boot falls back to the JDK HttpClient, whose
 * default HTTP/2 negotiation triggers "RST_STREAM: Stream cancelled" against
 * WireMock's Jetty engine in tests - the same issue already worked around in
 * HttpPaddleClient (repayment-reconciliation-service). Forcing HTTP/1.1 here
 * fixes it the same way, for every proxied route.
 */
@Configuration
public class GatewayHttpClientConfig {

    @Bean
    ClientHttpRequestFactory clientHttpRequestFactory() {
        HttpClient jdkHttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return new JdkClientHttpRequestFactory(jdkHttpClient);
    }
}
