package com.bank.insurance.onesb;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditEvent;
import com.bank.common.audit.AuditEventPublisher;
import com.bank.common.audit.AuditOutcomes;
import com.bank.common.error.ErrorCodes;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FUNC-005 IT: {@code POST /v1/proposals} — agent attribution, consent WARN,
 * success 201, idempotency, business reject.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("FUNC-005")
@Tag("integration")
class ProposalSubmitIT {

    private static final String TERM_PROPOSAL_PATH = "/insurance/lifeterm/v1/proposal";

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
        registry.add("onesb.poll.base-delay-ms", () -> "1");
        registry.add("onesb.poll.max-delay-ms", () -> "5");
        registry.add("onesb.poll.max-attempts", () -> "3");
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditEventPublisher auditEventPublisher;

    @BeforeEach
    void resetStubs() {
        ONESB.resetAll();
        PERSISTENCE.resetAll();
        stubPersistenceHappyPath("job-prop-default");
    }

    @Test
    void ac1_missingAgentId_returns422_andNeverCallsOneSb() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/proposals")
                        .header("Idempotency-Key", "idem-ac1-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "journeyId": "j-ac1",
                                  "consentRef": "consent-ac1",
                                  "values": { "proposer.panNumber": "ABCDE1234F" }
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is(ErrorCodes.AGENT_ATTRIBUTION_MISSING)));

        ONESB.verify(0, postRequestedFor(urlEqualTo(TERM_PROPOSAL_PATH)));
        PERSISTENCE.verify(0, postRequestedFor(urlEqualTo("/internal/v1/jobs")));
    }

    @Test
    @Tag("FUNC-025")
    void emptyValues_returns422_andNeverCallsOneSb() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/proposals")
                        .header("Idempotency-Key", "idem-empty-" + UUID.randomUUID())
                        .header("X-Actor-Id", "rm-empty")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "journeyId": "j-empty",
                                  "consentRef": "consent-empty",
                                  "agentId": "109337",
                                  "productCode": "T1",
                                  "manufacturerId": "HDFC",
                                  "values": {}
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is(ErrorCodes.VALIDATION_ERROR)));

        ONESB.verify(0, postRequestedFor(urlEqualTo(TERM_PROPOSAL_PATH)));
        PERSISTENCE.verify(0, postRequestedFor(urlEqualTo("/internal/v1/jobs")));
    }

    @Test
    void ac2_missingConsentRef_auditsWarn_andStillSubmits() throws Exception {
        String jobId = "job-ac2-" + UUID.randomUUID();
        stubPersistenceHappyPath(jobId);
        ONESB.stubFor(post(urlEqualTo(TERM_PROPOSAL_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reqId\":\"REQ-AC2\",\"data\":{}}")));
        stubOneSbProposalPoll("REQ-AC2");

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/proposals")
                        .header("Idempotency-Key", "idem-ac2-" + UUID.randomUUID())
                        .header("X-Actor-Id", "rm-ac2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBodyWithoutConsent()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalJobId", is(jobId)))
                .andExpect(jsonPath("$.status", is("PENDING")));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventPublisher, atLeastOnce()).publish(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(e -> AuditActions.CONSENT_REF_MISSING.equals(e.getAction())
                        && AuditOutcomes.WARN.equals(e.getOutcome()));

        ONESB.verify(exactly(1), postRequestedFor(urlEqualTo(TERM_PROPOSAL_PATH))
                .withRequestBody(matchingJsonPath("$.distributor.distributorID",
                        containing("TEST_DIST"))));
    }

    @Test
    void ac3_success_returns201_withJobIdAndStatus() throws Exception {
        String jobId = "job-ac3-" + UUID.randomUUID();
        stubPersistenceHappyPath(jobId);
        ONESB.stubFor(post(urlEqualTo(TERM_PROPOSAL_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"applicationNumber\":\"APP-AC3\",\"reqId\":\"REQ-AC3\"}")));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/proposals")
                        .header("Idempotency-Key", "idem-ac3-" + UUID.randomUUID())
                        .header("X-Actor-Id", "rm-ac3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalJobId", is(jobId)))
                .andExpect(jsonPath("$.jobId", is(jobId)))
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        ONESB.verify(exactly(1), postRequestedFor(urlEqualTo(TERM_PROPOSAL_PATH)));
        PERSISTENCE.verify(exactly(1), postRequestedFor(urlEqualTo("/internal/v1/jobs")));
    }

    @Test
    void ac4_idempotentReplay_doesNotReinvokeOneSb() throws Exception {
        String jobId = "job-replay-" + UUID.randomUUID();
        String key = "idem-replay-" + UUID.randomUUID();
        stubPersistenceHappyPath(jobId);
        ONESB.stubFor(post(urlEqualTo(TERM_PROPOSAL_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reqId\":\"REQ-REPLAY\",\"data\":{}}")));
        stubOneSbProposalPoll("REQ-REPLAY");

        String body = validBody();
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/proposals")
                        .header("Idempotency-Key", key)
                        .header("X-Actor-Id", "rm-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalJobId", is(jobId)));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/proposals")
                        .header("Idempotency-Key", key)
                        .header("X-Actor-Id", "rm-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalJobId", is(jobId)));

        ONESB.verify(exactly(1), postRequestedFor(urlEqualTo(TERM_PROPOSAL_PATH)));
    }

    @Test
    void r6_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(ErrorCodes.MISSING_IDEMPOTENCY_KEY)));

        ONESB.verify(0, postRequestedFor(urlEqualTo(TERM_PROPOSAL_PATH)));
    }

    @Test
    void ac5_businessReject_returns422_proposalRejected_andAudits() throws Exception {
        String jobId = "job-rej-" + UUID.randomUUID();
        stubPersistenceHappyPath(jobId);
        ONESB.stubFor(post(urlEqualTo(TERM_PROPOSAL_PATH))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "errors": [{
                                    "field": "proposer.panNumber",
                                    "message": "UW declined",
                                    "code": "UW_DECLINE"
                                  }]
                                }
                                """)));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/proposals")
                        .header("Idempotency-Key", "idem-ac5-" + UUID.randomUUID())
                        .header("X-Actor-Id", "rm-ac5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is(ErrorCodes.PROPOSAL_REJECTED)));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventPublisher, atLeastOnce()).publish(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(e -> AuditActions.PROPOSAL_SUBMITTED.equals(e.getAction())
                        && AuditOutcomes.REJECTED.equals(e.getOutcome()));
    }

    private static String validBody() {
        return """
                {
                  "lob": "TERM",
                  "journeyId": "j-prop-1",
                  "schemaId": "scm-term-1",
                  "offerId": "off-1",
                  "productCode": "T1",
                  "manufacturerId": "HDFC",
                  "version": "1",
                  "consentRef": "consent-1",
                  "agentId": "109337",
                  "values": {
                    "proposer.panNumber": "ABCDE1234F",
                    "nominee.name": "Jane Doe"
                  },
                  "distribution": { "rmEmployeeId": "E123", "channelType": "B2B" }
                }
                """;
    }

    private static String validBodyWithoutConsent() {
        return """
                {
                  "lob": "TERM",
                  "journeyId": "j-prop-2",
                  "agentId": "109337",
                  "values": { "proposer.panNumber": "ABCDE1234F" },
                  "distribution": { "rmEmployeeId": "E123" }
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
                                  "jobType": "PROPOSAL",
                                  "lob": "TERM",
                                  "status": "PENDING",
                                  "journeyId": "j-prop-1",
                                  "idempotencyKey": "idem",
                                  "createdAt": "2026-07-30T12:00:00Z",
                                  "updatedAt": "2026-07-30T12:00:00Z",
                                  "version": 0,
                                  "createdByActor": "ProposalSubmitIT"
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

    private static void stubOneSbProposalPoll(String reqId) {
        ONESB.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .get(urlEqualTo("/insurance/lifeterm/v1/proposal/poll/" + reqId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "data": {
                                    "isPollComplete": true,
                                    "applicationNumber": "APP-POLL"
                                  }
                                }
                                """)));
    }
}
