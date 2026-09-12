package com.bank.insurance.onesb;

import com.bank.common.error.ErrorCodes;
import com.bank.insurance.onesb.adapter.onesb.polling.AsyncJobPoller;
import com.bank.common.domain.JobStatus;
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
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FUNC-002 IT: MockMvc + dual WireMock (1SB + bank persistence) for quote create ACs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("FUNC-002")
@Tag("integration")
class QuoteCreateIT {

    private static final String TERM_QUOTE_PATH = "/insurance/lifeterm/v1/quote";
    private static final String TERM_POLL_PREFIX = "/insurance/lifeterm/v1/quote/poll/";

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
        registry.add("onesb.poll.base-delay-ms", () -> "1");
        registry.add("onesb.poll.max-delay-ms", () -> "5");
        registry.add("onesb.poll.max-attempts", () -> "5");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AsyncJobPoller asyncJobPoller;

    @BeforeEach
    void resetStubs() {
        ONESB.resetAll();
        PERSISTENCE.resetAll();
        stubPersistenceHappyPath("job-it-default");
    }

    @Test
    void ac1_validTermQuote_createsJob_andPostsToOneSb() throws Exception {
        String jobId = "job-ac1-" + UUID.randomUUID();
        stubPersistenceHappyPath(jobId);
        ONESB.stubFor(post(urlEqualTo(TERM_QUOTE_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reqId\":\"REQ-AC1\",\"data\":{}}")));
        stubOneSbPollComplete("REQ-AC1");

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/quotes")
                        .header("Idempotency-Key", "idem-ac1-" + UUID.randomUUID())
                        .header("X-Actor-Id", "rm-ac1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId", is(jobId)))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.reqId").doesNotExist());

