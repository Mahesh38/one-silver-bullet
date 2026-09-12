package com.bank.insurance.onesb.api.dto;

import com.bank.common.domain.Lob;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Bank-facing eligibility (gate criteria) submit for {@code POST /v1/quotes/criteria}.
 */
public record EligibilitySubmitRequest(
        @NotNull Lob lob,
        @NotBlank String productCode,
        @NotBlank String manufacturerId,
        Map<String, Object> values,
        String agentId,
        CreateQuoteRequest.DistributionRequest distribution
) {}
