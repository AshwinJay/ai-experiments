package com.dbtespresso.parser;

import java.util.*;
import java.util.regex.*;

/**
 * Extracts config() key-value pairs from Jinja SQL templates.
 * Handles: config(materialized='table', schema='analytics', tags=['a','b'])
 */
public final class ConfigExtractor {

    private static final Pattern CONFIG_BLOCK = Pattern.compile(
            "\\{\\{[^}]*\\bconfig\\s*\\((.+?)\\)[^}]*\\}\\}", Pattern.DOTALL);

    private static final Pattern KV = Pattern.compile(
            "([a-zA-Z_]\\w*)\\s*=\\s*(?:" +
            "'([^']*)'|\"([^\"]*)\"|" +     // quoted strings
            "(\\[.*?\\])|(\\{.*?\\})|" +     // list/dict literals
            "([a-zA-Z_]\\w*)|(\\d+))",       // identifiers/numbers
            Pattern.DOTALL);

    private ConfigExtractor() {}

    public static Map<String, String> extract(String sql) {
        if (sql == null || sql.isBlank()) return Map.of();
        var block = CONFIG_BLOCK.matcher(sql);
        if (!block.find()) return Map.of();

        var config = new LinkedHashMap<String, String>();
        var kv = KV.matcher(block.group(1));
        while (kv.find()) {
            String key = kv.group(1);
            String val = firstNonNull(kv.group(2), kv.group(3), kv.group(4),
                    kv.group(5), kv.group(6), kv.group(7));
            if (val != null) config.put(key, val);
        }
        return Collections.unmodifiableMap(config);
    }

    private static String firstNonNull(String... vs) {
        for (var v : vs) if (v != null) return v;
        return null;
    }
}
