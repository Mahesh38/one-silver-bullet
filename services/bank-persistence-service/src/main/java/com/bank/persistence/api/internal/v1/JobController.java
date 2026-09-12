package com.bank.persistence.api.internal.v1;

import com.bank.common.error.ServiceErrors;
import com.bank.persistence.api.internal.v1.dto.CreateJobRequest;
import com.bank.persistence.api.internal.v1.dto.CreateOfferRequest;
import com.bank.persistence.api.internal.v1.dto.CreatePollAttemptRequest;
import com.bank.persistence.api.internal.v1.dto.JobResponse;
import com.bank.persistence.api.internal.v1.dto.OfferResponse;
import com.bank.persistence.api.internal.v1.dto.PatchJobStatusRequest;
import com.bank.persistence.api.internal.v1.dto.PollAttemptResponse;
import com.bank.persistence.entity.IntegrationJobEntity;
import com.bank.persistence.entity.IntegrationJobOfferEntity;
import com.bank.persistence.entity.JobPollAttemptEntity;
import com.bank.persistence.repo.IntegrationJobOfferRepository;
import com.bank.persistence.repo.IntegrationJobRepository;
import com.bank.persistence.repo.JobPollAttemptRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/jobs")
public class JobController {

    private final IntegrationJobRepository jobRepository;
    private final IntegrationJobOfferRepository offerRepository;
    private final JobPollAttemptRepository pollAttemptRepository;
    private final ServiceErrors serviceErrors;


    public JobController(IntegrationJobRepository jobRepository,
                         IntegrationJobOfferRepository offerRepository,
                         JobPollAttemptRepository pollAttemptRepository,
                          ServiceErrors serviceErrors) {
        this.serviceErrors = serviceErrors;
        this.jobRepository = jobRepository;
        this.offerRepository = offerRepository;
        this.pollAttemptRepository = pollAttemptRepository;
    }

    @PostMapping
    public ResponseEntity<JobResponse> createJob(@Valid @RequestBody CreateJobRequest request) {
        Instant now = Instant.now();
        IntegrationJobEntity entity = new IntegrationJobEntity();
        entity.setJobId(UUID.randomUUID().toString());
        entity.setJobType(request.jobType());
        entity.setLob(request.lob());
        entity.setStatus("PENDING");
        entity.setJourneyId(request.journeyId());
        entity.setIdempotencyKey(request.idempotencyKey());
        entity.setExternalProvider("ONE_SB");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setCreatedByActor(request.createdByActor());
        entity.setVersion(0L);

        IntegrationJobEntity saved = jobRepository.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping("/{jobId}")
    public JobResponse getJob(@PathVariable String jobId) {
        return jobRepository.findById(jobId)
                .map(JobController::toResponse)
                .orElseThrow(() -> NotFound.of(serviceErrors, "Job", jobId));
    }

    @PatchMapping("/{jobId}/status")
    public JobResponse patchStatus(@PathVariable String jobId,
                                   @Valid @RequestBody PatchJobStatusRequest request) {
        IntegrationJobEntity entity = jobRepository.findById(jobId)
                .orElseThrow(() -> NotFound.of(serviceErrors, "Job", jobId));

        entity.setStatus(request.status());
        if (request.failureReason() != null) {
            entity.setFailureReason(request.failureReason());
        }
        if (request.externalReqId() != null) {
            entity.setExternalReqId(request.externalReqId());
        }
        if (request.completedAt() != null) {
            entity.setCompletedAt(request.completedAt());
        }
        if (request.applicationNumber() != null) {
            entity.setApplicationNumber(request.applicationNumber());
        }
        entity.setUpdatedAt(Instant.now());

        return toResponse(jobRepository.save(entity));
    }

    @GetMapping("/{jobId}/offers")
    public List<OfferResponse> listOffers(@PathVariable String jobId) {
        if (!jobRepository.existsById(jobId)) {
            throw NotFound.of(serviceErrors, "Job", jobId);
        }
        return offerRepository.findByJobId(jobId).stream()
                .map(JobController::toOfferResponse)
                .toList();
    }

    @PostMapping("/{jobId}/offers")
    public ResponseEntity<OfferResponse> addOffer(@PathVariable String jobId,
                                                  @RequestBody CreateOfferRequest request) {
        if (!jobRepository.existsById(jobId)) {
            throw NotFound.of(serviceErrors, "Job", jobId);
        }

        Instant now = Instant.now();
        IntegrationJobOfferEntity entity = new IntegrationJobOfferEntity();
        entity.setOfferId(request.offerId() != null ? request.offerId() : UUID.randomUUID().toString());
        entity.setJobId(jobId);
        entity.setInsurerCode(request.insurerCode());
        entity.setProductCode(request.productCode());
        entity.setProductName(request.productName());
        entity.setPremiumAmount(request.premiumAmount());
        entity.setPremiumFrequency(request.premiumFrequency());
        entity.setSumAssured(request.sumAssured());
        entity.setOutOfBound(request.outOfBound() != null ? request.outOfBound() : Boolean.FALSE);
        entity.setOfferStatus(request.offerStatus());
        entity.setErrorSummary(request.errorSummary());
        entity.setRawOfferBlobId(request.rawOfferBlobId());
        entity.setFundsJson(request.fundsJson());
        entity.setCreatedAt(now);

        IntegrationJobOfferEntity saved = offerRepository.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toOfferResponse(saved));
    }

