package com.bank.insurance.onesb.adapter.onesb.quote;

import com.bank.insurance.onesb.adapter.onesb.client.OneSbHttpClient;
import com.bank.common.domain.Lob;
import com.bank.common.domain.QuoteOffer;
import com.bank.common.domain.RawPayloadDirection;
import com.bank.insurance.onesb.domain.port.outbound.OneSbQuotePort;
import com.bank.insurance.onesb.domain.port.outbound.RawPayloadStorePort;
import com.bank.insurance.onesb.lob.LobQuoteHandler;
import com.bank.insurance.onesb.lob.LobQuoteHandlerRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1SB quote adapter — submit + poll with offer normalisation.
 * All 1SB JSON parsing stays inside this adapter.
 * Submit does not resolve LOB handlers — the application service supplies path + payload (Case 2).
 * <p>
 * Submit request/response and the completed poll response are captured as raw payload evidence
 * (COMP-003) — best-effort, never fails the quote flow.
 */
@Component
public class OneSbQuoteAdapter implements OneSbQuotePort {

    private final OneSbHttpClient httpClient;
    private final LobQuoteHandlerRegistry handlerRegistry;
    private final ObjectMapper objectMapper;
    private final RawPayloadStorePort rawPayloadStorePort;

    @Autowired
    public OneSbQuoteAdapter(OneSbHttpClient httpClient,
                             LobQuoteHandlerRegistry handlerRegistry,
                             ObjectMapper objectMapper,
                             RawPayloadStorePort rawPayloadStorePort) {
        this.httpClient = httpClient;
        this.handlerRegistry = handlerRegistry;
        this.objectMapper = objectMapper;
        this.rawPayloadStorePort = rawPayloadStorePort;
    }

    /** Test / manual wiring without raw payload capture. */
    public OneSbQuoteAdapter(OneSbHttpClient httpClient,
                             LobQuoteHandlerRegistry handlerRegistry,
                             ObjectMapper objectMapper) {
        this(httpClient, handlerRegistry, objectMapper, (jobId, direction, operation, lob, payload, httpStatus) -> { });
    }

