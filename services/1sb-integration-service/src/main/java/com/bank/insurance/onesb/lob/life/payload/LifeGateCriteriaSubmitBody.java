package com.bank.insurance.onesb.lob.life.payload;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed 1SB Life gate-criteria submit body ({@code FUNC-023}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class LifeGateCriteriaSubmitBody {

    private final LifeQuoteRequest.Distributor distributor;
    private final Map<String, Object> formFields;

    public LifeGateCriteriaSubmitBody(
            LifeQuoteRequest.Distributor distributor,
            Map<String, Object> formFields) {
        this.distributor = distributor;
        this.formFields = formFields == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(formFields));
    }

    public LifeQuoteRequest.Distributor getDistributor() {
        return distributor;
    }

    @JsonAnyGetter
    public Map<String, Object> formFields() {
        return formFields;
    }
}
