package com.bank.insurance.onesb.api.dto;

import com.bank.common.domain.Lob;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Bank-facing create-quote request body for {@code POST /v1/quotes}.
 */
public record CreateQuoteRequest(
        @NotNull Lob lob,
        String mode,
        String category,
        @NotNull BigDecimal sumAssured,
        BigDecimal premiumAmount,
        @NotEmpty @Valid List<MemberRequest> members,
        Map<String, Object> preferences,
        DistributionRequest distribution,
        String journeyId,
        String sessionId,
        ProductSelectionRequest selection
) {
    public record MemberRequest(
            String role,
            Integer sequenceNumber,
            @NotNull String dob,
            @NotNull String gender,
            Boolean tobacco,
            BigDecimal annualIncome,
            String pincode
    ) {}

    public record DistributionRequest(
            String rmEmployeeId,
            String agentId,
            String channelType
    ) {}

    /**
     * Single Quote pin. Required when {@code mode} is SINGLE.
     */
    public record ProductSelectionRequest(
            String insurerCode,
            List<String> productCodes,
            String planOption,
            String coverOption,
            String deathBenefitOption,
            Integer policyTerm,
            Integer premiumPaymentTerm,
            String premiumFrequency,
            String premiumPaymentOption
    ) {}
}
