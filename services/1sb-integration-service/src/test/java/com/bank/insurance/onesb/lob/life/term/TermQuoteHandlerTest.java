package com.bank.insurance.onesb.lob.life.term;

import com.bank.common.domain.Lob;
import com.bank.common.secrets.SecretProvider;
import com.bank.insurance.onesb.domain.command.CreateQuoteCommand;
import com.bank.insurance.onesb.lob.life.payload.LifeQuoteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("FUNC-002")
class TermQuoteHandlerTest {

    private TermQuoteHandler handler;

    @BeforeEach
    void setUp() {
        SecretProvider secrets = mock(SecretProvider.class);
        when(secrets.getDistributorId()).thenReturn("TEST_DIST");
        handler = new TermQuoteHandler(secrets);
    }

    @Test
    void supportedLob_isTerm() {
        assertThat(handler.supportedLob()).isEqualTo(Lob.TERM);
    }

    @Test
    void submitAndPollPaths() {
        assertThat(handler.submitPath()).isEqualTo("/insurance/lifeterm/v1/quote");
        assertThat(handler.pollPath("REQ-1")).isEqualTo("/insurance/lifeterm/v1/quote/poll/REQ-1");
    }

    @Test
    void buildSubmitPayload_mapsMinimalTermFieldsToTypedModel() {
        CreateQuoteCommand command = new CreateQuoteCommand(
                Lob.TERM, "MULTI", "SUM_ASSURED", new BigDecimal("5000000"), null,
                List.of(new CreateQuoteCommand.MemberDetail(
                        "LIFE_ASSURED", 1, "1990-01-15", "M", true,
                        new BigDecimal("1200000"), "400001")),
                null,
                new CreateQuoteCommand.DistributionContext(null, "109337", "B2B"),
                "j-1", null, "idem", "actor"
        );

        LifeQuoteRequest payload = handler.buildSubmitPayload(command);

        assertThat(payload.typeOfQuote()).isEqualTo("Multi-Quote");
        assertThat(payload.quoteCategory()).isEqualTo("Sum Assured");
        assertThat(payload.distributor().distributorID()).isEqualTo("TEST_DIST");
        assertThat(payload.distributor().agentID()).isEqualTo("109337");
        assertThat(payload.distributor().channelType()).isEqualTo("B2B");
        assertThat(payload.distributor().salesChannel()).isEqualTo("Online");
        assertThat(payload.personalInformation().individualDetails()).hasSize(1);
        LifeQuoteRequest.IndividualDetail member = payload.personalInformation().individualDetails().getFirst();
        assertThat(member.dateOfBirth()).isEqualTo("1990-01-15");
        assertThat(member.gender()).isEqualTo("Male");
        assertThat(member.tobacco()).isEqualTo("Yes");
        assertThat(member.quoteAmount()).isEqualTo(new BigDecimal("5000000"));
        assertThat(member.zipCode()).isEqualTo("400001");
        assertThat(payload.product().productType()).isEqualTo("LifeTerm");
    }

    @Test
    @Tag("FUNC-022")
    void buildSubmitPayload_singleQuote_emitsPin() {
        CreateQuoteCommand command = new CreateQuoteCommand(
                Lob.TERM, "SINGLE", "SUM_ASSURED", new BigDecimal("5000000"), null,
                List.of(new CreateQuoteCommand.MemberDetail(
                        "LIFE_ASSURED", 1, "1990-01-15", "M", false,
                        new BigDecimal("1200000"), "400001")),
                null,
                new CreateQuoteCommand.DistributionContext(null, "109337", "B2B"),
                "j-1", null, "idem", "actor",
                new CreateQuoteCommand.ProductSelection(
                        "BALIC", List.of("345"), null, null, null, 20, 15, "Y", null)
        );

        LifeQuoteRequest payload = handler.buildSubmitPayload(command);
        assertThat(payload.typeOfQuote()).isEqualTo("Single Quote");
        assertThat(payload.product().insuranceAndProducts().getFirst().insuranceCompanyCode())
                .isEqualTo("BALIC");
        assertThat(handler.criteriaPath("345", "BALIC"))
                .isEqualTo("/insurance/lifeterm/v1/quote/gateCriteria?productId=345&manufacturerId=BALIC");
    }
}
