package com.bank.common.domain;

/**
 * Bank-facing result of submitting a product eligibility (gate criteria) form.
 */
public record EligibilitySubmitResult(
        boolean accepted,
        String reqId,
        String status
) {}
