package com.bank.insurance.onesb.application;

import com.bank.insurance.onesb.TestErrors;
import com.bank.common.domain.EligibilitySubmitResult;
import com.bank.common.domain.Lob;
import com.bank.common.domain.ProposalSchema;
import com.bank.common.error.ErrorCodes;
import com.bank.common.error.ServiceException;
import com.bank.common.secrets.SecretProvider;
import com.bank.insurance.onesb.domain.port.outbound.OneSbEligibilityPort;
import com.bank.insurance.onesb.lob.LobQuoteHandler;
import com.bank.insurance.onesb.lob.LobQuoteHandlerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("FUNC-023")
@ExtendWith(MockitoExtension.class)
class EligibilityServiceTest {

    @Mock OneSbEligibilityPort eligibilityPort;
    @Mock SecretProvider secretProvider;
    @Mock LobQuoteHandler termHandler;

    private EligibilityService service;

    @BeforeEach
    void setUp() {
        when(termHandler.supportedLob()).thenReturn(Lob.TERM);
        LobQuoteHandlerRegistry registry = new LobQuoteHandlerRegistry(List.of(termHandler), TestErrors.ONESB);
        service = new EligibilityService(registry, eligibilityPort, secretProvider, TestErrors.ONESB);
    }

    @Test
    void getCriteria_usesHandlerPath() {
        when(termHandler.criteriaPath("345", "BALIC"))
                .thenReturn("/insurance/lifeterm/v1/quote/gateCriteria?productId=345&manufacturerId=BALIC");
        ProposalSchema schema = new ProposalSchema(Lob.TERM, "345", "BALIC", null, Map.of("ok", true));
        when(eligibilityPort.getCriteria(eq(Lob.TERM), eq("345"), eq("BALIC"), any())).thenReturn(schema);

        assertThat(service.getCriteria(Lob.TERM, "345", "BALIC")).isSameAs(schema);
        verify(eligibilityPort).getCriteria(
                Lob.TERM, "345", "BALIC",
                "/insurance/lifeterm/v1/quote/gateCriteria?productId=345&manufacturerId=BALIC");
    }

    @Test
    void getCriteria_missingProduct_throws422_noUpstream() {
        assertThatThrownBy(() -> service.getCriteria(Lob.TERM, " ", "BALIC"))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getErrorResponse().getCode())
                        .isEqualTo(ErrorCodes.VALIDATION_ERROR));
        verify(eligibilityPort, never()).getCriteria(any(), any(), any(), any());
    }

    @Test
    void submitCriteria_postsTypedBody() {
        when(termHandler.criteriaPath("345", "BALIC"))
                .thenReturn("/insurance/lifeterm/v1/quote/gateCriteria?productId=345&manufacturerId=BALIC");
        when(secretProvider.getDistributorId()).thenReturn("BCIBL");
        EligibilitySubmitResult result = new EligibilitySubmitResult(true, "REQ-C", "ACCEPTED");
        when(eligibilityPort.submit(any(), any())).thenReturn(result);

        EligibilitySubmitResult out = service.submitCriteria(
                Lob.TERM, "345", "BALIC", Map.of("field", "v"), "109337", "B2B", "rm-1");

        assertThat(out).isSameAs(result);
        verify(eligibilityPort).submit(eq(
                "/insurance/lifeterm/v1/quote/gateCriteria?productId=345&manufacturerId=BALIC"), any());
    }
}
