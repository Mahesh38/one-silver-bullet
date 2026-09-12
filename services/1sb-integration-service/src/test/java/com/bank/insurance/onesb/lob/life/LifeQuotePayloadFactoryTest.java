package com.bank.insurance.onesb.lob.life;

import com.bank.common.secrets.SecretProvider;
import com.bank.insurance.onesb.domain.command.CreateQuoteCommand;
import com.bank.common.domain.Lob;
import com.bank.insurance.onesb.lob.life.payload.LifeQuoteRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("FUNC-022")
class LifeQuotePayloadFactoryTest {

    @Test
    void resolveTypeOfQuote_mapsBankModes() {
        assertThat(LifeQuotePayloadFactory.resolveTypeOfQuote(null)).isEqualTo("Multi-Quote");
        assertThat(LifeQuotePayloadFactory.resolveTypeOfQuote("MULTI")).isEqualTo("Multi-Quote");
        assertThat(LifeQuotePayloadFactory.resolveTypeOfQuote("SINGLE")).isEqualTo("Single Quote");
        assertThat(LifeQuotePayloadFactory.resolveTypeOfQuote("single quote")).isEqualTo("Single Quote");
        assertThat(LifeQuotePayloadFactory.resolveTypeOfQuote("SQ")).isEqualTo("Single Quote");
        assertThat(LifeQuotePayloadFactory.isSingleQuote("SINGLE")).isTrue();
        assertThat(LifeQuotePayloadFactory.isSingleQuote("MULTI")).isFalse();
    }

    @Test
    void build_singleQuote_pinsInsuranceCompanyCodeAndProductCodes() {
        SecretProvider secrets = mock(SecretProvider.class);
        when(secrets.getDistributorId()).thenReturn("BCIBL");
        CreateQuoteCommand command = new CreateQuoteCommand(
                Lob.TERM, "SINGLE", "SUM_ASSURED", new BigDecimal("5000000"), null,
                List.of(new CreateQuoteCommand.MemberDetail(
                        "LIFE_ASSURED", 1, "1990-01-15", "M", false,
                        new BigDecimal("1000000"), "400001")),
                null,
                new CreateQuoteCommand.DistributionContext(null, "109337", "B2B"),
                "j-1", null, "idem", "actor",
                new CreateQuoteCommand.ProductSelection(
                        "BALIC", List.of("345"), "P1", null, null, 20, 15, "Y", "2")
        );

        LifeQuoteRequest payload = LifeQuotePayloadFactory.build(
                command, secrets, LifeQuoteRequest.Product.term("LifeTerm"));

        assertThat(payload.typeOfQuote()).isEqualTo("Single Quote");
        assertThat(payload.product().insuranceAndProducts()).hasSize(1);
        assertThat(payload.product().insuranceAndProducts().getFirst().insuranceCompanyCode())
                .isEqualTo("BALIC");
        assertThat(payload.product().insuranceAndProducts().getFirst().productCode())
                .containsExactly("345");
        assertThat(payload.product().planOption().optionSelected()).isEqualTo("P1");
        assertThat(payload.product().policyTerm()).isEqualTo(20);
        assertThat(payload.product().premiumPaymentTerm()).isEqualTo(15);
        assertThat(payload.product().premiumPaymentFrequency()).isEqualTo("Y");
    }

    @Test
    void build_multiQuote_doesNotEmitPin() {
        SecretProvider secrets = mock(SecretProvider.class);
        when(secrets.getDistributorId()).thenReturn("BCIBL");
        CreateQuoteCommand command = new CreateQuoteCommand(
                Lob.TERM, "MULTI", "SUM_ASSURED", new BigDecimal("5000000"), null,
                List.of(new CreateQuoteCommand.MemberDetail(
                        "LIFE_ASSURED", 1, "1990-01-15", "M", false, null, "400001")),
                null,
                new CreateQuoteCommand.DistributionContext(null, "109337", "B2B"),
                "j-1", null, "idem", "actor"
        );

        LifeQuoteRequest payload = LifeQuotePayloadFactory.build(
                command, secrets, LifeQuoteRequest.Product.term("LifeTerm"));

        assertThat(payload.typeOfQuote()).isEqualTo("Multi-Quote");
        assertThat(payload.product().insuranceAndProducts()).isNull();
    }
}