        ONESB.verify(exactly(1), postRequestedFor(urlEqualTo(TERM_QUOTE_PATH)));
        PERSISTENCE.verify(exactly(1), postRequestedFor(urlEqualTo("/internal/v1/jobs")));
    }

    @Test
    @Tag("FUNC-022")
    void singleQuote_withoutPin_returns422_andNeverCallsOneSb() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/quotes")
                        .header("Idempotency-Key", "idem-sq-missing-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "mode": "SINGLE",
                                  "sumAssured": 5000000,
                                  "members": [{ "dob": "1990-01-15", "gender": "M" }],
                                  "distribution": { "agentId": "109337" }
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is(ErrorCodes.VALIDATION_ERROR)));

        ONESB.verify(0, postRequestedFor(urlEqualTo(TERM_QUOTE_PATH)));
        PERSISTENCE.verify(0, postRequestedFor(urlEqualTo("/internal/v1/jobs")));
    }

    @Test
    @Tag("FUNC-022")
    void singleQuote_withPin_postsTypeOfQuoteSingleQuote() throws Exception {
        String jobId = "job-sq-" + UUID.randomUUID();
        stubPersistenceHappyPath(jobId);
        ONESB.stubFor(post(urlEqualTo(TERM_QUOTE_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reqId\":\"REQ-SQ\",\"data\":{}}")));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/quotes")
                        .header("Idempotency-Key", "idem-sq-" + UUID.randomUUID())
                        .header("X-Actor-Id", "rm-sq")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "mode": "SINGLE",
                                  "journeyId": "j-sq",
                                  "sumAssured": 5000000,
                                  "members": [{ "dob": "1990-01-15", "gender": "M" }],
                                  "distribution": { "agentId": "109337" },
                                  "selection": {
                                    "insurerCode": "BALIC",
                                    "productCodes": ["345"],
                                    "policyTerm": 20,
                                    "premiumPaymentTerm": 15,
                                    "premiumFrequency": "Y"
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId", is(jobId)));

        ONESB.verify(exactly(1), postRequestedFor(urlEqualTo(TERM_QUOTE_PATH))
                .withRequestBody(matchingJsonPath("$.typeOfQuote",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("Single Quote")))
                .withRequestBody(matchingJsonPath(
                        "$.product.insuranceAndProducts[0].insuranceCompanyCode",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("BALIC")))
                .withRequestBody(matchingJsonPath(
                        "$.product.insuranceAndProducts[0].productCode[0]",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("345"))));
    }

    @Test
    void ac2_invalidBody_returns422_andNeverCallsOneSb() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/quotes")
                        .header("Idempotency-Key", "idem-ac2-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "sumAssured": 5000000,
                                  "members": []
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is(ErrorCodes.VALIDATION_ERROR)));

        ONESB.verify(0, postRequestedFor(urlEqualTo(TERM_QUOTE_PATH)));
        PERSISTENCE.verify(0, postRequestedFor(urlEqualTo("/internal/v1/jobs")));
    }

    @Test
    void ac3_poller_pendingThenComplete_viaWireMockAdapter() {
        String reqId = "REQ-AC3";
        ONESB.stubFor(get(urlEqualTo(TERM_POLL_PREFIX + reqId))
                .inScenario("ac3")
                .whenScenarioStateIs("Started")
                .willSetStateTo("done")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"isPollComplete\":false}}")));
        ONESB.stubFor(get(urlEqualTo(TERM_POLL_PREFIX + reqId))
                .inScenario("ac3")
                .whenScenarioStateIs("done")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "data": {
                                    "isPollComplete": true,
                                    "quote": [{
                                      "offerId": "off-ac3",
                                      "insurerCode": "HDFC",
                                      "productCode": "T1",
                                      "productName": "Term",
                                      "premiumAmount": 4200,
                                      "sumAssured": 5000000
                                    }]
                                  }
                                }
                                """)));

        stubPersistenceHappyPath("job-ac3");

        AsyncJobPoller.PollOutcome outcome =
                asyncJobPoller.pollQuoteUntilDone("job-ac3", "TERM", reqId);

        assertThat(outcome.terminalStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(outcome.pollAttempts()).isGreaterThanOrEqualTo(2);
        PERSISTENCE.verify(postRequestedFor(urlMatching("/internal/v1/jobs/job-ac3/offers")));
    }

    @Test
    void r6_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(ErrorCodes.MISSING_IDEMPOTENCY_KEY)));

        ONESB.verify(0, postRequestedFor(urlEqualTo(TERM_QUOTE_PATH)));
    }

    @Test
    void r6_idempotentReplay_doesNotReinvokeOneSb() throws Exception {
        String jobId = "job-replay-" + UUID.randomUUID();
        String key = "idem-replay-" + UUID.randomUUID();
        stubPersistenceHappyPath(jobId);
        ONESB.stubFor(post(urlEqualTo(TERM_QUOTE_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reqId\":\"REQ-REPLAY\",\"data\":{}}")));
        stubOneSbPollComplete("REQ-REPLAY");

        String body = validBody();
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/quotes")
                        .header("Idempotency-Key", key)
                        .header("X-Actor-Id", "rm-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId", is(jobId)));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/quotes")
                        .header("Idempotency-Key", key)
                        .header("X-Actor-Id", "rm-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId", is(jobId)));

        ONESB.verify(exactly(1), postRequestedFor(urlEqualTo(TERM_QUOTE_PATH)));
    }

    private static String validBody() {
        return """
                {
                  "lob": "TERM",
                  "journeyId": "j-it-1",
                  "sumAssured": 5000000,
                  "members": [{ "dob": "1990-01-15", "gender": "M" }],
                  "distribution": { "agentId": "109337" }
                }
                """;
    }

    private static void stubPersistenceHappyPath(String jobId) {
        PERSISTENCE.stubFor(post(urlEqualTo("/internal/v1/jobs"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "jobId": "%s",
                                  "jobType": "QUOTE",
                                  "lob": "TERM",
                                  "status": "PENDING",
                                  "journeyId": "j-it-1",
                                  "idempotencyKey": "idem",
                                  "createdAt": "2026-07-30T12:00:00Z",
                                  "updatedAt": "2026-07-30T12:00:00Z",
                                  "version": 0,
                                  "createdByActor": "QuoteCreateIT"
                                }
                                """.formatted(jobId))));

        PERSISTENCE.stubFor(patch(urlPathMatching("/internal/v1/jobs/.*/status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"jobId\":\"" + jobId + "\",\"status\":\"RUNNING\"}")));

        PERSISTENCE.stubFor(post(urlPathMatching("/internal/v1/jobs/.*/poll-attempts"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"attemptId\":1}")));

        PERSISTENCE.stubFor(post(urlPathMatching("/internal/v1/jobs/.*/offers"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"offerId\":\"o1\"}")));
    }

    private static void stubOneSbPollComplete(String reqId) {
        ONESB.stubFor(get(urlEqualTo(TERM_POLL_PREFIX + reqId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "data": {
                                    "isPollComplete": true,
                                    "quote": [{
                                      "offerId": "off-bg",
                                      "insurerCode": "ICICI",
                                      "productCode": "P1",
                                      "productName": "Term",
                                      "premiumAmount": 1000,
                                      "sumAssured": 5000000
                                    }]
                                  }
                                }
                                """)));
    }
}
