package com.bank.persistence.api.internal.v1.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record OfferResponse(
        String offerId,
        String jobId,
        String insurerCode,
        String productCode,
        String productName,
        BigDecimal premiumAmount,
        String premiumFrequency,
        BigDecimal sumAssured,
        Boolean outOfBound,
        String offerStatus,
        String errorSummary,
        String rawOfferBlobId,
        Instant createdAt,
        String fundsJson
) {}
