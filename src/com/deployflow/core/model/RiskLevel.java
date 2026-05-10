package com.deployflow.core.model;

public enum RiskLevel {
    LOW(1, "Low"),
    MEDIUM(2, "Medium"),
    HIGH(3, "High"),
    CRITICAL(4, "Critical");

    private final int weight;
    private final String label;

    RiskLevel(int weight, String label) {
        this.weight = weight;
        this.label = label;
    }

    public int weight() {
        return weight;
    }

    public String label() {
        return label;
    }

    public static RiskLevel from(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM;
        }
        return switch (value.trim().toLowerCase()) {
            case "low", "minor" -> LOW;
            case "high", "major" -> HIGH;
            case "critical", "blocker", "urgent" -> CRITICAL;
            default -> MEDIUM;
        };
    }
}
