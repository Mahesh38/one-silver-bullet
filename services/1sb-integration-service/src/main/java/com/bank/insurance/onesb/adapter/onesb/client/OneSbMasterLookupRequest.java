package com.bank.insurance.onesb.adapter.onesb.client;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Typed 1SB master-lookup request ({@code FUNC-024}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OneSbMasterLookupRequest(
        String lookUpCategory,
        List<String> entityIds,
        String manufacturerId,
        Distributor distributor
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Distributor(String distributorID, String channelType, String salesChannel) {}
}
