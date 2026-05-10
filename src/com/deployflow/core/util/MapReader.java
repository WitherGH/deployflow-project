package com.deployflow.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads typed values from generic maps created from JSON requests.
 * Keeping this helper in core prevents model classes from depending on web code.
 */
public final class MapReader {
    private MapReader() {
    }

    public static String stringValue(Map<String, Object> object, String key, String fallback) {
        Object value = object.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public static int intValue(Map<String, Object> object, String key, int fallback) {
        Object value = object.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static List<String> stringList(Map<String, Object> object, String key) {
        List<String> result = new ArrayList<>();
        Object value = object.get(key);
        if (!(value instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }
}
