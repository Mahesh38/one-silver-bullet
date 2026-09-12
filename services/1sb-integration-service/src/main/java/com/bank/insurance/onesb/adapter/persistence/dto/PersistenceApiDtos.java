package com.bank.insurance.onesb.adapter.persistence.dto;

import java.time.Instant;
import java.util.List;

/** Request/response shapes matching bank-persistence-service /internal/v1 contract. */
public final class PersistenceApiDtos {

    private PersistenceApiDtos() {}

    public record CreateJobRequest(
            String lob,
            String jobType,
            String journeyId,
            String idempotencyKey,
            String createdByActor
    ) {}

    public record JobResponse(
            String jobId,
            String jobType,
            String lob,
            String status,
            String failureReason,
            String journeyId,
            String applicationNumber,
            String policyNumber,
            String externalReqId,
            String externalProvider,
            String idempotencyKey,
            String resultBlobId,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            String ownedByInstance,
            Long version,
            String createdByActor
    ) {}

    public record PatchJobStatusRequest(
            String status,
            String failureReason,
            String externalReqId,
            Instant completedAt,
            String applicationNumber
    ) {}

    public record CreateOfferRequest(
            String offerId,
            String insurerCode,
            String productCode,
            String productName,
            java.math.BigDecimal premiumAmount,
            String premiumFrequency,
            java.math.BigDecimal sumAssured,
            Boolean outOfBound,
            String offerStatus,
            String errorSummary,
            String rawOfferBlobId,
            String fundsJson
    ) {}

    public record OfferResponse(
            String offerId,
            String jobId,
            String insurerCode,
            String productCode,
            String productName,
            java.math.BigDecimal premiumAmount,
            String premiumFrequency,
            java.math.BigDecimal sumAssured,
            Boolean outOfBound,
            String offerStatus,
            String errorSummary,
            String rawOfferBlobId,
            Instant createdAt,
            String fundsJson
    ) {}

    public record CreatePaymentSessionRequest(
            String sessionId,
            String jobId,
            String applicationNumber,
            String lob,
            String paymentUrl,
            String redirectUrl,
            String status,
            String externalTxnId,
            Instant expiresAt,
            String createdByActor
    ) {}

    public record PaymentSessionResponse(
            String sessionId,
            String jobId,
            String applicationNumber,
            String lob,
            String paymentUrl,
            String redirectUrl,
            String status,
            String externalTxnId,
            Instant createdAt,
            Instant expiresAt,
            Instant updatedAt,
            String createdByActor
    ) {}

    public record CreatePollAttemptRequest(
            short attemptNumber,
            Instant attemptedAt,
            Short httpStatus,
            Boolean isComplete,
            Integer durationMs,
            String errorMessage
    ) {}

    public record CreateRawPayloadRequest(
            String jobId,
            String direction,
            String operation,
            String lob,
            String payload,
            Short httpStatus
    ) {}

    public record PollAttemptResponse(
            Long attemptId,
            String jobId,
            short attemptNumber,
            Instant attemptedAt,
            Short httpStatus,
            Boolean isComplete,
            Integer durationMs,
            String errorMessage
    ) {}
}
