package com.bank.insurance.onesb.lob.life.term;

import com.bank.common.domain.Lob;
import com.bank.common.secrets.SecretProvider;
import com.bank.insurance.onesb.domain.command.CreateQuoteCommand;
import com.bank.insurance.onesb.lob.LobQuoteHandler;
import com.bank.insurance.onesb.lob.life.LifeEligibilitySupport;
import com.bank.insurance.onesb.lob.life.LifeQuotePayloadFactory;
import com.bank.insurance.onesb.lob.life.payload.LifeQuoteRequest;
import org.springframework.stereotype.Component;

/**
 * Term LOB quote handler — maps bank {@link CreateQuoteCommand} to typed
 * {@link LifeQuoteRequest} for {@code POST /insurance/lifeterm/v1/quote}.
 * <p>
 * Poll path: {@code GET /insurance/lifeterm/v1/quote/poll/{reqId}}.
 * Portal: retail Term consumer-request (insurance-gateway-api).
 */
@Component
public class TermQuoteHandler implements LobQuoteHandler {

    static final String SUBMIT_PATH = "/insurance/lifeterm/v1/quote";
    static final String POLL_PATH_PREFIX = "/insurance/lifeterm/v1/quote/poll/";
    /** 1SB productType / legacy product token for Term. */
    static final String PRODUCT_TYPE = "LifeTerm";

    private final SecretProvider secretProvider;

    public TermQuoteHandler(SecretProvider secretProvider) {
        this.secretProvider = secretProvider;
    }

    @Override
    public Lob supportedLob() {
        return Lob.TERM;
    }

    @Override
    public LifeQuoteRequest buildSubmitPayload(CreateQuoteCommand command) {
        return LifeQuotePayloadFactory.build(
                command, secretProvider, LifeQuoteRequest.Product.term(PRODUCT_TYPE));
    }

    @Override
    public String submitPath() {
        return SUBMIT_PATH;
    }

    @Override
    public String pollPath(String externalReqId) {
        return POLL_PATH_PREFIX + externalReqId;
    }

    @Override
    public String criteriaPath(String productCode, String manufacturerId) {
        return LifeEligibilitySupport.criteriaPath(SUBMIT_PATH, productCode, manufacturerId);
    }
}
