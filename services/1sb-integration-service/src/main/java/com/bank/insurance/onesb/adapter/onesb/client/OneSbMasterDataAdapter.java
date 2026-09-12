package com.bank.insurance.onesb.adapter.onesb.client;

import com.bank.common.domain.LookupValue;
import com.bank.common.secrets.SecretProvider;
import com.bank.insurance.onesb.domain.port.outbound.OneSbMasterDataPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calls 1SB LOB master lookup ({@code POST /insurance/lifeterm|lifesave/v1/master/lookup}).
 * Demo {@code POST /v1/master/lookup} is 404 — do not use that path.
 */
@Component
public class OneSbMasterDataAdapter implements OneSbMasterDataPort {

    static final String TERM_PATH = "/insurance/lifeterm/v1/master/lookup";
    static final String SAVE_PATH = "/insurance/lifesave/v1/master/lookup";

    private final OneSbHttpClient httpClient;
    private final SecretProvider secretProvider;

    public OneSbMasterDataAdapter(OneSbHttpClient httpClient, SecretProvider secretProvider) {
        this.httpClient = httpClient;
        this.secretProvider = secretProvider;
    }

    @Override
    @SuppressWarnings("unchecked")
    public MasterLookupResult lookup(
            String lob,
            String lookUpCategory,
            List<String> entityIds,
            String manufacturerId) {
        OneSbMasterLookupRequest body = new OneSbMasterLookupRequest(
                lookUpCategory,
                entityIds,
                manufacturerId == null || manufacturerId.isBlank() ? null : manufacturerId,
                new OneSbMasterLookupRequest.Distributor(
                        secretProvider.getDistributorId(), "B2B", "Online")
        );

        Map<String, Object> raw = httpClient.post(lookupPath(lob), body, Map.class);
        Map<String, List<LookupValue>> normalised = normalise(raw, entityIds);
        return new MasterLookupResult(normalised);
    }

    static String lookupPath(String lob) {
        if (lob == null || lob.isBlank()) {
            return TERM_PATH;
        }
        return switch (lob.trim().toUpperCase(Locale.ROOT)) {
            case "SAVING", "ULIP" -> SAVE_PATH;
            default -> TERM_PATH;
        };
    }

    /**
     * Converts 1SB maps of string arrays or objects into {@link LookupValue} lists.
     * Flat strings become {@code code == label}.
     */
    static Map<String, List<LookupValue>> normalise(Map<String, Object> raw, List<String> entityIds) {
        Map<String, List<LookupValue>> out = new LinkedHashMap<>();
        if (raw == null) {
            for (String id : entityIds) {
                out.put(id, List.of());
            }
            return out;
        }

        Map<String, Object> dataSection = asMap(raw.get("data"));

        for (String entityId : entityIds) {
            Object value = raw.get(entityId);
            if (value == null && dataSection != null) {
                value = dataSection.get(entityId);
            }
            out.put(entityId, toLookupValues(value));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<LookupValue> toLookupValues(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<LookupValue> result = new ArrayList<>(list.size());
            for (Object item : list) {
                LookupValue lv = toLookupValue(item);
                if (lv != null) {
                    result.add(lv);
                }
            }
            return List.copyOf(result);
        }
        if (value instanceof Map<?, ?> map && !map.isEmpty() && looksLikeCodeLabelMap(map)) {
            LookupValue lv = toLookupValue(map);
            return lv != null ? List.of(lv) : List.of();
        }
        if (value instanceof String s) {
            return List.of(LookupValue.of(s));
        }
        return List.of(LookupValue.of(String.valueOf(value)));
    }

    private static boolean looksLikeCodeLabelMap(Map<?, ?> map) {
        return map.containsKey("code") || map.containsKey("label") || map.containsKey("value");
    }

    @SuppressWarnings("unchecked")
    private static LookupValue toLookupValue(Object item) {
        if (item == null) {
            return null;
        }
        if (item instanceof String s) {
            return LookupValue.of(s);
        }
        if (item instanceof Map<?, ?> map) {
            Object code = firstNonNull(map.get("code"), map.get("value"), map.get("id"));
            Object label = firstNonNull(map.get("label"), map.get("name"), map.get("description"), code);
            if (code == null && label == null) {
                return null;
            }
            String codeStr = code != null ? String.valueOf(code) : String.valueOf(label);
            String labelStr = label != null ? String.valueOf(label) : codeStr;
            return new LookupValue(codeStr, labelStr);
        }
        return LookupValue.of(String.valueOf(item));
    }

    private static Object firstNonNull(Object... values) {
        for (Object v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }
}
