package com.bank.insurance.onesb.api;

import com.bank.common.error.PlatformErrorAutoConfiguration;

import com.bank.common.error.ErrorCodes;
import com.bank.insurance.onesb.domain.port.inbound.EligibilityUseCase;
import com.bank.insurance.onesb.domain.port.inbound.QuoteUseCase;
import com.bank.insurance.onesb.domain.port.outbound.IdempotencyPort;
import com.bank.insurance.onesb.domain.port.outbound.OneSbQuotePort;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("FUNC-002")
@WebMvcTest(controllers = QuoteController.class)
@Import(PlatformErrorAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class QuoteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QuoteUseCase quoteUseCase;

    @MockBean
    private EligibilityUseCase eligibilityUseCase;

    @MockBean
    private OneSbQuotePort oneSbQuotePort;

    @MockBean
    private IdempotencyPort idempotencyPort;

    @Test
    void createQuote_valid_returns202WithJobIdOnly() throws Exception {
        when(quoteUseCase.createQuote(any())).thenReturn("job-abc");

        mockMvc.perform(post("/v1/quotes")
                        .header("Idempotency-Key", "idem-1")
                        .header("X-Actor-Id", "rm-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "journeyId": "j-1",
                                  "sumAssured": 5000000,
                                  "members": [{ "dob": "1990-01-15", "gender": "M" }],
                                  "distribution": { "agentId": "109337" }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId", is("job-abc")))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.reqId").doesNotExist());

        verify(quoteUseCase).createQuote(any());
        verify(oneSbQuotePort, never()).submitQuote(any(), any(), any());
    }

    @Test
    void createQuote_missingMembers_returns422_validationError_noUseCaseOrUpstream() throws Exception {
        mockMvc.perform(post("/v1/quotes")
                        .header("Idempotency-Key", "idem-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "sumAssured": 5000000,
                                  "members": []
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is(ErrorCodes.VALIDATION_ERROR)));

        verify(quoteUseCase, never()).createQuote(any());
        verify(oneSbQuotePort, never()).submitQuote(any(), any(), any());
    }

    @Test
    void createQuote_missingSumAssured_returns422_validationError_noUpstream() throws Exception {
        mockMvc.perform(post("/v1/quotes")
                        .header("Idempotency-Key", "idem-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "members": [{ "dob": "1990-01-15", "gender": "M" }]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is(ErrorCodes.VALIDATION_ERROR)));

        verify(quoteUseCase, never()).createQuote(any());
        verify(oneSbQuotePort, never()).submitQuote(any(), any(), any());
    }
}
