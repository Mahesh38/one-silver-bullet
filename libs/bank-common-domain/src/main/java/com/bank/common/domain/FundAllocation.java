package com.bank.common.domain;

import java.math.BigDecimal;

/**
 * Bank-canonical ULIP fund row taken from a quote offer (not a 1SB list API).
 */
public record FundAllocation(
        String code,
        String name,
        BigDecimal allocationPercent
) {}
