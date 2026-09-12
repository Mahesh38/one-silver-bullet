package com.bank.insurance.onesb.adapter.onesb.client;

import com.bank.insurance.onesb.TestErrors;

import com.bank.common.error.ErrorCodes;
import com.bank.common.error.ServiceException;
import com.bank.insurance.onesb.adapter.onesb.error.OneSbErrorNormaliser;
import com.bank.common.domain.LookupValue;
import com.bank.common.secrets.SecretProvider;
import com.bank.insurance.onesb.domain.port.outbound.OneSbMasterDataPort.MasterLookupResult;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
@Tag("FUNC-001")
@WireMockTest
class OneSbMasterDataAdapterTest {

    private OneSbMasterDataAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        RestClient restClient = RestClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth("test-api-key", "test-api-secret"))
                .build();
        SecretProvider secrets = mock(SecretProvider.class);
        when(secrets.getDistributorId()).thenReturn("TEST_DIST");
        adapter = new OneSbMasterDataAdapter(
                new OneSbHttpClient(restClient, new OneSbErrorNormaliser(TestErrors.ONESB), event -> {}, TestErrors.ONESB),
                secrets);
    }

    @Test
    void lookup_normalisesFlatStringArrays_andSendsExpectedBody() {
        stubFor(post(urlEqualTo("/insurance/lifeterm/v1/master/lookup"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "reqId": "r-1",
                                  "GENDER": ["M", "F"],
                                  "OCC": ["SALARIED", "SELF_EMPLOYED"]
                                }
                                """)));

        MasterLookupResult result = adapter.lookup(
                "TERM", "quote", List.of("GENDER", "OCC"), null);

        assertThat(result.lookups().get("GENDER"))
                .containsExactly(LookupValue.of("M"), LookupValue.of("F"));
        assertThat(result.lookups().get("OCC"))
                .extracting(LookupValue::code)
                .containsExactly("SALARIED", "SELF_EMPLOYED");

        verify(exactly(1), postRequestedFor(urlEqualTo("/insurance/lifeterm/v1/master/lookup"))
                .withHeader("Authorization", containing("Basic "))
                .withRequestBody(equalToJson("""
                        {
                          "lookUpCategory": "quote",
                          "entityIds": ["GENDER", "OCC"],
                          "distributor": {
                            "distributorID": "TEST_DIST",
                            "channelType": "B2B",
                            "salesChannel": "Online"
                          }
                        }
                        """)));
    }

    @Test
    void lookup_savingLob_usesLifesavePath() {
        stubFor(post(urlEqualTo("/insurance/lifesave/v1/master/lookup"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "GENDER": ["M"] }
                                """)));

        MasterLookupResult result = adapter.lookup("ULIP", "quote", List.of("GENDER"), null);

        assertThat(result.lookups().get("GENDER")).containsExactly(LookupValue.of("M"));
        verify(exactly(1), postRequestedFor(urlEqualTo("/insurance/lifesave/v1/master/lookup")));
    }

    @Test
    void lookup_normalisesCodeLabelObjects_andDataWrapper() {
        stubFor(post(urlEqualTo("/insurance/lifeterm/v1/master/lookup"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "data": {
                                    "TITLE": [
                                      {"code": "MR", "label": "Mr"},
                                      {"code": "MS", "label": "Ms"}
                                    ]
                                  }
                                }
                                """)));

        MasterLookupResult result = adapter.lookup(
                "TERM", "proposal", List.of("TITLE"), "HDFC");

        assertThat(result.lookups().get("TITLE"))
                .containsExactly(new LookupValue("MR", "Mr"), new LookupValue("MS", "Ms"));

        verify(postRequestedFor(urlEqualTo("/insurance/lifeterm/v1/master/lookup"))
                .withRequestBody(equalToJson("""
                        {
                          "lookUpCategory": "proposal",
                          "entityIds": ["TITLE"],
                          "manufacturerId": "HDFC",
                          "distributor": {
                            "distributorID": "TEST_DIST",
                            "channelType": "B2B",
                            "salesChannel": "Online"
                          }
                        }
                        """)));
    }

    @Test
    void lookup_upstream5xx_mapsToServiceException() {
        stubFor(post(urlEqualTo("/insurance/lifeterm/v1/master/lookup"))
                .willReturn(aResponse().withStatus(503).withBody("unavailable")));

        assertThatThrownBy(() -> adapter.lookup("TERM", "quote", List.of("GENDER"), null))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> {
                    ServiceException se = (ServiceException) ex;
                    assertThat(se.getErrorResponse().getCode()).isEqualTo(ErrorCodes.UPSTREAM_UNAVAILABLE);
                });
    }

    @Test
    void normalise_emptyWhenEntityMissing() {
        Map<String, List<LookupValue>> out = OneSbMasterDataAdapter.normalise(
                Map.of("OTHER", List.of("X")), List.of("GENDER"));
        assertThat(out.get("GENDER")).isEmpty();
    }
}
