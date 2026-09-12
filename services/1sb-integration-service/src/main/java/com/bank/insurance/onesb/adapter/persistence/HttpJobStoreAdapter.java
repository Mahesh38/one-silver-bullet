package com.bank.insurance.onesb.adapter.persistence;

import com.bank.insurance.onesb.adapter.persistence.dto.PersistenceApiDtos.CreateJobRequest;
import com.bank.insurance.onesb.adapter.persistence.dto.PersistenceApiDtos.CreateOfferRequest;
import com.bank.insurance.onesb.adapter.persistence.dto.PersistenceApiDtos.CreatePollAttemptRequest;
import com.bank.insurance.onesb.adapter.persistence.dto.PersistenceApiDtos.JobResponse;
import com.bank.insurance.onesb.adapter.persistence.dto.PersistenceApiDtos.OfferResponse;
import com.bank.insurance.onesb.adapter.persistence.dto.PersistenceApiDtos.PatchJobStatusRequest;
import com.bank.common.domain.FundAllocation;
import com.bank.common.domain.JobStatus;
import com.bank.common.domain.Lob;
import com.bank.common.domain.QuoteJob;
import com.bank.common.domain.QuoteOffer;
import com.bank.insurance.onesb.domain.port.outbound.JobStorePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * HTTP adapter implementing {@link JobStorePort} against bank-persistence-service.
 */
@Component
public class HttpJobStoreAdapter implements JobStorePort {

    static final String POLL_TIMEOUT_REASON = "POLL_TIMEOUT";

    private static final ObjectMapper FUNDS_JSON = new ObjectMapper();
    private static final TypeReference<List<FundAllocation>> FUNDS_TYPE = new TypeReference<>() {};

    private final RestClient persistenceRestClient;

    @Autowired
    public HttpJobStoreAdapter(@Qualifier("persistenceRestClient") RestClient persistenceRestClient) {
        this.persistenceRestClient = persistenceRestClient;
    }

    @Override
    public String createJob(String lob, String jobType, String journeyId, String idempotencyKey, String actorId) {
        JobResponse response = persistenceRestClient.post()
                .uri("/internal/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateJobRequest(lob, jobType, journeyId, idempotencyKey, actorId))
                .retrieve()
                .body(JobResponse.class);
        if (response == null || response.jobId() == null) {
            throw new IllegalStateException("Persistence createJob returned empty response");
        }
        return response.jobId();
    }

    @Override
    public void updateJobStatus(String jobId, JobStatus status) {
        patchStatus(jobId, new PatchJobStatusRequest(status.name(), null, null, null, null));
    }

    @Override
    public void updateJobPolling(String jobId, String externalReqId) {
        patchStatus(jobId, new PatchJobStatusRequest(JobStatus.RUNNING.name(), null, externalReqId, null, null));
    }

    @Override
    public void completeJob(String jobId, List<QuoteOffer> offers) {
        completeJob(jobId, offers, null);
    }

    @Override
    public void completeJob(String jobId, List<QuoteOffer> offers, String applicationNumber) {
        Instant completedAt = Instant.now();
        JobStatus status = resolveCompleteStatus(offers);
        String appNo = applicationNumber != null && !applicationNumber.isBlank() ? applicationNumber : null;
        patchStatus(jobId, new PatchJobStatusRequest(status.name(), null, null, completedAt, appNo));
        if (offers != null) {
            for (QuoteOffer offer : offers) {
                persistenceRestClient.post()
                        .uri("/internal/v1/jobs/{jobId}/offers", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new CreateOfferRequest(
                                offer.offerId(),
                                offer.insurerCode(),
                                offer.productCode(),
                                offer.productName(),
                                offer.premiumAmount(),
                                offer.premiumFrequency(),
                                offer.sumAssured(),
                                offer.outOfBound(),
                                offer.offerStatus(),
                                offer.errorSummary(),
                                null,
                                writeFundsJson(offer.funds())
                        ))
                        .retrieve()
                        .toBodilessEntity();
            }
        }
    }

    @Override
    public void failJob(String jobId, String failureReason) {
        JobStatus status = POLL_TIMEOUT_REASON.equals(failureReason) ? JobStatus.TIMEOUT : JobStatus.FAILED;
        patchStatus(jobId, new PatchJobStatusRequest(
                status.name(),
                failureReason,
                null,
                Instant.now(),
                null
        ));
    }

    @Override
    public void recordPollAttempt(String jobId, int attemptNumber, int httpStatus,
                                  boolean complete, int durationMs, String errorMessage) {
        persistenceRestClient.post()
                .uri("/internal/v1/jobs/{jobId}/poll-attempts", jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreatePollAttemptRequest(
                        (short) attemptNumber,
                        Instant.now(),
                        (short) httpStatus,
                        complete,
                        durationMs,
                        errorMessage
                ))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public Optional<QuoteJob> findQuoteJob(String jobId) {
        try {
            JobResponse job = persistenceRestClient.get()
                    .uri("/internal/v1/jobs/{jobId}", jobId)
                    .retrieve()
                    .body(JobResponse.class);
            if (job == null) {
                return Optional.empty();
            }
            List<OfferResponse> offers = persistenceRestClient.get()
                    .uri("/internal/v1/jobs/{jobId}/offers", jobId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<OfferResponse>>() {});
            return Optional.of(toQuoteJob(job, offers != null ? offers : List.of()));
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        }
    }

    private void patchStatus(String jobId, PatchJobStatusRequest request) {
        persistenceRestClient.patch()
                .uri("/internal/v1/jobs/{jobId}/status", jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    /** PARTIAL when some offers succeeded and some carry {@code errorSummary}. */
    static JobStatus resolveCompleteStatus(List<QuoteOffer> offers) {
        if (offers == null || offers.isEmpty()) {
            return JobStatus.COMPLETED;
        }
        boolean hasError = offers.stream()
                .anyMatch(o -> o.errorSummary() != null && !o.errorSummary().isBlank());
        boolean hasSuccess = offers.stream()
                .anyMatch(o -> o.errorSummary() == null || o.errorSummary().isBlank());
        return hasError && hasSuccess ? JobStatus.PARTIAL : JobStatus.COMPLETED;
    }

    private static QuoteJob toQuoteJob(JobResponse job, List<OfferResponse> offers) {
        List<QuoteOffer> quoteOffers = offers.stream()
                .map(o -> new QuoteOffer(
                        o.offerId(),
                        o.insurerCode(),
                        null,
                        o.productCode(),
                        o.productName(),
                        o.premiumAmount(),
                        o.premiumFrequency(),
                        o.sumAssured(),
                        Boolean.TRUE.equals(o.outOfBound()),
                        o.offerStatus(),
                        o.errorSummary(),
                        parseFundsJson(o.fundsJson())
                ))
                .toList();
        return new QuoteJob(
                job.jobId(),
                JobStatus.valueOf(job.status()),
                job.failureReason(),
                Lob.valueOf(job.lob()),
                job.journeyId(),
                quoteOffers,
                List.of(),
                job.createdAt(),
                job.completedAt(),
                job.applicationNumber()
        );
    }

    static String writeFundsJson(List<FundAllocation> funds) {
        if (funds == null || funds.isEmpty()) {
            return null;
        }
        try {
            return FUNDS_JSON.writeValueAsString(funds);
        } catch (Exception e) {
            return null;
        }
    }

    static List<FundAllocation> parseFundsJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<FundAllocation> parsed = FUNDS_JSON.readValue(json, FUNDS_TYPE);
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (Exception e) {
            return List.of();
        }
    }
}
