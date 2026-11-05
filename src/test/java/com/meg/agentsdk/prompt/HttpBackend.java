package com.meg.agentsdk.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Local test double for lakeFS, an OpenAI-compatible model gateway, and MCP. */
final class HttpBackend implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ObjectMapper json = new ObjectMapper();
    final AtomicInteger initializations = new AtomicInteger(), toolCalls = new AtomicInteger();
    final AtomicInteger downloads = new AtomicInteger(), commits = new AtomicInteger();
    final List<String> modelRequests = new CopyOnWriteArrayList<>();
    final List<String> uploadBodies = new CopyOnWriteArrayList<>();
    final Map<String,String> prompts = new ConcurrentHashMap<>();
    final List<String> errors = new CopyOnWriteArrayList<>();
    volatile String downloadAuthorization;

    HttpBackend() throws Exception {
        prompts.put("c0", "System SOP");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/api/v1/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                downloadAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (exchange.getRequestMethod().equals("GET")) {
                    downloads.incrementAndGet();
                    String ref = path.split("/refs/")[1].split("/objects")[0];
                    String content = prompts.get(ref);
                    byte[] body = (content == null ? "missing" : content).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(content == null ? 404 : 200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
                } else if (path.endsWith("/commits")) {
                    exchange.getRequestBody().readAllBytes();
                    String commit = "c" + commits.incrementAndGet();
                    prompts.put(commit, "System SOP version " + commit);
                    byte[] body = json.writeValueAsBytes(Map.of("id", commit, "committer", "test",
                            "creation_date", 1, "message", "update", "meta_range_id", "x", "parents", List.of(), "metadata", Map.of()));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(201, body.length);
                    try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
                } else {
                    uploadBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] body = json.writeValueAsBytes(Map.of("path", "GST_OD.md", "checksum", "x",
                            "physical_address", "test", "mtime", 1, "size_bytes", 10, "path_type", "object"));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(201, body.length);
                    try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
                }
            } catch (Exception error) { errors.add(error.toString()); exchange.close(); }
        });
        server.createContext("/mcp", exchange -> {
            try {
                if (!exchange.getRequestMethod().equals("POST")) {
                    exchange.sendResponseHeaders(405, -1); exchange.close(); return;
                }
                JsonNode input = json.readTree(exchange.getRequestBody());
                if (!input.has("id")) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
                Object result = switch (input.path("method").asText()) {
                    case "initialize" -> {
                        initializations.incrementAndGet();
                        yield Map.of("protocolVersion", input.path("params").path("protocolVersion").asText(),
                                "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "test", "version", "1"));
                    }
                    case "tools/list" -> Map.of("tools", List.of(
                            Map.of("name", "get_current_status", "description", "Get status",
                                    "inputSchema", Map.of("type", "object", "properties", Map.of("request", Map.of("type", "string"))),
                                    "annotations", Map.of("readOnlyHint", false)),
                            Map.of("name", "verify_otp", "description", "Verify OTP",
                                    "inputSchema", Map.of("type", "object", "properties", Map.of()),
                                    "annotations", Map.of("readOnlyHint", false))));
                    case "tools/call" -> {
                        toolCalls.incrementAndGet();
                        yield Map.of("content", List.of(Map.of("type", "text",
                                "text", "status:" + input.path("params").path("arguments").path("request").asText())), "isError", false);
                    }
                    default -> Map.of();
                };
                byte[] body = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", input.get("id"), "result", result));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
            } catch (Exception error) { errors.add(error.toString()); exchange.close(); }
        });
        server.createContext("/v1", exchange -> {
            try {
                JsonNode input = json.readTree(exchange.getRequestBody());
                modelRequests.add(input.toString());
                String query = "", prompt = "";
                boolean hasToolResult = false;
                for (JsonNode message : input.path("messages")) {
                    if (message.path("role").asText().equals("user")) query = message.path("content").asText();
                    if (message.path("role").asText().equals("system")) prompt = message.path("content").asText();
                    if (message.path("role").asText().equals("tool")) hasToolResult = true;
                }
                boolean callTool = input.path("tools").size() > 0 && !hasToolResult;
                Object delta = callTool ? Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0,
                        "id", "call-" + query, "type", "function", "function", Map.of("name", input.path("tools").get(0).path("function").path("name").asText(),
                                "arguments", json.writeValueAsString(Map.of("request", query))))))
                        : Map.of("role", "assistant", "content", prompt + "|" + query);
                Map<String, Object> chunk = Map.of("id", "test", "object", "chat.completion.chunk", "created", 1,
                        "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta,
                                "finish_reason", callTool ? "tool_calls" : "stop")));
                byte[] body = ("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
            } catch (Exception error) { errors.add(error.toString()); exchange.close(); }
        });
        server.start();
    }

    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}



