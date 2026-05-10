package com.deployflow.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeploymentGraph {
    private final List<DeploymentTask> tasks;
    private final boolean[][] conflicts;
    private final boolean[][] before;
    private final Map<String, List<ConflictReason>> reasons;

    public DeploymentGraph(List<DeploymentTask> tasks, boolean[][] conflicts, boolean[][] before, Map<String, List<ConflictReason>> reasons) {
        this.tasks = List.copyOf(tasks);
        this.conflicts = copyMatrix(conflicts);
        this.before = copyMatrix(before);
        this.reasons = new LinkedHashMap<>();
        for (Map.Entry<String, List<ConflictReason>> entry : reasons.entrySet()) {
            this.reasons.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
    }

    public List<DeploymentTask> tasks() {
        return tasks;
    }

    public int size() {
        return tasks.size();
    }

    public boolean conflicts(int i, int j) {
        return conflicts[i][j];
    }

    public boolean mustRunBefore(int i, int j) {
        return before[i][j];
    }

    public int degree(int vertex) {
        int degree = 0;
        for (int i = 0; i < size(); i++) {
            if (conflicts[vertex][i]) {
                degree++;
            }
        }
        return degree;
    }

    public int conflictEdgeCount() {
        int count = 0;
        for (int i = 0; i < size(); i++) {
            for (int j = i + 1; j < size(); j++) {
                if (conflicts[i][j]) {
                    count++;
                }
            }
        }
        return count;
    }

    public int precedenceRuleCount() {
        int count = 0;
        for (int i = 0; i < size(); i++) {
            for (int j = 0; j < size(); j++) {
                if (before[i][j]) {
                    count++;
                }
            }
        }
        return count;
    }

    public List<ConflictReason> reasonsBetween(int i, int j) {
        return reasons.getOrDefault(edgeKey(i, j), Collections.emptyList());
    }

    public Map<String, Object> toMap(int[] colors) {
        Map<String, Object> graph = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < size(); i++) {
            Map<String, Object> node = new LinkedHashMap<>(tasks.get(i).toMap());
            node.put("index", i);
            node.put("color", colors == null || i >= colors.length ? 0 : colors[i]);
            node.put("degree", degree(i));
            nodes.add(node);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (int i = 0; i < size(); i++) {
            for (int j = i + 1; j < size(); j++) {
                if (conflicts[i][j]) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("from", tasks.get(i).id());
                    edge.put("to", tasks.get(j).id());
                    edge.put("fromIndex", i);
                    edge.put("toIndex", j);
                    edge.put("reasons", reasonsBetween(i, j).stream().map(ConflictReason::toMap).toList());
                    edges.add(edge);
                }
            }
        }
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }

    private static boolean[][] copyMatrix(boolean[][] matrix) {
        if (matrix == null) {
            return new boolean[0][0];
        }
        boolean[][] copy = new boolean[matrix.length][];
        for (int i = 0; i < matrix.length; i++) {
            copy[i] = matrix[i].clone();
        }
        return copy;
    }

    public static String edgeKey(int a, int b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
    }
}
