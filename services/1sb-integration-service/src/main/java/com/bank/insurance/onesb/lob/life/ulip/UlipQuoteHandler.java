package com.bank.insurance.onesb.lob.life.ulip;

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
 * Life ULIP quote handler ({@code FUNC-019}).
 * <p>
 * Portal reality: ULIP is <strong>not</strong> a separate {@code /lifeulip} quote API.
 * Quotes use the Saving API ({@code /insurance/lifesave/v1/quote}) with
 * {@code product.savingsProductType = ["ULIP"]}. Supplementary portal ops
 * {@code ulip-list-…} and {@code ulip-performance-…} are fund-list/performance helpers,
 * not the primary quote submit path.
 */
@Component
public class UlipQuoteHandler implements LobQuoteHandler {

    static final String SUBMIT_PATH = "/insurance/lifesave/v1/quote";
    static final String POLL_PATH_PREFIX = "/insurance/lifesave/v1/quote/poll/";
    static final String PRODUCT_TYPE = "LifeSave";

    private final SecretProvider secretProvider;

    public UlipQuoteHandler(SecretProvider secretProvider) {
        this.secretProvider = secretProvider;
    }

    @Override
    public Lob supportedLob() {
        return Lob.ULIP;
    }

    @Override
    public LifeQuoteRequest buildSubmitPayload(CreateQuoteCommand command) {
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
