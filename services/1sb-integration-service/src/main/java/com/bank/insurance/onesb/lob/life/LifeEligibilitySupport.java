package com.bank.insurance.onesb.lob.life;

import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Shared Life eligibility (gateCriteria) path builder.
 */
public final class LifeEligibilitySupport {

    private LifeEligibilitySupport() {}

    public static String criteriaPath(String quoteSubmitPath, String productCode, String manufacturerId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(quoteSubmitPath + "/gateCriteria");
        if (StringUtils.hasText(productCode)) {
            builder.queryParam("productId", productCode);
        }
        if (StringUtils.hasText(manufacturerId)) {
            builder.queryParam("manufacturerId", manufacturerId);
        }
        return builder.build().encode().toUriString();
    }
}
