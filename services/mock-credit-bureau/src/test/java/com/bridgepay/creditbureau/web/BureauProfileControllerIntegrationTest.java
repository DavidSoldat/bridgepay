package com.bridgepay.creditbureau.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BureauProfileControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void bureauProfile_returnsAConsistentProfile_forTheSameApplicant() throws Exception {
        String applicantId = UUID.randomUUID().toString();

        String first = mockMvc.perform(get("/internal/bureau-profile/{id}", applicantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.age").exists())
                .andExpect(jsonPath("$.monthlyIncome").exists())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(get("/internal/bureau-profile/{id}", applicantId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
    }

    @Test
    void bureauProfile_rejectsAMalformedApplicantId() throws Exception {
        mockMvc.perform(get("/internal/bureau-profile/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