    @Override
    public String submitQuote(String jobId, String path, Object payload) {
        captureRaw(jobId, RawPayloadDirection.REQ, path, payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = httpClient.post(path, payload, Map.class);
        captureRaw(jobId, RawPayloadDirection.RES, path, response);
        return extractReqId(response);
    }

    @Override
    public List<QuoteOffer> pollQuoteResult(String jobId, String externalReqId, String lob) {
        String path = pollPath(lob, externalReqId);
        String body = httpClient.get(path, String.class);
        captureRaw(jobId, RawPayloadDirection.RES, path, body);
        return parseOffers(body);
    }

    @Override
    public boolean isPollComplete(String jobId, String externalReqId, String lob) {
        String body = httpClient.get(pollPath(lob, externalReqId), String.class);
        Boolean flag = completeFlag(body);
        if (flag != null) {
            return flag;
        }
        return hasAvailableOffer(body);
    }

    private boolean hasAvailableOffer(String body) {
        return parseOffers(body).stream().anyMatch(o -> o.errorSummary() == null);
    }

    private String pollPath(String lob, String externalReqId) {
        LobQuoteHandler handler = handlerRegistry.get(Lob.valueOf(lob));
        return handler.pollPath(externalReqId);
    }

    private void captureRaw(String jobId, RawPayloadDirection direction, String operation, Object body) {
        if (jobId == null || body == null) {
            return;
        }
        try {
            String json = body instanceof String s ? s : objectMapper.writeValueAsString(body);
            rawPayloadStorePort.store(jobId, direction, operation, null, json, null);
        } catch (Exception ignored) {
            // best-effort — never block the quote flow on capture failure
        }
    }

    private String extractReqId(Map<String, Object> response) {
        if (response == null) {
            throw new IllegalStateException("1SB quote submit returned empty body");
        }
        Object reqId = response.get("reqId");
        if (reqId == null && response.get("data") instanceof Map<?, ?> data) {
            reqId = data.get("reqId");
        }
        if (reqId == null || reqId.toString().isBlank()) {
            throw new IllegalStateException("1SB quote submit missing reqId");
        }
        return reqId.toString();
    }

    private Boolean completeFlag(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode flag = root.path("data").path("isPollComplete");
            if (flag.isMissingNode() || flag.isNull()) {
                flag = root.path("isPollComplete");
            }
            if (flag.isMissingNode() || flag.isNull()) {
                return null;
            }
            if (flag.isBoolean()) {
                return flag.booleanValue();
            }
            if (flag.isTextual() && !flag.asText().isBlank()) {
                return Boolean.parseBoolean(flag.asText());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    List<QuoteOffer> parseOffers(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            List<QuoteOffer> offers = new ArrayList<>();

            collectOfferNodes(offers, data.path("quote"));
            collectOfferNodes(offers, data.path("products"));
            collectOfferNodes(offers, data.path("insuranceAndProducts"));
            JsonNode quoteResponse = data.path("quoteResponse");
            if (quoteResponse.isObject() && !quoteResponse.isEmpty()) {
                collectOfferNodes(offers, quoteResponse.path("quote"));
                collectOfferNodes(offers, quoteResponse.path("insuranceAndProducts"));
            }

            // Per-manufacturer errors with no product payload → offer-shaped failure rows
            JsonNode errors = data.path("errors");
            if (errors.isMissingNode()) {
                errors = root.path("errors");
            }
            if (errors.isArray()) {
                for (JsonNode err : errors) {
                    String insurer = text(err, "manufacturerId", "insurerCode", "code",
                            "insuranceCompanyCode");
                    String message = errorMessage(err);
                    if (message == null || message.isBlank()) {
                        continue;
                    }
                    // Skip if we already mapped a successful offer for same insurer with no error
                    boolean already = offers.stream().anyMatch(o ->
                            insurer != null && insurer.equals(o.insurerCode()) && o.errorSummary() == null);
                    if (!already) {
                        offers.add(new QuoteOffer(
                                null,
                                insurer,
                                text(err, "manufacturerName", "insurerName", "insuranceCompanyName"),
                                text(err, "productCode", "productId"),
                                text(err, "productName"),
                                null,
                                null,
                                null,
                                false,
                                "ERROR",
                                message
                        ));
                    } else {
                        // Attach error onto existing? Prefer separate PARTIAL rows: add error offer
                        offers.add(new QuoteOffer(
                                null,
                                insurer,
                                text(err, "manufacturerName", "insurerName", "insuranceCompanyName"),
                                text(err, "productCode", "productId"),
                                text(err, "productName"),
                                null,
                                null,
                                null,
                                false,
                                "ERROR",
                                message
                        ));
                    }
                }
            }
            return List.copyOf(offers);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void collectOfferNodes(List<QuoteOffer> offers, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                expandOfferNode(offers, item);
            }
            return;
        }
        if (node.isObject() && !node.isEmpty()) {
            expandOfferNode(offers, node);
        }
    }

    private void expandOfferNode(List<QuoteOffer> offers, JsonNode node) {
        JsonNode nestedIap = node.path("insuranceAndProducts");
        if (nestedIap.isArray() && !nestedIap.isEmpty()) {
            for (JsonNode iap : nestedIap) {
                expandProductDetails(offers, iap, node);
            }
            return;
        }
        if (nestedIap.isObject() && !nestedIap.isEmpty()) {
            expandProductDetails(offers, nestedIap, node);
            return;
        }
        JsonNode productDetails = node.path("productDetails");
        if (productDetails.isArray() && !productDetails.isEmpty()) {
            for (JsonNode pd : productDetails) {
                offers.add(mapOffer(pd, node));
            }
            return;
        }
        offers.add(mapOffer(node, null));
    }

    private void expandProductDetails(List<QuoteOffer> offers, JsonNode companyOrProduct, JsonNode parent) {
        JsonNode productDetails = companyOrProduct.path("productDetails");
        if (productDetails.isArray() && !productDetails.isEmpty()) {
            for (JsonNode pd : productDetails) {
                offers.add(mapOffer(pd, companyOrProduct));
            }
            return;
        }
        if (productDetails.isObject() && !productDetails.isEmpty()) {
            offers.add(mapOffer(productDetails, companyOrProduct));
            return;
        }
        offers.add(mapOffer(companyOrProduct, parent));
    }

    private QuoteOffer mapOffer(JsonNode node, JsonNode parent) {
        String errorSummary = firstText(node, parent, "errorSummary", "errorMessage", "error");
        JsonNode errArr = node.path("errors");
        if ((errorSummary == null || errorSummary.isBlank()) && errArr.isArray() && !errArr.isEmpty()) {
            errorSummary = text(errArr.get(0), "message", "errorMessage", "detail");
        }

        BigDecimal premium = firstDecimal(node, parent,
                "premiumAmount", "premium", "totalPremium", "modalPremium", "installmentPremium");
        if (premium == null) {
            premium = nestedPremium(node);
        }
        if (premium == null && parent != null) {
            premium = nestedPremium(parent);
        }

        BigDecimal sumAssured = firstDecimal(node, parent, "sumAssured", "sum_assured", "quoteAmount");
        boolean oob = node.path("outOfBound").asBoolean(false)
                || (parent != null && parent.path("outOfBound").asBoolean(false))
                || "Yes".equalsIgnoreCase(firstText(node, parent, "outOfBound"));

        String offerStatus = errorSummary != null && !errorSummary.isBlank() ? "ERROR" : "AVAILABLE";
        String statusField = firstText(node, parent, "offerStatus", "status");
        if (statusField != null) {
            offerStatus = statusField;
        }

        String freq = firstText(node, parent, "premiumFrequency", "frequency", "premiumPaymentFrequency", "freq");
        if (freq == null && parent != null) {
            freq = text(parent.path("productDetails"), "premiumPaymentFrequency", "freq", "frequency");
        }

        return new QuoteOffer(
                firstText(node, parent, "offerId", "quoteId", "id"),
                firstText(node, parent, "insurerCode", "manufacturerId", "manufacturerCode",
                        "insuranceCompanyCode"),
                firstText(node, parent, "insurerName", "manufacturerName", "insuranceCompanyName"),
                firstText(node, parent, "productCode", "productId"),
                firstText(node, parent, "productName", "product"),
                premium,
                freq,
                sumAssured,
                oob,
                offerStatus,
                errorSummary,
                extractFunds(node, parent)
        );
    }

    static List<com.bank.common.domain.FundAllocation> extractFunds(JsonNode node, JsonNode parent) {
        List<com.bank.common.domain.FundAllocation> funds = new ArrayList<>();
        collectFunds(funds, node);
        collectFunds(funds, parent);
        if (node != null) {
            collectFunds(funds, node.path("productDetails"));
        }
        if (parent != null) {
            collectFunds(funds, parent.path("productDetails"));
        }
        return List.copyOf(funds);
    }

    private static void collectFunds(List<com.bank.common.domain.FundAllocation> funds, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        JsonNode fundDetails = node.path("planOption").path("investmentOptions").path("fundDetails");
        if (fundDetails.isMissingNode() || fundDetails.isNull()) {
            fundDetails = node.path("investmentOptions").path("fundDetails");
        }
        if (fundDetails.isMissingNode() || fundDetails.isNull()) {
            fundDetails = node.path("fundDetails");
        }
        if (!fundDetails.isArray()) {
            return;
        }
        for (JsonNode fund : fundDetails) {
            String code = text(fund, "fundCode", "code", "fundId");
            String name = text(fund, "fundName", "name");
            if (code == null && name == null) {
                continue;
            }
            funds.add(new com.bank.common.domain.FundAllocation(
                    code, name, decimal(fund, "allocationPercent", "allocation", "fundAllocation")));
        }
    }

    private static BigDecimal nestedPremium(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.path("premium").isObject()) {
            BigDecimal nested = decimal(node.path("premium"), "amount", "premiumAmount",
                    "modalPremium", "installmentPremium");
            if (nested != null) {
                return nested;
            }
        }
        JsonNode individuals = node.path("individualDetails");
        if (individuals.isArray()) {
            for (JsonNode ind : individuals) {
                JsonNode details = ind.path("premiumDetails");
                if (details.isArray()) {
                    for (JsonNode pd : details) {
                        BigDecimal value = decimal(pd, "totalPremiumValue", "premiumValue", "amount");
                        if (value != null) {
                            return value;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String errorMessage(JsonNode err) {
        String message = text(err, "message", "errorMessage", "detail", "errorDisplayMessage");
        if (message != null && !message.isBlank()) {
            return message;
        }
        JsonNode list = err.path("listOfErrors");
        if (list.isArray() && !list.isEmpty()) {
            return text(list.get(0), "message", "errorMessage", "errorDisplayMessage", "detail");
        }
        return null;
    }

    private static String firstText(JsonNode node, JsonNode parent, String... fields) {
        String value = text(node, fields);
        if (value != null) {
            return value;
        }
        return text(parent, fields);
    }

    private static BigDecimal firstDecimal(JsonNode node, JsonNode parent, String... fields) {
        BigDecimal value = decimal(node, fields);
        if (value != null) {
            return value;
        }
        return decimal(parent, fields);
    }

    private static String text(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode v = node.path(field);
            if (!v.isMissingNode() && !v.isNull() && v.isValueNode()) {
                String s = v.asText();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        for (String field : fields) {
            JsonNode v = node.path(field);
            if (v.isNumber()) {
                return v.decimalValue();
            }
            if (v.isTextual()) {
                try {
                    return new BigDecimal(v.asText());
                } catch (NumberFormatException ignored) {
                    // try next
                }
            }
        }
        return null;
    }
}
