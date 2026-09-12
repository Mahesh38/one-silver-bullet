package com.bank.persistence.api.internal.v1;

import com.bank.common.error.ErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API coverage for jobs, status patch, and offers under {@code /internal/v1/jobs}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class JobApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createJob_returns201_withExpectedFields() throws Exception {
        String idem = "idem-job-create-" + UUID.randomUUID();

        mockMvc.perform(post("/internal/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "jobType": "QUOTE",
                                  "journeyId": "journey-job-1",
                                  "idempotencyKey": "%s",
                                  "createdByActor": "qa-002-test"
                                }
                                """.formatted(idem)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId", notNullValue()))
                .andExpect(jsonPath("$.lob", is("TERM")))
                .andExpect(jsonPath("$.jobType", is("QUOTE")))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.journeyId", is("journey-job-1")))
                .andExpect(jsonPath("$.idempotencyKey", is(idem)))
                .andExpect(jsonPath("$.externalProvider", is("ONE_SB")))
                .andExpect(jsonPath("$.createdByActor", is("qa-002-test")))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()))
                .andExpect(jsonPath("$.version", is(0)));
    }

    @Test
    void createJob_missingRequiredField_returns400_withValidationError() throws Exception {
        mockMvc.perform(post("/internal/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "",
                                  "jobType": "QUOTE",
                                  "createdByActor": "qa-002-test"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code", is(ErrorCodes.VALIDATION_ERROR)))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.errors[0].code", is(ErrorCodes.MISSING_REQUIRED_FIELD)))
                .andExpect(jsonPath("$.errors[0].field", is("lob")));
    }

    @Test
    void getJob_byId_returnsJob() throws Exception {
        String jobId = createJob("idem-job-get-" + UUID.randomUUID());

        mockMvc.perform(get("/internal/v1/jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId", is(jobId)))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.lob", is("TERM")));
    }

    @Test
    void getJob_unknown_returns404_withResourceNotFound() throws Exception {
        mockMvc.perform(get("/internal/v1/jobs/{jobId}", "missing-job-id"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code", is(ErrorCodes.RESOURCE_NOT_FOUND)))
                .andExpect(jsonPath("$.title", is("Not found")))
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    void patchStatus_transitionsRunningCompletedTimeout() throws Exception {
        String jobId = createJob("idem-job-patch-" + UUID.randomUUID());

        mockMvc.perform(patch("/internal/v1/jobs/{jobId}/status", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "RUNNING",
                                  "externalReqId": "ext-req-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RUNNING")))
                .andExpect(jsonPath("$.externalReqId", is("ext-req-1")));

        mockMvc.perform(patch("/internal/v1/jobs/{jobId}/status", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "COMPLETED",
                                  "completedAt": "2026-07-30T12:30:00Z",
                                  "applicationNumber": "APP-PATCH-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.completedAt", notNullValue()))
                .andExpect(jsonPath("$.applicationNumber", is("APP-PATCH-1")));

        String timeoutJobId = createJob("idem-job-timeout-" + UUID.randomUUID());
        mockMvc.perform(patch("/internal/v1/jobs/{jobId}/status", timeoutJobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "TIMEOUT",
                                  "failureReason": "poll window exhausted"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("TIMEOUT")))
                .andExpect(jsonPath("$.failureReason", is("poll window exhausted")));
    }

    @Test
    void patchStatus_unknownJob_returns404_withResourceNotFound() throws Exception {
        mockMvc.perform(patch("/internal/v1/jobs/{jobId}/status", "no-such-job")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "RUNNING"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code", is(ErrorCodes.RESOURCE_NOT_FOUND)));
    }

    @Test
    void addOffer_persists_andListReturnsIt() throws Exception {
        String jobId = createJob("idem-job-offer-" + UUID.randomUUID());
        String offerId = UUID.randomUUID().toString();

        mockMvc.perform(post("/internal/v1/jobs/{jobId}/offers", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "offerId": "%s",
                                  "insurerCode": "INS-A",
                                  "productCode": "TERM-100",
                                  "productName": "Synthetic Term Plan",
                                  "premiumAmount": 1250.50,
                                  "premiumFrequency": "YEARLY",
                                  "sumAssured": 500000,
                                  "outOfBound": false,
                                  "offerStatus": "AVAILABLE"
                                }
                                """.formatted(offerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.offerId", is(offerId)))
                .andExpect(jsonPath("$.jobId", is(jobId)))
                .andExpect(jsonPath("$.insurerCode", is("INS-A")))
                .andExpect(jsonPath("$.productCode", is("TERM-100")))
                .andExpect(jsonPath("$.premiumAmount", is(1250.50)))
                .andExpect(jsonPath("$.offerStatus", is("AVAILABLE")))
                .andExpect(jsonPath("$.outOfBound", is(false)))
                .andExpect(jsonPath("$.createdAt", notNullValue()));

        mockMvc.perform(get("/internal/v1/jobs/{jobId}/offers", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].offerId", is(offerId)))
                .andExpect(jsonPath("$[0].insurerCode", is("INS-A")));
    }

    @Test
    void addOffer_persistsFundsJson() throws Exception {
        String jobId = createJob("idem-job-funds-" + UUID.randomUUID());
        String offerId = UUID.randomUUID().toString();

        mockMvc.perform(post("/internal/v1/jobs/{jobId}/offers", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "offerId": "%s",
                                  "insurerCode": "BALIC",
                                  "productCode": "301",
                                  "offerStatus": "AVAILABLE",
                                  "fundsJson": "[{\\"code\\":\\"EQ1\\",\\"name\\":\\"Equity\\",\\"allocationPercent\\":60}]"
                                }
                                """.formatted(offerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.offerId", is(offerId)))
                .andExpect(jsonPath("$.fundsJson", org.hamcrest.Matchers.containsString("EQ1")));

        mockMvc.perform(get("/internal/v1/jobs/{jobId}/offers", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fundsJson", org.hamcrest.Matchers.containsString("EQ1")));
    }

    @Test
    void addOffer_missingJob_returns404_withResourceNotFound() throws Exception {
        mockMvc.perform(post("/internal/v1/jobs/{jobId}/offers", "missing-offer-job")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "insurerCode": "INS-A",
                                  "offerStatus": "AVAILABLE"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code", is(ErrorCodes.RESOURCE_NOT_FOUND)));
    }

    @Test
    void listOffers_missingJob_returns404_withResourceNotFound() throws Exception {
        mockMvc.perform(get("/internal/v1/jobs/{jobId}/offers", "missing-list-job"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is(ErrorCodes.RESOURCE_NOT_FOUND)));
    }

    private String createJob(String idempotencyKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/internal/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "jobType": "QUOTE",
                                  "journeyId": "journey-helper",
                                  "idempotencyKey": "%s",
                                  "createdByActor": "qa-002-test"
                                }
                                """.formatted(idempotencyKey)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("jobId").asText();
    }
}
