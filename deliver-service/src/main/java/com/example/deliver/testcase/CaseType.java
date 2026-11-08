package com.example.deliver.testcase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.platform.common.error.ApiException;

/**
 * Test case type vocabulary.
 *
 * <p>The database column is {@code VARCHAR(8)} (T0 migration, not ours to change) while the UI
 * speaks the long vocabulary ({@code FUNCTIONAL} = 10 chars). The boundary therefore normalises:
 * storage keeps the short code, every response carries the long word. Both mappings live here so
 * the two directions can never drift, and an unknown value is a 400 rather than a silently
 * truncated column.</p>
 */
public final class CaseType {

    private static final Map<String, String> STORE = new LinkedHashMap<>();
    private static final Map<String, String> WIRE = new LinkedHashMap<>();

    static {
        alias("FUNCTIONAL", "FUNC", "FUNCTIONAL");
        alias("INTERFACE", "API", "INTERFACE");
        alias("UI", "UI", "UI");
        alias("PERFORMANCE", "PERF", "PERFORMANCE");
        alias("SECURITY", "SEC", "SECURITY");
        alias("COMPATIBILITY", "COMPAT", "COMPATIBILITY");
    }

    private CaseType() {
    }

    private static void alias(String wire, String store, String canonicalWire) {
        STORE.put(wire, store);
        STORE.put(store, store);
        WIRE.put(store, canonicalWire);
    }

    /** Storage code for a wire value; blank means "unspecified" and defaults to FUNCTIONAL. */
    public static String store(String value) {
        if (value == null || value.isBlank()) {
            return "FUNC";
        }
        String store = STORE.get(value.trim().toUpperCase(Locale.ROOT));
        if (store == null) {
            throw new ApiException(400, "CASE_TYPE_INVALID", "用例类型不支持：" + value,
                    Map.of("allowed", wireValues()));
        }
        return store;
    }

    /** Wire value for a storage code (unknown storage stays as it was stored, never dropped). */
    public static String wire(String store) {
        if (store == null || store.isBlank()) {
            return "FUNCTIONAL";
        }
        String key = store.trim().toUpperCase(Locale.ROOT);
        return WIRE.getOrDefault(key, key);
    }

    public static List<String> wireValues() {
        return List.copyOf(WIRE.values());
    }

    public static List<String> storeCodes() {
        return List.copyOf(WIRE.keySet());
    }
}
