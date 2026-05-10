package com.deployflow.core.data;

import com.deployflow.core.model.DeploymentTask;
import com.deployflow.core.model.RiskLevel;

import java.util.List;

public final class SampleCatalog {
    private SampleCatalog() {
    }

    public static List<DeploymentTask> defaultTasks() {
        return List.of(
                new DeploymentTask(
                        "feature-flags",
                        "Feature Flags",
                        "Platform",
                        "production",
                        RiskLevel.LOW,
                        15,
                        List.of(),
                        List.of("config-store", "edge-cache"),
                        List.of("config", "safe-rollout")
                ),
                new DeploymentTask(
                        "identity-api",
                        "Identity API",
                        "Platform",
                        "production",
                        RiskLevel.HIGH,
                        40,
                        List.of("feature-flags"),
                        List.of("identity-db", "redis-cluster"),
                        List.of("auth", "customer-facing")
                ),
                new DeploymentTask(
                        "payments-gateway",
                        "Payments Gateway",
                        "Payments",
                        "production",
                        RiskLevel.CRITICAL,
                        55,
                        List.of("identity-api"),
                        List.of("payments-db", "redis-cluster", "pci-vault"),
                        List.of("money-flow", "pci")
                ),
                new DeploymentTask(
                        "checkout-web",
                        "Checkout Web",
                        "Commerce",
                        "production",
                        RiskLevel.HIGH,
                        35,
                        List.of("payments-gateway", "inventory-service"),
                        List.of("edge-cache", "checkout-cdn"),
                        List.of("frontend", "conversion")
                ),
                new DeploymentTask(
                        "inventory-service",
                        "Inventory Service",
                        "Commerce",
                        "production",
                        RiskLevel.MEDIUM,
                        30,
                        List.of("feature-flags"),
                        List.of("inventory-db", "warehouse-queue"),
                        List.of("stock", "warehouse")
                ),
                new DeploymentTask(
                        "orders-api",
                        "Orders API",
                        "Commerce",
                        "production",
                        RiskLevel.MEDIUM,
                        28,
                        List.of("inventory-service"),
                        List.of("orders-db", "warehouse-queue"),
                        List.of("orders", "backend")
                ),
                new DeploymentTask(
                        "notifications-worker",
                        "Notifications Worker",
                        "Messaging",
                        "production",
                        RiskLevel.LOW,
                        20,
                        List.of("orders-api"),
                        List.of("email-provider", "events-bus"),
                        List.of("async", "email")
                ),
                new DeploymentTask(
                        "search-indexer",
                        "Search Indexer",
                        "Data",
                        "production",
                        RiskLevel.MEDIUM,
                        45,
                        List.of("inventory-service"),
                        List.of("search-cluster", "events-bus"),
                        List.of("batch", "search")
                ),
                new DeploymentTask(
                        "analytics-pipeline",
                        "Analytics Pipeline",
                        "Data",
                        "production",
                        RiskLevel.MEDIUM,
                        60,
                        List.of("orders-api"),
                        List.of("warehouse-db", "events-bus"),
                        List.of("etl", "dashboards")
                ),
                new DeploymentTask(
                        "mobile-api",
                        "Mobile API",
                        "Experience",
                        "production",
                        RiskLevel.HIGH,
                        38,
                        List.of("identity-api", "orders-api"),
                        List.of("mobile-edge", "redis-cluster"),
                        List.of("mobile", "customer-facing")
                ),
                new DeploymentTask(
                        "support-portal",
                        "Support Portal",
                        "Experience",
                        "production",
                        RiskLevel.LOW,
                        18,
                        List.of("identity-api"),
                        List.of("support-cdn", "config-store"),
                        List.of("internal", "portal")
                ),
                new DeploymentTask(
                        "billing-reports",
                        "Billing Reports",
                        "Finance Ops",
                        "production",
                        RiskLevel.LOW,
                        25,
                        List.of("payments-gateway"),
                        List.of("warehouse-db", "billing-store"),
                        List.of("reporting", "finance")
                )
        );
    }
}
