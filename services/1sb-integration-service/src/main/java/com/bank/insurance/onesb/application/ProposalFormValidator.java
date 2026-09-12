package com.bank.insurance.onesb.application;

import com.bank.common.domain.ProposalSchema;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks a 1SB dynamic proposal schema for mandatory field names and compares
 * them to the bank {@code values} map ({@code FUNC-025}).
 */
public final class ProposalFormValidator {

    private static final int MAX_MISSING = 20;

    private ProposalFormValidator() {}

    public static List<String> missingMandatory(ProposalSchema schema, Map<String, Object> values) {
        if (schema == null || schema.fields() == null || schema.fields().isEmpty()) {
            return List.of();
        }
        Set<String> required = new LinkedHashSet<>();
        collectMandatory(schema.fields(), required);
        if (required.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String name : required) {
            if (missing.size() >= MAX_MISSING) {
                break;
            }
            if (!hasValue(values, name)) {
                missing.add(name);
            }
        }
        return List.copyOf(missing);
    }

    static void collectMandatory(Object node, Set<String> required) {
        if (node instanceof Map<?, ?> map) {
            boolean mandatory = isTruthy(map.get("mandatory")) || isTruthy(map.get("required"));
            String name = firstText(map, "name", "id", "fieldName", "fieldId", "key");
            if (mandatory && StringUtils.hasText(name)) {
                required.add(name);
            }
            for (Object value : map.values()) {
                collectMandatory(value, required);
            }
            return;
        }
        if (node instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectMandatory(item, required);
            }
        }
    }

    private static boolean hasValue(Map<String, Object> values, String name) {
        if (values == null || values.isEmpty() || name == null) {
            return false;
        }
        if (present(values.get(name))) {
            return true;
        }
        Object nested = values;
        for (String part : name.split("\\.")) {
            if (!(nested instanceof Map<?, ?> map)) {
                return false;
            }
            nested = map.get(part);
        }
        return present(nested);
    }

    private static boolean present(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return StringUtils.hasText(s);
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    private static boolean isTruthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return false;
        }
        String s = value.toString().trim();
        return "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "y".equalsIgnoreCase(s)
                || "1".equals(s);
    }

    private static String firstText(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object v = map.get(key);
            if (v != null && StringUtils.hasText(v.toString())) {
                return v.toString().trim();
            }
        }
        return null;
    }
}
