package com.bank.insurance.onesb.adapter.onesb.eligibility;

import com.bank.common.domain.EligibilitySubmitResult;
import com.bank.common.domain.Lob;
import com.bank.common.domain.ProposalSchema;
import com.bank.insurance.onesb.adapter.onesb.client.OneSbHttpClient;
import com.bank.insurance.onesb.lob.life.payload.LifeGateCriteriaSubmitBody;
import com.bank.insurance.onesb.lob.life.payload.LifeQuoteRequest;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("FUNC-023")
class OneSbEligibilityAdapterTest {

    private static final String PATH =
            "/insurance/lifeterm/v1/quote/gateCriteria?productId=345&manufacturerId=BALIC";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private OneSbEligibilityAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        RestClient restClient = RestClient.builder()
                .baseUrl(wireMock.baseUrl())
                .requestFactory(factory)
                .build();
        adapter = new OneSbEligibilityAdapter(new OneSbHttpClient(restClient));
    }

    @Test
    void getCriteria_getsHandlerPath_andWrapsFields() {
        wireMock.stubFor(get(urlPathEqualTo("/insurance/lifeterm/v1/quote/gateCriteria"))
                .withQueryParam("productId", equalTo("345"))
                .withQueryParam("manufacturerId", equalTo("BALIC"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"fieldGroups\":[{\"name\":\"gate\"}]}")));

        ProposalSchema schema = adapter.getCriteria(Lob.TERM, "345", "BALIC", PATH);

        assertThat(schema.lob()).isEqualTo(Lob.TERM);
        assertThat(schema.productCode()).isEqualTo("345");
        assertThat(schema.manufacturerId()).isEqualTo("BALIC");
        assertThat(schema.fields()).containsKey("fieldGroups");
        wireMock.verify(getRequestedFor(urlPathEqualTo("/insurance/lifeterm/v1/quote/gateCriteria")));
    }

    @Test
    void submit_postsDistributorAndFormFields() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reqId\":\"REQ-C1\"}")));

        LifeGateCriteriaSubmitBody body = new LifeGateCriteriaSubmitBody(
                new LifeQuoteRequest.Distributor("BCIBL", "109337", "B2B", "Online"),
                Map.of("occupation", "SALARIED"));

        EligibilitySubmitResult result = adapter.submit(PATH, body);

        assertThat(result.accepted()).isTrue();
        assertThat(result.reqId()).isEqualTo("REQ-C1");
        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.distributor.distributorID", equalTo("BCIBL")))
                .withRequestBody(matchingJsonPath("$.occupation", equalTo("SALARIED"))));
    }
}
