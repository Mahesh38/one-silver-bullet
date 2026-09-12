package com.bank.persistence.api.internal.v1.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record CreateOfferRequest(
        String offerId,
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
        String fundsJson
) {}
