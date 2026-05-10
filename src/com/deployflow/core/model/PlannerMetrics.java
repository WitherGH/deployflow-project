package com.deployflow.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PlannerMetrics {
    private long recursiveCalls;
    private long backtracks;
    private long safetyChecks;
    private long forwardChecks;
    private long elapsedNanos;
    private int colorsTried;
    private int vertexCount;
    private int conflictEdges;
    private int precedenceRules;
    private String algorithmName = "Backtracking";

    public void countRecursiveCall() {
        recursiveCalls++;
    }

    public void countBacktrack() {
        backtracks++;
    }

    public void countSafetyCheck() {
        safetyChecks++;
    }

    public void countForwardCheck() {
        forwardChecks++;
    }

    public void setElapsedNanos(long elapsedNanos) {
        this.elapsedNanos = elapsedNanos;
    }

    public void setColorsTried(int colorsTried) {
        this.colorsTried = colorsTried;
    }

    public void setGraphStats(int vertexCount, int conflictEdges, int precedenceRules) {
        this.vertexCount = vertexCount;
        this.conflictEdges = conflictEdges;
        this.precedenceRules = precedenceRules;
    }

    public void setAlgorithmName(String algorithmName) {
        this.algorithmName = algorithmName;
    }

    public long recursiveCalls() {
        return recursiveCalls;
    }

    public long backtracks() {
        return backtracks;
    }

    public long safetyChecks() {
        return safetyChecks;
    }

    public long forwardChecks() {
        return forwardChecks;
    }

    public long elapsedNanos() {
        return elapsedNanos;
    }

    public int colorsTried() {
        return colorsTried;
    }

    public String algorithmName() {
        return algorithmName;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("algorithm", algorithmName);
        map.put("recursiveCalls", recursiveCalls);
        map.put("backtracks", backtracks);
        map.put("safetyChecks", safetyChecks);
        map.put("forwardChecks", forwardChecks);
        map.put("elapsedMs", Math.round(elapsedNanos / 1_000_000.0 * 100.0) / 100.0);
        map.put("colorsTried", colorsTried);
        map.put("vertexCount", vertexCount);
        map.put("conflictEdges", conflictEdges);
        map.put("precedenceRules", precedenceRules);
        double density = vertexCount <= 1 ? 0 : (2.0 * conflictEdges) / (vertexCount * (vertexCount - 1));
        map.put("density", Math.round(density * 1000.0) / 1000.0);
        return map;
    }
}
