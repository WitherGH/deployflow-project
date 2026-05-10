package com.deployflow.core.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ColoringResult {
    private final boolean solved;
    private final int[] colors;
    private final int colorCount;
    private final PlannerMetrics metrics;
    private final List<String> trace;
    private final String message;

    public ColoringResult(boolean solved, int[] colors, int colorCount, PlannerMetrics metrics, List<String> trace, String message) {
        this.solved = solved;
        this.colors = colors == null ? new int[0] : Arrays.copyOf(colors, colors.length);
        this.colorCount = colorCount;
        this.metrics = metrics;
        this.trace = List.copyOf(trace == null ? new ArrayList<>() : trace);
        this.message = message;
    }

    public boolean solved() {
        return solved;
    }

    public int[] colors() {
        return Arrays.copyOf(colors, colors.length);
    }

    public int colorCount() {
        return colorCount;
    }

    public PlannerMetrics metrics() {
        return metrics;
    }

    public List<String> trace() {
        return trace;
    }

    public String message() {
        return message;
    }
}
