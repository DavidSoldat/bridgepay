package com.bridgepay.creditrisk.web;

import com.bridgepay.creditrisk.client.RepaymentHistoryUnavailableException;
import com.bridgepay.creditrisk.scoring.ScoringService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CreditLimitUnavailableTest {

    @Test
    void anUpstreamFailure_is503_notAMadeUpLimit() throws Exception {
        ScoringService scoringService = mock(ScoringService.class);
        when(scoringService.creditLimit(any())).thenThrow(new RepaymentHistoryUnavailableException("down", null));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ScoreController(scoringService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/internal/credit-limit/{id}", UUID.randomUUID()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("CREDIT_LIMIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").exists());
    }
}
