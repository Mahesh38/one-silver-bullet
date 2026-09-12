package com.bank.insurance.onesb.lob.life.payload;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Typed 1SB Life quote request body shared by Term, Savings and ULIP handlers.
 * <p>
 * Portal alignment ({@code insurance-gateway-api} / retail LOB pages):
 * request {@code product.productType} is {@code LifeTerm} or {@code LifeSave};
 * Savings/ULIP filter via {@code product.savingsProductType} ({@code nonParticipating}|{@code Participating}|{@code ULIP}).
 * Saving docs require distributor {@code agentId} (camelCase {@code d}); Term still accepts
 * {@code agentID}. Both names are serialised so one payload works on every Life path.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LifeQuoteRequest(
        String typeOfQuote,
        String quoteCategory,
        String includeBI,
        String outOfBoundConfig,
        AdditionalSetup additionalSetup,
        Distributor distributor,
        PersonalInformation personalInformation,
        Product product
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdditionalSetup(String currency, String userCountry) {}

    /**
     * 1SB distributor block. Saving Get-quote schema names the agent field {@code agentId};
     * Term fixtures historically used {@code agentID}. Live demo (2026-09-11): Saving
     * Multi-Quote with only {@code agentID} returns {@code INSGW_NO_VALID_PRODUCT_FOUND};
     * {@code agentId} succeeds. Term accepts {@code agentId} alone.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Distributor(
            String distributorID,
            @JsonProperty("agentId") String agentID,
            String channelType,
            String salesChannel
    ) {
        @JsonGetter("agentID")
        public String agentIDAlias() {
            return agentID;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PersonalInformation(List<IndividualDetail> individualDetails) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndividualDetail(
            String memberType,
            int memberSequenceNumber,
            String gender,
            String dateOfBirth,
            String tobacco,
            BigDecimal annualIncome,
            String zipCode,
            BigDecimal quoteAmount
    ) {}

    /**
     * Single Quote pin. 1SB requires {@code insuranceCompanyCode} + {@code productCode[]}
     * (not bank {@code manufacturerId}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InsuranceAndProduct(
            String insuranceCompanyCode,
            List<String> productCode
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OptionRef(@JsonProperty("OptionSelected") String optionSelected) {}

    /**
     * @param productType          1SB LOB family token ({@code LifeTerm}, {@code LifeSave}, …)
     * @param savingsProductType   Saving filters; use {@code ULIP} for ULIP quotes on the lifesave API
     * @param product              Legacy alias some Term fixtures used ({@code product.product}); prefer {@code productType}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Product(
            String productType,
            List<String> savingsProductType,
            String product,
            List<InsuranceAndProduct> insuranceAndProducts,
            OptionRef planOption,
            OptionRef coverOption,
            @JsonProperty("DBPoption") OptionRef deathBenefitOption,
            Integer policyTerm,
            Integer premiumPaymentTerm,
            String premiumPaymentFrequency,
            String premiumPaymentOption
    ) {
        public static Product term(String token) {
            return new Product(token, null, token, null, null, null, null, null, null, null, null);
        }

        public static Product saving(String token, List<String> savingsTypes) {
            return new Product(token, savingsTypes, null, null, null, null, null, null, null, null, null);
        }

        public Product withPin(
                List<InsuranceAndProduct> pin,
                OptionRef plan,
                OptionRef cover,
                OptionRef dbp,
                Integer policyTerm,
                Integer ppt,
                String frequency,
                String payOption) {
            return new Product(
                    productType, savingsProductType, product,
                    pin, plan, cover, dbp, policyTerm, ppt, frequency, payOption);
        }
    }
}
