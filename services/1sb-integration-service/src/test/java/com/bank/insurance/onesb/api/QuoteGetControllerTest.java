package com.bank.insurance.onesb.api;

import com.bank.common.error.ErrorCodes;
import com.bank.common.error.PlatformErrorAutoConfiguration;
import com.bank.common.error.ServiceErrorResponse;
import com.bank.common.error.ServiceException;
import com.bank.common.domain.JobStatus;
import com.bank.common.domain.Lob;
import com.bank.common.domain.QuoteJob;
import com.bank.common.domain.QuoteOffer;
import com.bank.insurance.onesb.domain.port.inbound.EligibilityUseCase;
import com.bank.insurance.onesb.domain.port.inbound.QuoteUseCase;
import com.bank.insurance.onesb.domain.port.outbound.IdempotencyPort;
import com.bank.insurance.onesb.domain.port.outbound.OneSbQuotePort;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FUNC-003: harden GET /v1/quotes/{jobId} — status + offers, 404, no fabricated offers.
 */
@Tag("FUNC-003")
@WebMvcTest(controllers = QuoteController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(PlatformErrorAutoConfiguration.class)
class QuoteGetControllerTest {

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
    void ac1_completed_returnsStatusAndOffers() throws Exception {
        QuoteOffer offer = new QuoteOffer(
                "off-1", "HDFC", null, "T1", "Term",
                new BigDecimal("4200"), "YEARLY", new BigDecimal("5000000"),
                false, "AVAILABLE", null
        );
        when(quoteUseCase.getQuoteResult("job-done")).thenReturn(new QuoteJob(
                "job-done", JobStatus.COMPLETED, null, Lob.TERM, "j-1",
                List.of(offer), List.of(),
                Instant.parse("2026-07-30T12:00:00Z"),
                Instant.parse("2026-07-30T12:01:00Z"), null));

        mockMvc.perform(get("/v1/quotes/job-done"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId", is("job-done")))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.offers", hasSize(1)))
                .andExpect(jsonPath("$.offers[0].offerId", is("off-1")))
                .andExpect(jsonPath("$.offers[0].insurerCode", is("HDFC")))
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    void ac3_inProgress_returnsStatusWithEmptyOffers() throws Exception {
        when(quoteUseCase.getQuoteResult("job-run")).thenReturn(new QuoteJob(
                "job-run", JobStatus.RUNNING, null, Lob.TERM, "j-1",
                List.of(), List.of(),
                Instant.parse("2026-07-30T12:00:00Z"), null, null));

        mockMvc.perform(get("/v1/quotes/job-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId", is("job-run")))
                .andExpect(jsonPath("$.status", is("RUNNING")))
                .andExpect(jsonPath("$.offers", hasSize(0)))
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    void ac3_pending_returnsStatusWithEmptyOffers_evenIfStoreHadOffers() throws Exception {
        QuoteOffer fake = new QuoteOffer(
                "should-not-appear", "X", null, "P", "P",
                BigDecimal.ONE, "YEARLY", BigDecimal.TEN, false, "AVAILABLE", null
        );
        when(quoteUseCase.getQuoteResult("job-pend")).thenReturn(new QuoteJob(
                "job-pend", JobStatus.PENDING, null, Lob.TERM, "j-1",
                List.of(fake), List.of(),
                Instant.parse("2026-07-30T12:00:00Z"), null, null));

        mockMvc.perform(get("/v1/quotes/job-pend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.offers", hasSize(0)));
    }

    @Test
    void ac2_unknownJobId_returns404_resourceNotFound() throws Exception {
        when(quoteUseCase.getQuoteResult("missing")).thenThrow(new ServiceException(
                ServiceErrorResponse.builder()
                        .title("Not Found")
                        .status(404)
                        .detail("Quote job not found: missing")
                        .code(ErrorCodes.RESOURCE_NOT_FOUND)
                        .retryable(false)
                        .build()));

        mockMvc.perform(get("/v1/quotes/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is(ErrorCodes.RESOURCE_NOT_FOUND)));
    }

    @Test
    void timeout_returns200WithStatusTimeout_noFakeOffers() throws Exception {
        when(quoteUseCase.getQuoteResult("job-to")).thenReturn(new QuoteJob(
                "job-to", JobStatus.TIMEOUT, "POLL_TIMEOUT", Lob.TERM, "j-1",
                List.of(), List.of(),
                Instant.parse("2026-07-30T12:00:00Z"),
                Instant.parse("2026-07-30T12:05:00Z"), null));

        mockMvc.perform(get("/v1/quotes/job-to"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId", is("job-to")))
                .andExpect(jsonPath("$.status", is("TIMEOUT")))
                .andExpect(jsonPath("$.failureReason", is("POLL_TIMEOUT")))
                .andExpect(jsonPath("$.offers", hasSize(0)));
    }
}