    @PostMapping("/{jobId}/poll-attempts")
    public ResponseEntity<PollAttemptResponse> addPollAttempt(
            @PathVariable String jobId,
            @Valid @RequestBody CreatePollAttemptRequest request) {
        if (!jobRepository.existsById(jobId)) {
            throw NotFound.of(serviceErrors, "Job", jobId);
        }

        JobPollAttemptEntity entity = new JobPollAttemptEntity();
        entity.setJobId(jobId);
        entity.setAttemptNumber(request.attemptNumber());
        entity.setAttemptedAt(request.attemptedAt() != null ? request.attemptedAt() : Instant.now());
        entity.setHttpStatus(request.httpStatus());
        entity.setIsComplete(request.isComplete());
        entity.setDurationMs(request.durationMs());
        entity.setErrorMessage(request.errorMessage());

        JobPollAttemptEntity saved = pollAttemptRepository.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toPollAttemptResponse(saved));
    }

    @GetMapping("/{jobId}/poll-attempts")
    public List<PollAttemptResponse> listPollAttempts(@PathVariable String jobId) {
        if (!jobRepository.existsById(jobId)) {
            throw NotFound.of(serviceErrors, "Job", jobId);
        }
        return pollAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId).stream()
                .map(JobController::toPollAttemptResponse)
                .toList();
    }

    static JobResponse toResponse(IntegrationJobEntity e) {
        return new JobResponse(
                e.getJobId(),
                e.getJobType(),
                e.getLob(),
                e.getStatus(),
                e.getFailureReason(),
                e.getJourneyId(),
                e.getApplicationNumber(),
                e.getPolicyNumber(),
                e.getExternalReqId(),
                e.getExternalProvider(),
                e.getIdempotencyKey(),
                e.getResultBlobId(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getCompletedAt(),
                e.getOwnedByInstance(),
                e.getVersion(),
                e.getCreatedByActor()
        );
    }

    static OfferResponse toOfferResponse(IntegrationJobOfferEntity e) {
        return new OfferResponse(
                e.getOfferId(),
                e.getJobId(),
                e.getInsurerCode(),
                e.getProductCode(),
                e.getProductName(),
                e.getPremiumAmount(),
                e.getPremiumFrequency(),
                e.getSumAssured(),
                e.getOutOfBound(),
                e.getOfferStatus(),
                e.getErrorSummary(),
                e.getRawOfferBlobId(),
                e.getCreatedAt(),
                e.getFundsJson()
        );
    }

    static PollAttemptResponse toPollAttemptResponse(JobPollAttemptEntity e) {
        return new PollAttemptResponse(
                e.getAttemptId(),
                e.getJobId(),
                e.getAttemptNumber(),
                e.getAttemptedAt(),
                e.getHttpStatus(),
                e.getIsComplete(),
                e.getDurationMs(),
                e.getErrorMessage()
        );
    }
}
