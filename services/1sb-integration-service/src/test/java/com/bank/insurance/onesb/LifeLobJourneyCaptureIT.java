package com.bank.insurance.onesb;

import com.bank.insurance.onesb.adapter.onesb.polling.AsyncJobPoller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA evidence: capture bank-facing and outbound 1SB HTTP for Term / Saving / ULIP
 * quote → poll → proposal schema → proposal submit, plus Single Quote pin and
 * bank criteria → 1SB {@code gateCriteria}.
 * <p>
 * Does not call the live 1SB sandbox (TESTING-RULES R4). Writes JSON + markdown under
 * {@code LIFE_JOURNEY_CAPTURE_DIR} or {@code build/reports/life-journey-capture}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("QA-012")
@Tag("integration")
class LifeLobJourneyCaptureIT {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path CAPTURE_DIR = Path.of(
            System.getenv().getOrDefault("LIFE_JOURNEY_CAPTURE_DIR",
                    "build/reports/life-journey-capture"));

    private static final WireMockServer ONESB = new WireMockServer(wireMockConfig().dynamicPort());
    private static final WireMockServer PERSISTENCE = new WireMockServer(wireMockConfig().dynamicPort());
    private static final List<Map<String, Object>> LOB_REPORTS = new ArrayList<>();

    static {
        ONESB.start();
        PERSISTENCE.start();
    }

    @AfterAll
    static void stopAndWriteIndex() throws Exception {
        writeIndex();
        ONESB.stop();
        PERSISTENCE.stop();
    }

    @DynamicPropertySource
    static void bindWireMockBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("onesb.client.base-url", ONESB::baseUrl);
        registry.add("bank.persistence.base-url", PERSISTENCE::baseUrl);
        registry.add("onesb.poll.base-delay-ms", () -> "1");
        registry.add("onesb.poll.max-delay-ms", () -> "5");
        registry.add("onesb.poll.max-attempts", () -> "3");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AsyncJobPoller asyncJobPoller;

    @BeforeEach
    void resetStubs() {
        ONESB.resetAll();
        PERSISTENCE.resetAll();
    }

    @Test
    void termSavingUlip_captureQuotePollProposal_andRecordUnwiredSteps() throws Exception {
        Files.createDirectories(CAPTURE_DIR);
        for (String lob : List.of("TERM", "SAVING", "ULIP")) {
            LOB_REPORTS.add(captureLob(lob));
        }
        writeIndex();
        assertThat(LOB_REPORTS).hasSize(3);
        for (Map<String, Object> report : LOB_REPORTS) {
            assertThat(report.get("multiQuoteWired")).isEqualTo(true);
            assertThat(report.get("mqPollWired")).isEqualTo(true);
            assertThat(report.get("getProposalWired")).isEqualTo(true);
            assertThat(report.get("submitProposalWired")).isEqualTo(true);
            assertThat(report.get("getCriteriaWired")).isEqualTo(true);
            assertThat(report.get("singleQuoteWired")).isEqualTo(true);
            JsonNode quoteReq = MAPPER.valueToTree(report.get("onesbQuoteRequest"));
            assertThat(quoteReq.path("typeOfQuote").asText()).isEqualTo("Multi-Quote");
            JsonNode sqReq = MAPPER.valueToTree(report.get("onesbSingleQuoteRequest"));
            assertThat(sqReq.path("typeOfQuote").asText()).isEqualTo("Single Quote");
            assertThat(sqReq.path("product").path("insuranceAndProducts").get(0)
                    .path("insuranceCompanyCode").asText()).isEqualTo("MFG");
        }
    }

