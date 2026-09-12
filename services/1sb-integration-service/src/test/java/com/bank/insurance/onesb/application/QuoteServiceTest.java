package com.bank.insurance.onesb.application;

import com.bank.insurance.onesb.TestErrors;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditEvent;
import com.bank.common.audit.AuditEventPublisher;
import com.bank.common.error.ErrorCodes;
import com.bank.common.error.ServiceException;
import com.bank.insurance.onesb.domain.command.CreateQuoteCommand;
import com.bank.common.domain.JobStatus;
import com.bank.common.domain.Lob;
import com.bank.common.domain.QuoteJob;
import com.bank.insurance.onesb.domain.port.outbound.JobPollSchedulerPort;
import com.bank.insurance.onesb.domain.port.outbound.JobStorePort;
import com.bank.insurance.onesb.domain.port.outbound.OneSbQuotePort;
import com.bank.insurance.onesb.lob.LobQuoteHandler;
import com.bank.insurance.onesb.lob.LobQuoteHandlerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("FUNC-002")
@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

    @Mock JobStorePort jobStore;
    @Mock OneSbQuotePort quotePort;
    @Mock JobPollSchedulerPort pollScheduler;
    @Mock AuditEventPublisher auditEventPublisher;
    @Mock LobQuoteHandler termHandler;

    private QuoteService quoteService;

    @BeforeEach
    void setUp() {
        when(termHandler.supportedLob()).thenReturn(Lob.TERM);
        LobQuoteHandlerRegistry registry = new LobQuoteHandlerRegistry(List.of(termHandler), TestErrors.ONESB);
        quoteService = new QuoteService(jobStore, registry, quotePort, pollScheduler, auditEventPublisher, TestErrors.ONESB);
    }

    @Test
    void createQuote_validTerm_buildsPayloadViaHandler_submitsPathAndAudits() {
        CreateQuoteCommand command = validCommand();
        Map<String, Object> payload = Map.of("typeOfQuote", "Multi-Quote");
        when(termHandler.buildSubmitPayload(command)).thenReturn(payload);
        when(termHandler.submitPath()).thenReturn("/insurance/lifeterm/v1/quote");
        when(jobStore.createJob("TERM", "QUOTE", "j-1", "idem-1", "actor-1")).thenReturn("job-1");
        when(quotePort.submitQuote("job-1", "/insurance/lifeterm/v1/quote", payload)).thenReturn("REQ-99");

        String jobId = quoteService.createQuote(command);

        assertThat(jobId).isEqualTo("job-1");
        verify(termHandler).buildSubmitPayload(command);
        verify(termHandler).submitPath();
        verify(jobStore).createJob("TERM", "QUOTE", "j-1", "idem-1", "actor-1");
        verify(quotePort).submitQuote("job-1", "/insurance/lifeterm/v1/quote", payload);
        verify(jobStore).updateJobPolling("job-1", "REQ-99");
        verify(pollScheduler).scheduleQuotePoll("job-1", "TERM", "REQ-99");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo(AuditActions.QUOTE_CREATED);
        assertThat(captor.getValue().getResourceId()).isEqualTo("job-1");
    }

    @Test
    void createQuote_missingMembers_throws422_noUpstreamCall() {
        CreateQuoteCommand command = new CreateQuoteCommand(
                Lob.TERM, null, null, new BigDecimal("5000000"), null,
                List.of(), null, null, "j-1", null, "idem-1", "actor-1"
        );

        assertThatThrownBy(() -> quoteService.createQuote(command))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException se = (ServiceException) ex;
                    assertThat(se.getHttpStatus()).isEqualTo(422);
                    assertThat(se.getErrorResponse().getCode()).isEqualTo(ErrorCodes.VALIDATION_ERROR);
                });

        verify(jobStore, never()).createJob(any(), any(), any(), any(), any());
        verify(quotePort, never()).submitQuote(any(), any(), any());
        verify(termHandler, never()).buildSubmitPayload(any());
    }

    @Test
    void createQuote_unsupportedLob_throws422_noUpstreamCall() {
        CreateQuoteCommand command = new CreateQuoteCommand(
                Lob.HEALTH, null, null, new BigDecimal("5000000"), null,
                List.of(new CreateQuoteCommand.MemberDetail(
                        "LIFE_ASSURED", 1, "1990-01-15", "M", false, null, null)),
                null, null, "j-1", null, "idem-1", "actor-1"
        );

        assertThatThrownBy(() -> quoteService.createQuote(command))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException se = (ServiceException) ex;
                    assertThat(se.getHttpStatus()).isEqualTo(422);
                    assertThat(se.getErrorResponse().getCode()).isEqualTo(ErrorCodes.UNSUPPORTED_LOB);
                });

        verify(quotePort, never()).submitQuote(any(), any(), any());
        verify(termHandler, never()).buildSubmitPayload(any());
    }

    @Test
    @Tag("FUNC-022")
    void createQuote_singleWithoutPin_throws422_noUpstreamCall() {
        CreateQuoteCommand command = new CreateQuoteCommand(
                Lob.TERM, "SINGLE", null, new BigDecimal("5000000"), null,
                List.of(new CreateQuoteCommand.MemberDetail(
                        "LIFE_ASSURED", 1, "1990-01-15", "M", false, null, null)),
                null, null, "j-1", null, "idem-1", "actor-1"
        );

        assertThatThrownBy(() -> quoteService.createQuote(command))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException se = (ServiceException) ex;
                    assertThat(se.getHttpStatus()).isEqualTo(422);
                    assertThat(se.getErrorResponse().getCode()).isEqualTo(ErrorCodes.VALIDATION_ERROR);
                });

        verify(jobStore, never()).createJob(any(), any(), any(), any(), any());
        verify(quotePort, never()).submitQuote(any(), any(), any());
    }

    /**
     * FUNC-003: GET returns the job for all terminal statuses (including TIMEOUT) so the bank
     * can poll. QUOTE_TIMEOUT is not thrown on the GET path — controller maps to 200 + status.
     */
    @Test
    @Tag("FUNC-003")
    void getQuoteResult_timeout_returnsJobWithTimeoutStatus() {
        QuoteJob timedOut = new QuoteJob(
                "job-to", JobStatus.TIMEOUT, "POLL_TIMEOUT", Lob.TERM, "j-1",
                List.of(), List.of(), Instant.parse("2026-07-30T12:00:00Z"),
                Instant.parse("2026-07-30T12:05:00Z"), null);
        when(jobStore.findQuoteJob("job-to")).thenReturn(Optional.of(timedOut));

        QuoteJob result = quoteService.getQuoteResult("job-to");
        assertThat(result).isEqualTo(timedOut);
        assertQuoteTerminal(result, JobStatus.TIMEOUT, "POLL_TIMEOUT");
    }

    @Test
    @Tag("FUNC-003")
    void getQuoteResult_unknown_throwsResourceNotFound() {
        when(jobStore.findQuoteJob("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> quoteService.getQuoteResult("missing"))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException se = (ServiceException) ex;
                    assertThat(se.getHttpStatus()).isEqualTo(404);
                    assertThat(se.getErrorResponse().getCode()).isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND);
                });
    }

    @Test
    void getQuoteResult_completed_returnsJob() {
        QuoteJob job = new QuoteJob(
                "job-ok", JobStatus.COMPLETED, null, Lob.TERM, "j-1",
                List.of(), List.of(), Instant.parse("2026-07-30T12:00:00Z"),
                Instant.parse("2026-07-30T12:01:00Z"), null);
        when(jobStore.findQuoteJob("job-ok")).thenReturn(Optional.of(job));

        assertThat(quoteService.getQuoteResult("job-ok")).isEqualTo(job);
    }

    /** Optional helper: assert terminal job status (+ optional failureReason) without throwing. */
    private static void assertQuoteTerminal(QuoteJob job, JobStatus expected, String failureReason) {
        assertThat(job.status()).isEqualTo(expected);
        assertThat(job.failureReason()).isEqualTo(failureReason);
    }

    private static CreateQuoteCommand validCommand() {
        return new CreateQuoteCommand(
                Lob.TERM,
                "MULTI",
                "SUM_ASSURED",
                new BigDecimal("5000000"),
                null,
                List.of(new CreateQuoteCommand.MemberDetail(
                        "LIFE_ASSURED", 1, "1990-01-15", "M", false,
                        new BigDecimal("1000000"), "400001")),
                null,
                new CreateQuoteCommand.DistributionContext(null, "109337", "B2B"),
                "j-1",
                null,
                "idem-1",
                "actor-1"
        );
    }
}
