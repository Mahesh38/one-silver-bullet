package com.bank.insurance.onesb.domain.command;

import com.bank.common.domain.Lob;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Command object for creating a new quote job.
 * Bank-canonical field names only — no 1SB field names.
 */
public record CreateQuoteCommand(
        Lob lob,
        String mode,
        String category,
        BigDecimal sumAssured,
        BigDecimal premiumAmount,
        List<MemberDetail> members,
        Map<String, Object> preferences,
        DistributionContext distribution,
        String journeyId,
        String sessionId,
        String idempotencyKey,
        String actorId,
        ProductSelection selection
) {
    /** Compatibility constructor for callers that do not pin a Single Quote product. */
    public CreateQuoteCommand(
            Lob lob,
            String mode,
            String category,
            BigDecimal sumAssured,
            BigDecimal premiumAmount,
            List<MemberDetail> members,
            Map<String, Object> preferences,
            DistributionContext distribution,
            String journeyId,
            String sessionId,
            String idempotencyKey,
            String actorId) {
        this(lob, mode, category, sumAssured, premiumAmount, members, preferences, distribution,
                journeyId, sessionId, idempotencyKey, actorId, null);
    }

    public record MemberDetail(
            String role,
            int sequenceNumber,
            String dob,
            String gender,
            boolean tobacco,
            BigDecimal annualIncome,
            String pincode
    ) {}

    public record DistributionContext(
            String rmEmployeeId,
            String agentId,
            String channelType
    ) {}

    /**
     * Optional pin for Single Quote. Bank names: insurer + product codes + option ids.
     */
    public record ProductSelection(
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
