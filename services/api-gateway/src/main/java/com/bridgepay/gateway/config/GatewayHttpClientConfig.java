package com.bridgepay.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Spring Cloud Gateway Server MVC proxies every route through a RestClient
 * built by its own gatewayRestClientCustomizer, which picks up any
 * ClientHttpRequestFactory bean on the context (Boot's standard
 * RestClientCustomizer extension point).
 *
 * Not the JDK HttpClient: JDK 21's Http1Exchange intermittently NPEs in
 * requestMoreBody ("this.bodySubscriber is null") under load, which the
 * gateway turned into a sporadic 502 on real proxied requests (seen three
 * times in CI). Its default HTTP/2 negotiation also broke against WireMock's
 * Jetty. HttpURLConnection is HTTP/1.1-only and has neither problem.
 */
@Configuration
public class GatewayHttpClientConfig {

    // ponytail: HttpURLConnection can't send PATCH - fine while no /api/v1 route uses it;
    // add spring's HttpComponentsClientHttpRequestFactory (httpclient5) if one ever does.
    @Bean
    ClientHttpRequestFactory clientHttpRequestFactory() {
        return new SimpleClientHttpRequestFactory();
    }
}
