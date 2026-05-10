package com.deployflow.core.planner;

import com.deployflow.core.algorithm.GraphColoringSolver;
import com.deployflow.core.model.ColoringResult;
import com.deployflow.core.model.ConflictReason;
import com.deployflow.core.model.DeploymentGraph;
import com.deployflow.core.model.DeploymentTask;
import com.deployflow.core.model.PlannerOptions;
import com.deployflow.core.model.RiskLevel;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public final class DeploymentPlanner {
    private final GraphColoringSolver solver = new GraphColoringSolver();

    public Map<String, Object> plan(List<DeploymentTask> requestedTasks, PlannerOptions options) {
        List<DeploymentTask> tasks = deduplicate(requestedTasks);
        DeploymentGraph graph = buildGraph(tasks);
        ColoringResult result = solver.solveDeployment(graph, options.maxWindows(), options.mode());
        int[] colors = result.colors();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("product", "DeployFlow");
        response.put("solved", result.solved());
        response.put("message", result.message());
        response.put("algorithm", result.metrics().algorithmName());
        response.put("windowCountUsed", result.solved() ? result.colorCount() : 0);
        response.put("maxWindows", options.maxWindows());
        response.put("graph", graph.toMap(colors));
        response.put("conflicts", buildConflictList(graph));
        response.put("metrics", result.metrics().toMap());
        response.put("trace", result.trace());
        response.put("warnings", findWarnings(graph));

        if (result.solved()) {
            response.put("windows", buildWindows(graph, colors, result.colorCount(), options));
            response.put("runbook", buildRunbook(graph, colors, result.colorCount()));
            response.put("summary", buildSummary(graph, colors, result.colorCount()));
        } else {
            response.put("windows", List.of());
            response.put("runbook", List.of());
            response.put("summary", Map.of(
                    "headline", "No safe plan with current windows",
                    "nextAction", "Increase the number of windows or move one high-risk deployment to another day."
            ));
            response.put("suggestions", List.of(
                    "Add one more deployment window and run the planner again.",
                    "Remove one critical update from today's batch.",
                    "Check whether same-team deployments can be handled by a backup owner.",
                    "Split database migrations from service rollouts."
            ));
        }
        return response;
    }

    public DeploymentGraph buildGraph(List<DeploymentTask> rawTasks) {
        List<DeploymentTask> tasks = deduplicate(rawTasks);
        int n = tasks.size();
        boolean[][] conflicts = new boolean[n][n];
        boolean[][] before = new boolean[n][n];
        Map<String, List<ConflictReason>> reasons = new LinkedHashMap<>();

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                DeploymentTask a = tasks.get(i);
                DeploymentTask b = tasks.get(j);
                List<ConflictReason> pairReasons = new ArrayList<>();

                if (sameText(a.team(), b.team())) {
                    pairReasons.add(new ConflictReason("team", "Both deployments are owned by " + a.team() + "."));
                }

                List<String> sharedResources = sharedValues(a.resources(), b.resources());
                if (!sharedResources.isEmpty()) {
                    pairReasons.add(new ConflictReason("resource", "Shared resource: " + String.join(", ", sharedResources) + "."));
                }

                if (a.dependsOn(b)) {
                    pairReasons.add(new ConflictReason("dependency", a.service() + " depends on " + b.service() + "."));
                    before[j][i] = true;
                }
                if (b.dependsOn(a)) {
                    pairReasons.add(new ConflictReason("dependency", b.service() + " depends on " + a.service() + "."));
                    before[i][j] = true;
                }

                if (isHighRiskPair(a, b)) {
                    pairReasons.add(new ConflictReason("risk", "Two high-blast-radius releases should not run in parallel."));
                }

                if (!pairReasons.isEmpty()) {
                    conflicts[i][j] = true;
                    conflicts[j][i] = true;
                    reasons.put(DeploymentGraph.edgeKey(i, j), pairReasons);
                }
            }
        }
        return new DeploymentGraph(tasks, conflicts, before, reasons);
    }

    private List<Map<String, Object>> buildWindows(DeploymentGraph graph, int[] colors, int colorCount, PlannerOptions options) {
        List<Map<String, Object>> windows = new ArrayList<>();
        LocalTime start = parseTime(options.startTime());
        for (int color = 1; color <= colorCount; color++) {
            Map<String, Object> window = new LinkedHashMap<>();
            LocalTime windowStart = start.plusMinutes((long) (color - 1) * options.windowMinutes());
            LocalTime windowEnd = windowStart.plusMinutes(options.windowMinutes());
            List<Map<String, Object>> deployments = new ArrayList<>();
            for (int i = 0; i < graph.size(); i++) {
                if (colors[i] == color) {
                    Map<String, Object> item = new LinkedHashMap<>(graph.tasks().get(i).toMap());
                    item.put("window", color);
                    item.put("sequenceHint", sequenceHint(graph.tasks().get(i)));
                    deployments.add(item);
                }
            }
            deployments.sort(Comparator
                    .comparingInt((Map<String, Object> map) -> ((Number) map.get("riskWeight")).intValue()).reversed()
                    .thenComparing(map -> String.valueOf(map.get("team")))
                    .thenComparing(map -> String.valueOf(map.get("service"))));
            window.put("index", color);
            window.put("name", "Window " + color);
            window.put("start", windowStart.format(DateTimeFormatter.ofPattern("HH:mm")));
            window.put("end", windowEnd.format(DateTimeFormatter.ofPattern("HH:mm")));
            window.put("deployments", deployments);
            window.put("loadMinutes", deployments.stream()
                    .mapToInt(map -> ((Number) map.get("durationMinutes")).intValue())
                    .max().orElse(0));
            window.put("parallelCount", deployments.size());
            window.put("tone", windowTone(deployments));
            windows.add(window);
        }
        return windows;
    }

    private List<Map<String, Object>> buildRunbook(DeploymentGraph graph, int[] colors, int colorCount) {
        List<Map<String, Object>> runbook = new ArrayList<>();
        int step = 1;
        for (int color = 1; color <= colorCount; color++) {
            List<Integer> indexes = new ArrayList<>();
            for (int i = 0; i < graph.size(); i++) {
                if (colors[i] == color) {
                    indexes.add(i);
                }
            }
            indexes.sort(Comparator
                    .comparingInt((Integer i) -> graph.tasks().get(i).risk().weight()).reversed()
                    .thenComparing(i -> graph.tasks().get(i).service()));
            for (int index : indexes) {
                DeploymentTask task = graph.tasks().get(index);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("step", step++);
                item.put("window", color);
                item.put("service", task.service());
                item.put("team", task.team());
                item.put("risk", task.risk().label());
                item.put("action", actionFor(task));
                runbook.add(item);
            }
        }
        return runbook;
    }

    private Map<String, Object> buildSummary(DeploymentGraph graph, int[] colors, int colorCount) {
        int critical = 0;
        int high = 0;
        Set<String> teams = new LinkedHashSet<>();
        for (DeploymentTask task : graph.tasks()) {
            teams.add(task.team());
            if (task.risk() == RiskLevel.CRITICAL) {
                critical++;
            }
            if (task.risk() == RiskLevel.HIGH) {
                high++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("headline", colorCount + " safe release window" + (colorCount == 1 ? "" : "s") + " generated");
        summary.put("deployments", graph.size());
        summary.put("teams", teams.size());
        summary.put("critical", critical);
        summary.put("high", high);
        summary.put("conflicts", graph.conflictEdgeCount());
        summary.put("nextAction", "Review the timeline, then share the runbook with owners.");
        return summary;
    }

    private List<Map<String, Object>> buildConflictList(DeploymentGraph graph) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (int i = 0; i < graph.size(); i++) {
            for (int j = i + 1; j < graph.size(); j++) {
                if (graph.conflicts(i, j)) {
                    Map<String, Object> conflict = new LinkedHashMap<>();
                    conflict.put("from", graph.tasks().get(i).id());
                    conflict.put("fromService", graph.tasks().get(i).service());
                    conflict.put("to", graph.tasks().get(j).id());
                    conflict.put("toService", graph.tasks().get(j).service());
                    conflict.put("reasons", graph.reasonsBetween(i, j).stream().map(ConflictReason::toMap).toList());
                    conflicts.add(conflict);
                }
            }
        }
        return conflicts;
    }

    private List<String> findWarnings(DeploymentGraph graph) {
        List<String> warnings = new ArrayList<>();
        if (hasPrecedenceCycle(graph)) {
            warnings.add("Dependency cycle detected. The planner will reject impossible chronological orders.");
        }
        if (graph.conflictEdgeCount() > graph.size() * 2 && graph.size() > 4) {
            warnings.add("This is a dense deployment day. Expect more windows or move non-urgent releases.");
        }
        return warnings;
    }

    private boolean hasPrecedenceCycle(DeploymentGraph graph) {
        int n = graph.size();
        int[] indegree = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (graph.mustRunBefore(i, j)) {
                    indegree[j]++;
                }
            }
        }
        Queue<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (indegree[i] == 0) {
                queue.add(i);
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            int current = queue.remove();
            visited++;
            for (int next = 0; next < n; next++) {
                if (graph.mustRunBefore(current, next)) {
                    indegree[next]--;
                    if (indegree[next] == 0) {
                        queue.add(next);
                    }
                }
            }
        }
        return visited != n;
    }

    private List<DeploymentTask> deduplicate(List<DeploymentTask> tasks) {
        Map<String, DeploymentTask> byId = new LinkedHashMap<>();
        if (tasks != null) {
            for (DeploymentTask task : tasks) {
                if (task != null) {
                    byId.put(task.id(), task);
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static boolean sameText(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static List<String> sharedValues(List<String> a, List<String> b) {
        Set<String> normalized = new HashSet<>();
        for (String item : a) {
            normalized.add(item.toLowerCase(Locale.ROOT));
        }
        List<String> shared = new ArrayList<>();
        for (String item : b) {
            if (normalized.contains(item.toLowerCase(Locale.ROOT))) {
                shared.add(item);
            }
        }
        return shared;
    }

    private static boolean isHighRiskPair(DeploymentTask a, DeploymentTask b) {
        return a.risk().weight() >= RiskLevel.HIGH.weight() && b.risk().weight() >= RiskLevel.HIGH.weight();
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException ignored) {
            return LocalTime.of(9, 30);
        }
    }

    private static String sequenceHint(DeploymentTask task) {
        if (task.dependsOn().isEmpty()) {
            return "Can start when the window opens";
        }
        return "Wait until " + String.join(", ", task.dependsOn()) + " is confirmed healthy";
    }

    private static String windowTone(List<Map<String, Object>> deployments) {
        boolean hasCritical = deployments.stream().anyMatch(map -> "critical".equals(map.get("risk")));
        boolean hasHigh = deployments.stream().anyMatch(map -> "high".equals(map.get("risk")));
        if (hasCritical) {
            return "critical";
        }
        if (hasHigh) {
            return "high";
        }
        return deployments.size() > 2 ? "busy" : "calm";
    }

    private static String actionFor(DeploymentTask task) {
        return switch (task.risk()) {
            case CRITICAL -> "Run with incident channel open and verify dashboards immediately.";
            case HIGH -> "Deploy after owner check-in and watch shared resources.";
            case MEDIUM -> "Deploy, smoke-test, and hand off status.";
            case LOW -> "Deploy in parallel lane and confirm automated checks.";
        };
    }
}
