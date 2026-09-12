package com.bank.insurance.onesb;

import com.bank.common.error.ErrorCodes;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FUNC-023: bank {@code GET/POST /v1/quotes/criteria} → 1SB {@code gateCriteria}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("FUNC-023")
@Tag("integration")
class QuoteCriteriaIT {

    private static final String GATE_PATH = "/insurance/lifeterm/v1/quote/gateCriteria";

    private static final WireMockServer ONESB = new WireMockServer(wireMockConfig().dynamicPort());
    private static final WireMockServer PERSISTENCE = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        ONESB.start();
        PERSISTENCE.start();
    }

    @AfterAll
    static void stopWireMocks() {
        ONESB.stop();
        PERSISTENCE.stop();
    }

    @DynamicPropertySource
    static void bindWireMockBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("onesb.client.base-url", ONESB::baseUrl);
        registry.add("bank.persistence.base-url", PERSISTENCE::baseUrl);
        registry.add("onesb.distributor-id", () -> "TEST_DIST");
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetStubs() {
        ONESB.resetAll();
        PERSISTENCE.resetAll();
    }

    @Test
    void getCriteria_literalPathWinsOverJobId_andCallsGateCriteria() throws Exception {
        ONESB.stubFor(get(urlPathEqualTo(GATE_PATH))
                .withQueryParam("productId", equalTo("345"))
                .withQueryParam("manufacturerId", equalTo("BALIC"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"fieldGroups\":[{\"name\":\"gate\"}]}")));

        mockMvc.perform(MockMvcRequestBuilders.get("/v1/quotes/criteria")
                        .param("lob", "TERM")
                        .param("productCode", "345")
                        .param("manufacturerId", "BALIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lob", is("TERM")))
                .andExpect(jsonPath("$.productCode", is("345")))
                .andExpect(jsonPath("$.manufacturerId", is("BALIC")));

        ONESB.verify(exactly(1), getRequestedFor(urlPathEqualTo(GATE_PATH))
                .withQueryParam("productId", equalTo("345"))
                .withQueryParam("manufacturerId", equalTo("BALIC")));
    }

    @Test
    void postCriteria_requiresIdempotencyKey_andPostsToOneSb() throws Exception {
        ONESB.stubFor(post(urlPathEqualTo(GATE_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reqId\":\"REQ-GATE\"}")));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/quotes/criteria")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(ErrorCodes.MISSING_IDEMPOTENCY_KEY)));
        ONESB.verify(0, postRequestedFor(urlPathEqualTo(GATE_PATH)));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/quotes/criteria")
                        .header("Idempotency-Key", "idem-crit-" + UUID.randomUUID())
                        .header("X-Actor-Id", "rm-crit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted", is(true)))
                .andExpect(jsonPath("$.reqId", is("REQ-GATE")));

        ONESB.verify(exactly(1), postRequestedFor(urlPathEqualTo(GATE_PATH))
                .withRequestBody(matchingJsonPath("$.distributor.distributorID"))
                .withRequestBody(matchingJsonPath("$.occupation", equalTo("SALARIED"))));
    }

    private static String submitBody() {
        return """
                {
                  "lob": "TERM",
                  "productCode": "345",
                  "manufacturerId": "BALIC",
                  "agentId": "109337",
                  "values": { "occupation": "SALARIED" }
                }
                """;
    }
}
