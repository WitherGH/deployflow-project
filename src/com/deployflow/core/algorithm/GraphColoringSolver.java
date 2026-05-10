package com.deployflow.core.algorithm;

import com.deployflow.core.model.ColoringResult;
import com.deployflow.core.model.DeploymentGraph;
import com.deployflow.core.model.DeploymentTask;
import com.deployflow.core.model.PlannerMetrics;
import com.deployflow.core.model.PlannerOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class GraphColoringSolver {
    private static final int TRACE_LIMIT = 160;

    /**
     * Classical graph-coloring backtracking function.
     * Vertices are colored in their original order and only the adjacency matrix is checked.
     */
    public ColoringResult solveClassic(boolean[][] graph, int maxColors) {
        int vertexCount = graph == null ? 0 : graph.length;
        PlannerMetrics metrics = new PlannerMetrics();
        metrics.setAlgorithmName("Classical backtracking");
        metrics.setGraphStats(vertexCount, countEdges(graph), 0);
        List<String> trace = new ArrayList<>();
        long startedAt = System.nanoTime();

        if (vertexCount == 0) {
            metrics.setElapsedNanos(System.nanoTime() - startedAt);
            return new ColoringResult(true, new int[0], 0, metrics, trace, "Nothing to schedule.");
        }

        for (int colorCount = 1; colorCount <= maxColors; colorCount++) {
            int[] colors = new int[vertexCount];
            metrics.setColorsTried(colorCount);
            addTrace(trace, "Trying " + colorCount + " deployment window" + (colorCount == 1 ? "" : "s") + ".");
            if (classicColorVertex(0, graph, colors, colorCount, metrics, trace)) {
                metrics.setElapsedNanos(System.nanoTime() - startedAt);
                return new ColoringResult(true, colors, colorCount, metrics, trace, "Feasible coloring found.");
            }
        }

        metrics.setElapsedNanos(System.nanoTime() - startedAt);
        return new ColoringResult(false, new int[vertexCount], maxColors, metrics, trace,
                "No feasible coloring within the selected number of windows.");
    }

    /**
     * Domain-aware deployment planner. It still uses graph coloring, but adds chronological
     * dependency constraints and, in improved mode, MRV/degree ordering with forward checking.
     */
    public ColoringResult solveDeployment(DeploymentGraph graph, int maxColors, PlannerOptions.AlgorithmMode mode) {
        PlannerMetrics metrics = new PlannerMetrics();
        metrics.setGraphStats(graph.size(), graph.conflictEdgeCount(), graph.precedenceRuleCount());
        metrics.setAlgorithmName(mode == PlannerOptions.AlgorithmMode.CLASSIC
                ? "Classical deployment backtracking"
                : "Improved MRV + degree backtracking");
        List<String> trace = new ArrayList<>();
        long startedAt = System.nanoTime();

        if (graph.size() == 0) {
            metrics.setElapsedNanos(System.nanoTime() - startedAt);
            return new ColoringResult(true, new int[0], 0, metrics, trace, "Nothing to schedule.");
        }

        for (int colorCount = 1; colorCount <= maxColors; colorCount++) {
            int[] colors = new int[graph.size()];
            metrics.setColorsTried(colorCount);
            addTrace(trace, "Trying plan with " + colorCount + " release window" + (colorCount == 1 ? "" : "s") + ".");
            boolean solved = mode == PlannerOptions.AlgorithmMode.CLASSIC
                    ? colorByFixedOrder(0, graph, colors, colorCount, metrics, trace)
                    : colorByHeuristicOrder(graph, colors, colorCount, metrics, trace);
            if (solved) {
                metrics.setElapsedNanos(System.nanoTime() - startedAt);
                return new ColoringResult(true, colors, colorCount, metrics, trace, "Deployment plan generated.");
            }
        }

        metrics.setElapsedNanos(System.nanoTime() - startedAt);
        return new ColoringResult(false, new int[graph.size()], maxColors, metrics, trace,
                "No safe deployment plan exists within the selected windows.");
    }

    private boolean classicColorVertex(int vertex, boolean[][] graph, int[] colors, int colorCount,
                                       PlannerMetrics metrics, List<String> trace) {
        metrics.countRecursiveCall();
        if (vertex == colors.length) {
            return true;
        }
        for (int color = 1; color <= colorCount; color++) {
            if (isClassicSafe(vertex, color, graph, colors, metrics)) {
                colors[vertex] = color;
                addTrace(trace, "Vertex " + vertex + " → color " + color + ".");
                if (classicColorVertex(vertex + 1, graph, colors, colorCount, metrics, trace)) {
                    return true;
                }
                colors[vertex] = 0;
                metrics.countBacktrack();
                addTrace(trace, "Backtrack from vertex " + vertex + ".");
            }
        }
        return false;
    }

    private boolean colorByFixedOrder(int position, DeploymentGraph graph, int[] colors, int colorCount,
                                      PlannerMetrics metrics, List<String> trace) {
        metrics.countRecursiveCall();
        if (position == colors.length) {
            return true;
        }
        int vertex = position;
        for (int color = 1; color <= colorCount; color++) {
            if (isDeploymentSafe(vertex, color, graph, colors, metrics)) {
                colors[vertex] = color;
                addTrace(trace, label(graph, vertex) + " → Window " + color + ".");
                if (colorByFixedOrder(position + 1, graph, colors, colorCount, metrics, trace)) {
                    return true;
                }
                colors[vertex] = 0;
                metrics.countBacktrack();
                addTrace(trace, "Backtrack: " + label(graph, vertex) + " removed from Window " + color + ".");
            }
        }
        return false;
    }

    private boolean colorByHeuristicOrder(DeploymentGraph graph, int[] colors, int colorCount,
                                          PlannerMetrics metrics, List<String> trace) {
        metrics.countRecursiveCall();
        int vertex = selectMostConstrainedVertex(graph, colors, colorCount, metrics);
        if (vertex == -1) {
            return true;
        }

        for (int color : orderColorsByLeastConstrainingValue(vertex, graph, colors, colorCount, metrics)) {
            if (isDeploymentSafe(vertex, color, graph, colors, metrics)) {
                colors[vertex] = color;
                addTrace(trace, label(graph, vertex) + " → Window " + color + " selected by heuristic.");
                if (hasFutureOptions(graph, colors, colorCount, metrics)
                        && colorByHeuristicOrder(graph, colors, colorCount, metrics, trace)) {
                    return true;
                }
                colors[vertex] = 0;
                metrics.countBacktrack();
                addTrace(trace, "Backtrack: " + label(graph, vertex) + " cannot stay in Window " + color + ".");
            }
        }
        return false;
    }

    private int selectMostConstrainedVertex(DeploymentGraph graph, int[] colors, int colorCount, PlannerMetrics metrics) {
        int bestVertex = -1;
        int bestOptionCount = Integer.MAX_VALUE;
        int bestDegree = -1;
        int bestRisk = -1;
        for (int vertex = 0; vertex < colors.length; vertex++) {
            if (colors[vertex] != 0) {
                continue;
            }
            int options = 0;
            for (int color = 1; color <= colorCount; color++) {
                if (isDeploymentSafe(vertex, color, graph, colors, metrics)) {
                    options++;
                }
            }
            int degree = graph.degree(vertex);
            int risk = graph.tasks().get(vertex).risk().weight();
            if (options < bestOptionCount
                    || (options == bestOptionCount && degree > bestDegree)
                    || (options == bestOptionCount && degree == bestDegree && risk > bestRisk)) {
                bestVertex = vertex;
                bestOptionCount = options;
                bestDegree = degree;
                bestRisk = risk;
            }
        }
        return bestVertex;
    }

    private List<Integer> orderColorsByLeastConstrainingValue(int vertex, DeploymentGraph graph, int[] colors,
                                                              int colorCount, PlannerMetrics metrics) {
        List<Integer> candidates = new ArrayList<>();
        for (int color = 1; color <= colorCount; color++) {
            if (isDeploymentSafe(vertex, color, graph, colors, metrics)) {
                candidates.add(color);
            }
        }
        candidates.sort(Comparator.comparingInt(color -> constraintScore(vertex, color, graph, colors, colorCount, metrics)));
        return candidates;
    }

    private int constraintScore(int vertex, int candidateColor, DeploymentGraph graph, int[] colors,
                                int colorCount, PlannerMetrics metrics) {
        int[] copy = colors.clone();
        copy[vertex] = candidateColor;
        int score = 0;
        for (int other = 0; other < copy.length; other++) {
            if (copy[other] != 0 || (!graph.conflicts(vertex, other)
                    && !graph.mustRunBefore(vertex, other)
                    && !graph.mustRunBefore(other, vertex))) {
                continue;
            }
            int options = 0;
            for (int color = 1; color <= colorCount; color++) {
                if (isDeploymentSafe(other, color, graph, copy, metrics)) {
                    options++;
                }
            }
            score -= options;
        }
        return score;
    }

    private boolean hasFutureOptions(DeploymentGraph graph, int[] colors, int colorCount, PlannerMetrics metrics) {
        for (int vertex = 0; vertex < colors.length; vertex++) {
            if (colors[vertex] != 0) {
                continue;
            }
            metrics.countForwardCheck();
            boolean hasOption = false;
            for (int color = 1; color <= colorCount; color++) {
                if (isDeploymentSafe(vertex, color, graph, colors, metrics)) {
                    hasOption = true;
                    break;
                }
            }
            if (!hasOption) {
                return false;
            }
        }
        return true;
    }

    private boolean isClassicSafe(int vertex, int color, boolean[][] graph, int[] colors, PlannerMetrics metrics) {
        metrics.countSafetyCheck();
        for (int other = 0; other < graph.length; other++) {
            if (graph[vertex][other] && colors[other] == color) {
                return false;
            }
        }
        return true;
    }

    private boolean isDeploymentSafe(int vertex, int candidateColor, DeploymentGraph graph, int[] colors,
                                     PlannerMetrics metrics) {
        metrics.countSafetyCheck();
        for (int other = 0; other < colors.length; other++) {
            if (other == vertex || colors[other] == 0) {
                continue;
            }
            if (graph.conflicts(vertex, other) && colors[other] == candidateColor) {
                return false;
            }
            if (graph.mustRunBefore(vertex, other) && candidateColor >= colors[other]) {
                return false;
            }
            if (graph.mustRunBefore(other, vertex) && colors[other] >= candidateColor) {
                return false;
            }
        }
        return true;
    }

    private static int countEdges(boolean[][] graph) {
        if (graph == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < graph.length; i++) {
            for (int j = i + 1; j < graph.length; j++) {
                if (graph[i][j]) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void addTrace(List<String> trace, String event) {
        if (trace.size() < TRACE_LIMIT) {
            trace.add(event);
        } else if (trace.size() == TRACE_LIMIT) {
            trace.add("Trace paused after " + TRACE_LIMIT + " events to keep the UI readable.");
        }
    }

    private static String label(DeploymentGraph graph, int vertex) {
        DeploymentTask task = graph.tasks().get(vertex);
        return task.service() + " (" + task.risk().label().toLowerCase(Locale.ROOT) + ")";
    }
}
