package com.deployflow.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record ConflictReason(String type, String detail) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("detail", detail);
        return map;
    }
}
