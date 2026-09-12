package com.bank.insurance.onesb.lob.life.saving;

import com.bank.common.domain.Lob;
import com.bank.common.secrets.SecretProvider;
import com.bank.insurance.onesb.domain.command.CreateQuoteCommand;
import com.bank.insurance.onesb.lob.LobQuoteHandler;
import com.bank.insurance.onesb.lob.life.LifeEligibilitySupport;
import com.bank.insurance.onesb.lob.life.LifeQuotePayloadFactory;
import com.bank.insurance.onesb.lob.life.payload.LifeQuoteRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Life Savings quote handler ({@code FUNC-015}).
 * <p>
 * Portal-confirmed path: {@code POST /insurance/lifesave/v1/quote}
 * ({@code SOURCE-LINKS.md}, api-catalog §4, saving-consumer-request).
 * Product type {@code LifeSave}. Demo catalog for {@code BCIBL} is ULIP-only;
 * {@code savingsProductType=["ULIP"]} is the working Multi-Quote filter.
 * There is no separate {@code /lifeulip} quote API — ULIP is this same path.
 */
@Component
public class SavingQuoteHandler implements LobQuoteHandler {

    static final String SUBMIT_PATH = "/insurance/lifesave/v1/quote";
    static final String POLL_PATH_PREFIX = "/insurance/lifesave/v1/quote/poll/";
    static final String PRODUCT_TYPE = "LifeSave";

    private final SecretProvider secretProvider;

    public SavingQuoteHandler(SecretProvider secretProvider) {
        this.secretProvider = secretProvider;
    }

    @Override
    public Lob supportedLob() {
        return Lob.SAVING;
    }

    @Override
    public LifeQuoteRequest buildSubmitPayload(CreateQuoteCommand command) {
        // Demo Saving catalog is ULIP-only. nonParticipating/Participating remain valid
        // 1SB enum values but return INSGW_NO_VALID_PRODUCT_FOUND for BCIBL today.
        return LifeQuotePayloadFactory.build(
                command,
                secretProvider,
                LifeQuoteRequest.Product.saving(PRODUCT_TYPE, List.of("ULIP")));
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