    private Map<String, Object> captureLob(String lob) throws Exception {
        ONESB.resetAll();
        PERSISTENCE.resetAll();

        String quotePath = quotePath(lob);
        String pollPath = quotePath + "/poll/REQ-" + lob;
        String proposalPath = proposalPath(lob);
        String jobId = "job-" + lob.toLowerCase() + "-" + UUID.randomUUID();
        String reqId = "REQ-" + lob;

        stubPersistence(jobId, lob);
        stubQuote(quotePath, reqId);
        stubPoll(pollPath, lob);
        stubProposalSchema(proposalPath);
        stubProposalSubmit(proposalPath, lob);
        stubCriteria(lob);

        String bankQuoteReq = quoteBody(lob, false);
        MvcResult quoteResult = mockMvc.perform(MockMvcRequestBuilders.post("/v1/quotes")
                        .header("Idempotency-Key", "idem-" + lob + "-" + UUID.randomUUID())
                        .header("X-Actor-Id", "rm-capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankQuoteReq))
                .andExpect(status().isAccepted())
                .andReturn();

        String bankSqReq = quoteBody(lob, true);
        MvcResult sqResult = mockMvc.perform(MockMvcRequestBuilders.post("/v1/quotes")
                        .header("Idempotency-Key", "idem-sq-" + lob + "-" + UUID.randomUUID())
                        .header("X-Actor-Id", "rm-capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankSqReq))
                .andExpect(status().isAccepted())
                .andReturn();

        asyncJobPoller.pollQuoteUntilDone(jobId, lob, reqId);

        MvcResult schemaResult = mockMvc.perform(MockMvcRequestBuilders.get("/v1/proposals/schema")
                        .param("lob", lob)
                        .param("productCode", "P1")
                        .param("manufacturerId", "MFG")
                        .param("version", "1"))
                .andExpect(status().isOk())
                .andReturn();

        String bankProposalReq = proposalBody(lob);
        MvcResult submitResult = mockMvc.perform(MockMvcRequestBuilders.post("/v1/proposals")
                        .header("Idempotency-Key", "idem-p-" + lob + "-" + UUID.randomUUID())
                        .header("X-Actor-Id", "rm-capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankProposalReq))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult criteriaResult = mockMvc.perform(MockMvcRequestBuilders.get("/v1/quotes/criteria")
                        .param("lob", lob)
                        .param("productCode", "P1")
                        .param("manufacturerId", "MFG"))
                .andExpect(status().isOk())
                .andReturn();

        long criteriaHits = ONESB.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().contains("gateCriteria"))
                .count();

        Map<String, Object> onesbQuote = findServe(quotePath, "POST", "Multi-Quote");
        Map<String, Object> onesbSq = findServe(quotePath, "POST", "Single Quote");
        Map<String, Object> onesbPoll = findServe(pollPath, "GET");
        Map<String, Object> onesbSchema = findServe(proposalPath, "GET");
        Map<String, Object> onesbSubmit = findServe(proposalPath, "POST");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("lob", lob);
        report.put("multiQuoteWired", onesbQuote != null);
        report.put("mqPollWired", onesbPoll != null);
        report.put("getCriteriaWired", criteriaHits > 0 && criteriaResult.getResponse().getStatus() == 200);
        report.put("singleQuoteWired", onesbSq != null);
        report.put("singleQuoteNote",
                "mode=SINGLE + selection.insurerCode/productCodes → typeOfQuote=Single Quote and insuranceAndProducts pin");
        report.put("getCriteriaNote",
                "GET /v1/quotes/criteria → LOB handler gateCriteria path");
        report.put("criteriaBankStatus", criteriaResult.getResponse().getStatus());
        report.put("criteriaOneSbHits", criteriaHits);
        report.put("getProposalWired", onesbSchema != null);
        report.put("submitProposalWired", onesbSubmit != null);
        report.put("bankQuoteRequest", parseJson(bankQuoteReq));
        report.put("bankQuoteResponse", parseJson(quoteResult.getResponse().getContentAsString()));
        report.put("bankQuoteHttpStatus", quoteResult.getResponse().getStatus());
        report.put("bankSingleQuoteRequest", parseJson(bankSqReq));
        report.put("bankSingleQuoteResponse", parseJson(sqResult.getResponse().getContentAsString()));
        report.put("onesbQuoteRequest", onesbQuote != null ? onesbQuote.get("requestBody") : null);
        report.put("onesbQuoteResponse", onesbQuote != null ? onesbQuote.get("responseBody") : null);
        report.put("onesbSingleQuoteRequest", onesbSq != null ? onesbSq.get("requestBody") : null);
        report.put("onesbQuoteHttp", onesbQuote);
        report.put("onesbSingleQuoteHttp", onesbSq);
        report.put("onesbPollHttp", onesbPoll);
        report.put("bankProposalSchemaResponse", parseJson(schemaResult.getResponse().getContentAsString()));
        report.put("onesbProposalSchemaHttp", onesbSchema);
        report.put("bankProposalSubmitRequest", parseJson(bankProposalReq));
        report.put("bankProposalSubmitResponse", parseJson(submitResult.getResponse().getContentAsString()));
        report.put("onesbProposalSubmitHttp", onesbSubmit);

        Path file = CAPTURE_DIR.resolve("journey-" + lob.toLowerCase() + ".json");
        MAPPER.writeValue(file.toFile(), report);
        return report;
    }

    private Map<String, Object> findServe(String path, String method) {
        return findServe(path, method, null);
    }

    private Map<String, Object> findServe(String path, String method, String typeOfQuote) {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        for (ServeEvent event : ONESB.getAllServeEvents()) {
            LoggedRequest req = event.getRequest();
            if (!method.equalsIgnoreCase(req.getMethod().getName())) {
                continue;
            }
            String url = req.getUrl();
            if (!(url.equals(path) || url.startsWith(pathOnly))) {
                continue;
            }
            Object body = parseJson(req.getBodyAsString());
            if (typeOfQuote != null) {
                if (!(body instanceof JsonNode node)
                        || !typeOfQuote.equals(node.path("typeOfQuote").asText())) {
                    continue;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", method);
            row.put("url", url);
            row.put("requestBody", body);
            row.put("responseStatus", event.getResponse().getStatus());
            byte[] respBody = event.getResponse().getBody();
            String resp = respBody == null ? "" : new String(respBody, StandardCharsets.UTF_8);
            row.put("responseBody", parseJson(resp));
            return row;
        }
        return null;
    }

    private static Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, JsonNode.class);
        } catch (Exception e) {
            return raw;
        }
    }

    private static void stubQuote(String path, String reqId) {
        ONESB.stubFor(post(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reqId\":\"" + reqId + "\",\"data\":{}}")));
    }

    private static void stubPoll(String path, String lob) {
        ONESB.stubFor(get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "data": {
                                    "isPollComplete": true,
                                    "quote": [{
                                      "offerId": "off-%s",
                                      "insurerCode": "MFG",
                                      "productCode": "P1",
                                      "productName": "%s-Life",
                                      "premiumAmount": 1200,
                                      "sumAssured": 5000000
                                    }]
                                  }
                                }
                                """.formatted(lob, lob))));
    }

    private static void stubProposalSchema(String path) {
        ONESB.stubFor(get(urlPathEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "fieldGroups": [
                                    {"name":"personal","fields":[{"id":"proposer.panNumber","type":"string"}]}
                                  ],
                                  "version": "1"
                                }
                                """)));
    }

    private static void stubProposalSubmit(String path, String lob) {
        ONESB.stubFor(post(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"applicationNumber\":\"APP-" + lob + "\",\"reqId\":\"REQ-P-" + lob + "\"}")));
    }

    private static void stubCriteria(String lob) {
        String prefix = "TERM".equals(lob) ? "/insurance/lifeterm/v1" : "/insurance/lifesave/v1";
        ONESB.stubFor(get(urlPathEqualTo(prefix + "/quote/gateCriteria"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"fieldGroups\":[{\"name\":\"gate\"}]}")));
    }

    private static void stubPersistence(String jobId, String lob) {
        PERSISTENCE.stubFor(post(urlEqualTo("/internal/v1/jobs"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "jobId": "%s",
                                  "jobType": "QUOTE",
                                  "lob": "%s",
                                  "status": "PENDING",
                                  "journeyId": "j-capture",
                                  "idempotencyKey": "idem",
                                  "createdAt": "2026-09-11T12:00:00Z",
                                  "updatedAt": "2026-09-11T12:00:00Z",
                                  "version": 0,
                                  "createdByActor": "LifeLobJourneyCaptureIT"
                                }
                                """.formatted(jobId, lob))));
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

    private static String quotePath(String lob) {
        return "TERM".equals(lob) ? "/insurance/lifeterm/v1/quote" : "/insurance/lifesave/v1/quote";
    }

    private static String proposalPath(String lob) {
        return "TERM".equals(lob) ? "/insurance/lifeterm/v1/proposal" : "/insurance/lifesave/v1/proposal";
    }

    private static String quoteBody(String lob, boolean single) {
        if (single) {
            return """
                    {
                      "lob": "%s",
                      "mode": "SINGLE",
                      "category": "SUM_ASSURED",
                      "journeyId": "j-capture",
                      "sumAssured": 5000000,
                      "members": [{ "dob": "1990-01-15", "gender": "M", "pincode": "400001" }],
                      "distribution": { "agentId": "109337", "channelType": "B2B" },
                      "selection": { "insurerCode": "MFG", "productCodes": ["P1"] }
                    }
                    """.formatted(lob);
        }
        return """
                {
                  "lob": "%s",
                  "mode": "MULTI",
                  "category": "SUM_ASSURED",
                  "journeyId": "j-capture",
                  "sumAssured": 5000000,
                  "members": [{ "dob": "1990-01-15", "gender": "M", "pincode": "400001" }],
                  "distribution": { "agentId": "109337", "channelType": "B2B" }
                }
                """.formatted(lob);
    }

    private static String proposalBody(String lob) {
        return """
                {
                  "lob": "%s",
                  "journeyId": "j-capture",
                  "schemaId": "scm-1",
                  "offerId": "off-%s",
                  "productCode": "P1",
                  "manufacturerId": "MFG",
                  "version": "1",
                  "consentRef": "consent-1",
                  "agentId": "109337",
                  "values": { "proposer.panNumber": "ABCDE1234F" },
                  "distribution": { "rmEmployeeId": "E123", "channelType": "B2B" }
                }
                """.formatted(lob, lob);
    }

    private static void writeIndex() throws Exception {
        Files.createDirectories(CAPTURE_DIR);
        StringBuilder md = new StringBuilder();
        md.append("# Life LOB journey capture (Term / Saving / ULIP)\n\n");
        md.append("Environment: 1sb-integration-service → WireMock 1SB (not live sandbox; no `ONESB_API_KEY` in this VM).\n\n");
        md.append("| LOB | Multi-quote | MQ poll | Get criteria | Single quote | SQ poll | Get proposal | Submit proposal |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        for (Map<String, Object> r : LOB_REPORTS) {
            md.append("| ").append(r.get("lob"))
                    .append(" | ").append(flag(r.get("multiQuoteWired")))
                    .append(" | ").append(flag(r.get("mqPollWired")))
                    .append(" | ").append(flag(r.get("getCriteriaWired")))
                    .append(" | ").append(flag(r.get("singleQuoteWired")))
                    .append(" | N/A (same poll family) | ").append(flag(r.get("getProposalWired")))
                    .append(" | ").append(flag(r.get("submitProposalWired")))
                    .append(" |\n");
        }
        md.append("\nBank `mode=SINGLE` with `selection` emits 1SB `typeOfQuote=Single Quote`.\n");
        md.append("GET `/v1/quotes/criteria` is wired to 1SB `gateCriteria`.\n");
        MAPPER.writeValue(CAPTURE_DIR.resolve("index.json").toFile(), LOB_REPORTS);
        Files.writeString(CAPTURE_DIR.resolve("index.md"), md.toString());
    }

    private static String flag(Object wired) {
        return Boolean.TRUE.equals(wired) ? "PASS (wired)" : "FAIL (not wired)";
    }
}
