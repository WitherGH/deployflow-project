package com.deployflow.core.model;

import com.deployflow.core.util.MapReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record DeploymentTask(
        String id,
        String service,
        String team,
        String environment,
        RiskLevel risk,
        int durationMinutes,
        List<String> dependsOn,
        List<String> resources,
        List<String> tags
) {
    public DeploymentTask {
        id = normalizeId(id, service);
        service = requireText(service, "Unnamed service");
        team = requireText(team, "Unassigned");
        environment = requireText(environment, "production");
        risk = risk == null ? RiskLevel.MEDIUM : risk;
        durationMinutes = Math.max(5, durationMinutes);
        dependsOn = List.copyOf(clean(dependsOn));
        resources = List.copyOf(clean(resources));
        tags = List.copyOf(clean(tags));
    }

    public static DeploymentTask fromMap(Map<String, Object> object) {
        return new DeploymentTask(
                MapReader.stringValue(object, "id", ""),
                MapReader.stringValue(object, "service", "Unnamed service"),
                MapReader.stringValue(object, "team", "Unassigned"),
                MapReader.stringValue(object, "environment", "production"),
                RiskLevel.from(MapReader.stringValue(object, "risk", "medium")),
                MapReader.intValue(object, "durationMinutes", MapReader.intValue(object, "duration", 30)),
                MapReader.stringList(object, "dependsOn"),
                MapReader.stringList(object, "resources"),
                MapReader.stringList(object, "tags")
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("service", service);
        map.put("team", team);
        map.put("environment", environment);
        map.put("risk", risk.name().toLowerCase(Locale.ROOT));
        map.put("riskLabel", risk.label());
        map.put("riskWeight", risk.weight());
        map.put("durationMinutes", durationMinutes);
        map.put("dependsOn", dependsOn);
        map.put("resources", resources);
        map.put("tags", tags);
        return map;
    }

    public boolean hasResourceOverlap(DeploymentTask other) {
        return resources.stream().anyMatch(resource -> other.resources.contains(resource));
    }

    public boolean dependsOn(DeploymentTask possibleDependency) {
        return dependsOn.stream().anyMatch(dependency ->
                sameDependency(dependency, possibleDependency.id())
                        || sameDependency(dependency, possibleDependency.service())
                        || sameDependency(normalizeId(dependency, dependency), possibleDependency.id()));
    }

    private static String normalizeId(String id, String service) {
        String base = (id == null || id.isBlank()) ? service : id;
        if (base == null || base.isBlank()) {
            base = "service";
        }
        String normalized = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "service" : normalized;
    }

    private static String requireText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<String> clean(List<String> input) {
        if (input == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String item : input) {
            if (item != null) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private static boolean sameDependency(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof DeploymentTask other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
