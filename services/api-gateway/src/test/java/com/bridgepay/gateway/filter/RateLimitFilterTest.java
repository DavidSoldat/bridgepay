package com.bridgepay.gateway.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    @Test
    void allowsRequestsUpToTheLimit_thenReturns429() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(200); // untouched by the filter on the allowed path
        }
        verify(chain, times(2)).doFilter(any(), any());

        MockHttpServletRequest thirdRequest = new MockHttpServletRequest();
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(thirdRequest, thirdResponse, chain);

        assertThat(thirdResponse.getStatus()).isEqualTo(429);
        assertThat(thirdResponse.getContentAsString()).contains("RATE_LIMITED");
        verify(chain, times(2)).doFilter(any(), any()); // still 2 - the 3rd never reached the chain
    }
}
