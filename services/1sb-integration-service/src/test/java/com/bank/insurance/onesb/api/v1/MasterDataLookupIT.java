package com.bank.insurance.onesb.api.v1;

import com.bank.common.error.ErrorCodes;
import com.bank.insurance.onesb.application.MasterDataService;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.resetAllRequests;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end FUNC-001 acceptance: MockMvc + WireMock 1SB.
 */
@Tag("integration")
@Tag("FUNC-001")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MasterDataLookupIT.MutableClockConfig.class)
class MasterDataLookupIT {

    private static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    private static final AtomicReference<Instant> NOW =
            new AtomicReference<>(Instant.parse("2026-07-30T12:00:00Z"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MasterDataService masterDataService;

    @BeforeAll
    static void startWireMock() {
        WIRE_MOCK.start();
        WireMock.configureFor("localhost", WIRE_MOCK.port());
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("onesb.client.base-url", WIRE_MOCK::baseUrl);
        registry.add("onesb.api-key", () -> "test-api-key");
        registry.add("onesb.api-secret", () -> "test-api-secret");
        registry.add("onesb.distributor-id", () -> "TEST_DIST");
        registry.add("insurance.secrets.source", () -> "PROPERTIES");
        registry.add("bank.persistence.base-url", () -> "http://localhost:8081");
        registry.add("insurance.masters.cache-ttl-seconds", () -> "4");
    }

    @BeforeEach
    void resetStubs() {
        WIRE_MOCK.resetAll();
        WireMock.configureFor("localhost", WIRE_MOCK.port());
        NOW.set(Instant.parse("2026-07-30T12:00:00Z"));
        masterDataService.clearCache();
    }

    @Test
    @DisplayName("AC-1: lob=TERM + entityIds returns normalised enum lists")
    void ac1_termLookup_returnsNormalisedEnums() throws Exception {
        WireMock.stubFor(WireMock.post(urlEqualTo("/insurance/lifeterm/v1/master/lookup"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "GENDER": ["M", "F"],
                                  "OCC": ["SALARIED"],
                                  "TITLE": ["MR", "MS"]
                                }
                                """)));

        mockMvc.perform(post("/v1/master-data/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "lookUpCategory": "quote",
                                  "entityIds": ["GENDER", "OCC", "TITLE"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(MasterDataController.CACHE_HEADER, "MISS"))
                .andExpect(jsonPath("$.lob", is("TERM")))
                .andExpect(jsonPath("$.lookups.GENDER[0].code", is("M")))
                .andExpect(jsonPath("$.lookups.GENDER[0].label", is("M")))
                .andExpect(jsonPath("$.lookups.GENDER[1].code", is("F")))
                .andExpect(jsonPath("$.lookups.OCC[0].code", is("SALARIED")))
                .andExpect(jsonPath("$.lookups.TITLE[0].code", is("MR")))
                .andExpect(jsonPath("$.cache.hit", is(false)))
                .andExpect(jsonPath("$.cache.stale", is(false)));

        verify(exactly(1), postRequestedFor(urlEqualTo("/insurance/lifeterm/v1/master/lookup")));
    }

    @Test
    @DisplayName("AC-2: cache hit within TTL → no 1SB call")
    void ac2_secondCall_doesNotHitOneSb() throws Exception {
        WireMock.stubFor(WireMock.post(urlEqualTo("/insurance/lifeterm/v1/master/lookup"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "GENDER": ["M", "F"] }
                                """)));

        String body = """
                {
                  "lob": "TERM",
                  "entityIds": ["GENDER"],
                  "lookUpCategory": "quote"
                }
                """;

        mockMvc.perform(post("/v1/master-data/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string(MasterDataController.CACHE_HEADER, "MISS"));

        resetAllRequests();

        mockMvc.perform(post("/v1/master-data/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string(MasterDataController.CACHE_HEADER, "HIT"))
                .andExpect(jsonPath("$.cache.hit", is(true)))
                .andExpect(jsonPath("$.lookups.GENDER[0].code", is("M")));

        verify(exactly(0), postRequestedFor(urlEqualTo("/insurance/lifeterm/v1/master/lookup")));
    }

    @Test
    @DisplayName("AC-3: 1SB down + stale cache → STALE; cold → 503")
    void ac3_staleFallback_andCold503() throws Exception {
        WireMock.stubFor(WireMock.post(urlEqualTo("/insurance/lifeterm/v1/master/lookup"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "OCC": ["SALARIED"] }
                                """)));

        mockMvc.perform(post("/v1/master-data/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "entityIds": ["OCC"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(MasterDataController.CACHE_HEADER, "MISS"));

        NOW.set(NOW.get().plusSeconds(10)); // expire TTL (4s)

        WireMock.stubFor(WireMock.post(urlEqualTo("/insurance/lifeterm/v1/master/lookup"))
                .willReturn(aResponse().withStatus(503).withBody("down")));

        mockMvc.perform(post("/v1/master-data/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "entityIds": ["OCC"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(MasterDataController.CACHE_HEADER, "STALE"))
                .andExpect(jsonPath("$.cache.stale", is(true)))
                .andExpect(jsonPath("$.lookups.OCC[0].code", is("SALARIED")));

        // Cold key (never cached) → 503
        mockMvc.perform(post("/v1/master-data/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "TERM",
                                  "entityIds": ["CHANNEL"]
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code", is(ErrorCodes.UPSTREAM_UNAVAILABLE)))
                .andExpect(jsonPath("$.status", is(503)));
    }

    @Test
    @DisplayName("FUNC-024: lob=ULIP uses lifesave master lookup")
    @Tag("FUNC-024")
    void ulipLookup_usesLifesavePath() throws Exception {
        WireMock.stubFor(WireMock.post(urlEqualTo("/insurance/lifesave/v1/master/lookup"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "GENDER": ["M"] }
                                """)));

        mockMvc.perform(post("/v1/master-data/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lob": "ULIP",
                                  "lookUpCategory": "quote",
                                  "entityIds": ["GENDER"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lookups.GENDER[0].code", is("M")));

        verify(exactly(1), postRequestedFor(urlEqualTo("/insurance/lifesave/v1/master/lookup")));
        verify(exactly(0), postRequestedFor(urlEqualTo("/v1/master/lookup")));
    }

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return new Clock() {
                @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return NOW.get(); }
            };
        }
    }
}
