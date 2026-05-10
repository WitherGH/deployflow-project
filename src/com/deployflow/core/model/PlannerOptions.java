package com.deployflow.core.model;

import com.deployflow.core.util.MapReader;

import java.util.Locale;
import java.util.Map;

public record PlannerOptions(
        int maxWindows,
        String startTime,
        int windowMinutes,
        AlgorithmMode mode
) {
    public PlannerOptions {
        maxWindows = Math.max(1, Math.min(8, maxWindows));
        startTime = startTime == null || startTime.isBlank() ? "09:30" : startTime;
        windowMinutes = Math.max(15, Math.min(180, windowMinutes));
        mode = mode == null ? AlgorithmMode.IMPROVED : mode;
    }

    public static PlannerOptions fromMap(Map<String, Object> object) {
        return new PlannerOptions(
                MapReader.intValue(object, "maxWindows", 4),
                MapReader.stringValue(object, "startTime", "09:30"),
                MapReader.intValue(object, "windowMinutes", 45),
                AlgorithmMode.from(MapReader.stringValue(object, "algorithm", "improved"))
        );
    }

    public enum AlgorithmMode {
        CLASSIC,
        IMPROVED;

        public static AlgorithmMode from(String value) {
            if (value == null) {
                return IMPROVED;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "classic", "classical", "backtracking" -> CLASSIC;
                default -> IMPROVED;
            };
        }
    }
}
