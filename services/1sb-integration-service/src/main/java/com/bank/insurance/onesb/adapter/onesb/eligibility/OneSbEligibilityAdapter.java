package com.bank.insurance.onesb.adapter.onesb.eligibility;

import com.bank.common.domain.EligibilitySubmitResult;
import com.bank.common.domain.Lob;
import com.bank.common.domain.ProposalSchema;
import com.bank.insurance.onesb.adapter.onesb.client.OneSbHttpClient;
import com.bank.insurance.onesb.domain.port.outbound.OneSbEligibilityPort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1SB gateCriteria adapter. Paths are supplied by Life LOB handlers.
 */
@Component
public class OneSbEligibilityAdapter implements OneSbEligibilityPort {

    private final OneSbHttpClient httpClient;

    public OneSbEligibilityAdapter(OneSbHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ProposalSchema getCriteria(Lob lob, String productCode, String manufacturerId, String path) {
        Map<String, Object> body = httpClient.get(path, Map.class);
        Map<String, Object> fields = body == null ? Map.of() : new LinkedHashMap<>(body);
        return new ProposalSchema(lob, productCode, manufacturerId, null, fields);
    }

    @Override
    @SuppressWarnings("unchecked")
    public EligibilitySubmitResult submit(String path, Object payload) {
        Map<String, Object> response = httpClient.post(path, payload, Map.class);
        if (response == null) {
            return new EligibilitySubmitResult(true, null, "ACCEPTED");
        }
        String reqId = text(response, "reqId", "requestId");
        if (reqId == null && response.get("data") instanceof Map<?, ?> data) {
            reqId = text((Map<String, Object>) data, "reqId", "requestId");
        }
        boolean rejected = response.get("errors") instanceof java.util.List<?> errors && !errors.isEmpty();
        return new EligibilitySubmitResult(!rejected, reqId, rejected ? "REJECTED" : "ACCEPTED");
    }

    private static String text(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object v = map.get(key);
            if (v != null && StringUtils.hasText(v.toString())) {
                return v.toString();
            }
        }
        return null;
    }
}
