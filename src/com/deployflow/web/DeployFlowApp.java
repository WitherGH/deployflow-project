/*
 * DeployFlow - Software Deployment Window Planner
 * Driver program entry point.
 * Authors: Artem Pasichnyk, Yaroslav Kondratenko
 * Date: May 2026
 */
package com.deployflow.web;

import com.deployflow.core.data.SampleCatalog;
import com.deployflow.core.model.DeploymentTask;
import com.deployflow.core.model.PlannerOptions;
import com.deployflow.core.planner.DeploymentPlanner;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class DeployFlowApp {
    private static final int DEFAULT_PORT = 8080;
    private static final String PORT_ENV = "PORT";
    private static final Path WEB_ROOT = Path.of("resources", "web");
    private final DeploymentPlanner planner = new DeploymentPlanner();

    public static void main(String[] args) throws Exception {
        DeployFlowApp app = new DeployFlowApp();
        if (args.length > 0 && "--demo".equalsIgnoreCase(args[0])) {
            app.runConsoleDemo();
            return;
        }
        int port = args.length > 0 ? parsePort(args[0]) : parsePort(System.getenv(PORT_ENV));
        app.startServer(port);
    }

    private void startServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/api/catalog", this::handleCatalog);
        server.createContext("/api/plan", this::handlePlan);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("DeployFlow is running at http://localhost:" + port);
        System.out.println("Use `java -cp out com.deployflow.web.DeployFlowApp --demo` for console execution.");
    }

    private void runConsoleDemo() {
        List<DeploymentTask> sample = SampleCatalog.defaultTasks().subList(0, 8);
        PlannerOptions options = new PlannerOptions(5, "09:30", 45, PlannerOptions.AlgorithmMode.IMPROVED);
        Map<String, Object> result = planner.plan(sample, options);
        System.out.println("DeployFlow console demo");
        System.out.println("Solved: " + result.get("solved"));
        System.out.println("Message: " + result.get("message"));
        System.out.println("Algorithm: " + result.get("algorithm"));
        System.out.println("Windows used: " + result.get("windowCountUsed"));
        for (Object windowValue : Json.asList(result.get("windows"))) {
            Map<String, Object> window = Json.asObject(windowValue);
            System.out.println("\n" + window.get("name") + " " + window.get("start") + "-" + window.get("end"));
            for (Object deploymentValue : Json.asList(window.get("deployments"))) {
                Map<String, Object> deployment = Json.asObject(deploymentValue);
                System.out.println("  - " + deployment.get("service") + " [" + deployment.get("team") + ", " + deployment.get("riskLabel") + "]");
            }
        }
        System.out.println("\nMetrics: " + Json.stringify(result.get("metrics")));
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "ok");
        health.put("product", "DeployFlow");
        sendJson(exchange, 200, health);
    }

    private void handleCatalog(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "GET required"));
            return;
        }
        List<Map<String, Object>> tasks = SampleCatalog.defaultTasks().stream()
                .map(DeploymentTask::toMap)
                .toList();
        sendJson(exchange, 200, Map.of("tasks", tasks));
    }

    private void handlePlan(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            addCors(exchange.getResponseHeaders());
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "POST required"));
            return;
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> request = Json.asObject(Json.parse(body));
            List<DeploymentTask> tasks = parseTasks(request);
            PlannerOptions options = PlannerOptions.fromMap(request);
            Map<String, Object> result = planner.plan(tasks, options);
            sendJson(exchange, 200, result);
        } catch (RuntimeException exception) {
            sendJson(exchange, 400, Map.of(
                    "error", "Invalid planning request",
                    "detail", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()
            ));
        }
    }

    private List<DeploymentTask> parseTasks(Map<String, Object> request) {
        List<DeploymentTask> tasks = new ArrayList<>();
        for (Object value : Json.asList(request.get("tasks"))) {
            Map<String, Object> taskMap = Json.asObject(value);
            boolean selected = Json.booleanValue(taskMap, "selected", true);
            if (selected) {
                tasks.add(DeploymentTask.fromMap(taskMap));
            }
        }
        return tasks;
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8");
            return;
        }
        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath.equals("/") || requestPath.isBlank()) {
            requestPath = "/index.html";
        }
        Path file = WEB_ROOT.resolve(requestPath.substring(1)).normalize();
        if (!file.startsWith(WEB_ROOT) || !Files.exists(file) || Files.isDirectory(file)) {
            sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(file));
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object value) throws IOException {
        sendText(exchange, statusCode, Json.stringify(value), "application/json; charset=utf-8");
    }

    private void sendText(HttpExchange exchange, int statusCode, String text, String contentType) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        addCors(headers);
        headers.set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void addCors(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private static int parsePort(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535 ? port : DEFAULT_PORT;
        } catch (NumberFormatException ignored) {
            return DEFAULT_PORT;
        }
    }
}
