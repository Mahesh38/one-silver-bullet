package com.bank.insurance.onesb.lob;

import com.bank.insurance.onesb.domain.command.CreateQuoteCommand;
import com.bank.common.domain.Lob;

/**
 * Strategy interface for LOB-specific quote request translation.
 * Each implementation maps a bank-canonical {@link CreateQuoteCommand} to the
 * insurer-specific JSON payload and knows the 1SB submit/poll paths for its LOB.
 */
public interface LobQuoteHandler {

    Lob supportedLob();

    /**
     * Builds a typed JSON-serializable payload for the 1SB quote POST body.
     * Must not leak bank-only fields; adapter posts this as-is via Jackson.
     * Prefer records / DTOs — do not assemble bodies via {@code Map.put}.
     */
    Object buildSubmitPayload(CreateQuoteCommand command);

    /** Relative 1SB path for quote submit (e.g. {@code /insurance/lifeterm/v1/quote}). */
    String submitPath();

    /**
     * Relative 1SB path for quote poll by external request id.
     * Term: {@code GET /insurance/lifeterm/v1/quote/poll/{reqId}}.
     */
    String pollPath(String externalReqId);

    /**
     * Relative 1SB GET path for product gate criteria, including query string.
     * Unsupported LOBs leave this {@code null}.
     */
    default String criteriaPath(String productCode, String manufacturerId) {
        return null;
    }
}
