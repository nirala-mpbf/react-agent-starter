package com.meg.agentsdk.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.harness.agent.HarnessAgent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.OutputStream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentBuilderFactoryTest {
    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("agent.model", "test-model")
                .withProperty("agent.gateway.base-url", "http://localhost:1/v1")
                .withProperty("agent.gateway.api-key", "test");
    }

    private McpClientWrapper client(String name, String... names) throws Exception {
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn(name);
        when(client.initialize()).thenReturn(Mono.empty());
        ArrayList<Tool> tools = new ArrayList<McpSchema.Tool>();
        for (String tool : names) {
            tools.add(new ObjectMapper().readValue("""
                    {"name":"%s","description":"test","inputSchema":{"type":"object","properties":{}}}
                    """.formatted(tool), McpSchema.Tool.class));
        }
        when(client.listTools()).thenReturn(Mono.just(tools));
        return client;
    }

    private AgentBuilderFactory factory(List<AgentBuilderFactory.Endpoint> endpoints,
            List<String> tools, MockEnvironment env, McpClientWrapper... clients) {
        ArrayDeque<McpClientWrapper> queue = new ArrayDeque<>(List.of(clients));
        return new AgentBuilderFactory("test", "", endpoints, tools, env) {
            @Override protected McpClientWrapper getMcpClientWrapper(Endpoint endpoint, Duration timeout) {
                return queue.removeFirst();
            }
        };
    }

    private AgentBuilderFactory.Endpoint endpoint(String name) {
        return new AgentBuilderFactory.Endpoint(name, "http://localhost:1/mcp");
    }

    @Test void preservesAdvertisedNamesAndFiltersOnlyRequestedTools() throws Exception {
        McpClientWrapper client = client("brain", "get_current_status", "verify_otp");
        AgentBuilderFactory factory = factory(List.of(endpoint("brain")), List.of("get_current_status"), environment(), client);
        factory.afterPropertiesSet();
        try {
            try (HarnessAgent agent = factory.buildWithPrompt(null, null).sysPrompt("one").build()) {
                assertThat(agent.getToolkit().getToolNames()).containsExactly("get_current_status");
            }
            try (HarnessAgent agent = factory.buildWithPrompt(null, null).sysPrompt("two").build()) {
                assertThat(agent.getToolkit().getToolNames()).containsExactly("get_current_status");
            }
            verify(client, never()).close();
        } finally {
            factory.destroy();
            factory.destroy();
        }
        verify(client, times(1)).close();
    }

    @Test void omittedFilterExposesAllMcpToolsWithoutAddingApprovals() throws Exception {
        McpClientWrapper client = client("brain", "get_current_status", "verify_otp");
        AgentBuilderFactory factory = factory(List.of(endpoint("brain")), List.of(), environment(), client);
        factory.afterPropertiesSet();
        try (HarnessAgent agent = factory.buildWithPrompt(null, null).build()) {
            assertThat(agent.getToolkit().getToolNames()).containsExactlyInAnyOrder("get_current_status", "verify_otp");
            assertThat(factory.configurePermissions().getAllowRules()).isEmpty();
        } finally { factory.destroy(); }
    }

    @Test void noMcpMeansNoToolsIncludingBuiltInTools() {
        AgentBuilderFactory factory = factory(List.of(), List.of(), environment());
        factory.afterPropertiesSet();
        try (HarnessAgent agent = factory.buildWithPrompt(null, null).build()) {
            assertThat(agent.getToolkit().getToolNames()).isEmpty();
        } finally { factory.destroy(); }
    }

    @Test void executionApprovalsAreExplicitAndIndependent() throws Exception {
        MockEnvironment env = environment().withProperty("react-agent.permissions.allow[0]", "verify_otp");
        AgentBuilderFactory factory = factory(List.of(endpoint("brain")), List.of(), env,
                client("brain", "verify_otp", "get_current_status"));
        factory.afterPropertiesSet();
        try {
            assertThat(factory.configurePermissions().getAllowRules().values().stream()
                    .flatMap(List::stream).map(rule -> rule.toolName())).containsExactly("verify_otp");
            env.setProperty("react-agent.permissions.allow[0]", "unknown");
            assertThatThrownBy(factory::configurePermissions).hasMessageContaining("unavailable tool");
        } finally { factory.destroy(); }
    }

    @Test void invalidVisibilityClosesConnectionsDuringFailedStartup() throws Exception {
        McpClientWrapper client = client("brain", "get_current_status");
        AgentBuilderFactory factory = factory(List.of(endpoint("brain")), List.of("not_discovered"), environment(), client);
        assertThatThrownBy(factory::afterPropertiesSet).hasMessageContaining("undiscovered");
        verify(client).close();
    }

    @Test void invalidApprovalClosesConnectionsDuringFailedStartup() throws Exception {
        McpClientWrapper client = client("brain", "get_current_status");
        MockEnvironment env = environment().withProperty("react-agent.permissions.allow[0]", "not_discovered");
        AgentBuilderFactory factory = factory(List.of(endpoint("brain")), List.of(), env, client);
        assertThatThrownBy(factory::afterPropertiesSet).hasMessageContaining("unavailable");
        verify(client).close();
    }

    @Test void supportsMultipleServersWithDistinctActualToolNames() throws Exception {
        McpClientWrapper brain = client("brain", "get_current_status");
        McpClientWrapper lentra = client("lentra", "submit_profile");
        AgentBuilderFactory factory = factory(List.of(endpoint("brain"), endpoint("lentra")),
                List.of("get_current_status", "submit_profile"), environment(), brain, lentra);
        factory.afterPropertiesSet();
        try (HarnessAgent agent = factory.buildWithPrompt(null, null).build()) {
            assertThat(agent.getToolkit().getToolNames()).containsExactlyInAnyOrder("get_current_status", "submit_profile");
        } finally { factory.destroy(); }
        verify(brain).close();
        verify(lentra).close();
    }

    @Test void rejectsDuplicateToolNamesAndClosesEveryOpenedClient() throws Exception {
        McpClientWrapper first = client("brain", "status");
        McpClientWrapper second = client("lentra", "status");
        AgentBuilderFactory factory = factory(List.of(endpoint("brain"), endpoint("lentra")), List.of(), environment(), first, second);
        assertThatThrownBy(factory::afterPropertiesSet).hasMessageContaining("Duplicate MCP tool name");
        verify(first).close();
        verify(second).close();
    }

    @Test void discoveryFailureClosesTheFailingAndEarlierClients() throws Exception {
        McpClientWrapper first = client("brain", "status");
        McpClientWrapper second = client("lentra", "submit");
        when(second.listTools()).thenReturn(Mono.error(new IllegalStateException("server disconnected")));
        AgentBuilderFactory factory = factory(List.of(endpoint("brain"), endpoint("lentra")), List.of(), environment(), first, second);
        assertThatThrownBy(factory::afterPropertiesSet).hasMessageContaining("server disconnected");
        verify(first).close();
        verify(second).close();
    }

    @Test void closingOneClientDoesNotPreventClosingTheOthers() throws Exception {
        McpClientWrapper first = client("brain", "status");
        McpClientWrapper second = client("lentra", "submit");
        doThrow(new IllegalStateException("close failed")).when(first).close();
        AgentBuilderFactory factory = factory(List.of(endpoint("brain"), endpoint("lentra")), List.of(), environment(), first, second);
        factory.afterPropertiesSet();
        factory.destroy();
        verify(first).close();
        verify(second).close();
    }

    @Test void validatesEndpointsAndTimeoutBeforeConnecting() {
        assertThatThrownBy(() -> factory(List.of(endpoint("same"), endpoint("same")),
                List.of(), environment()).afterPropertiesSet()).hasMessageContaining("Duplicate MCP server");
        assertThatThrownBy(() -> factory(List.of(new AgentBuilderFactory.Endpoint("brain", "file:/bad")),
                List.of(), environment()).afterPropertiesSet()).hasMessageContaining("HTTP(S)");
        assertThatThrownBy(() -> factory(List.of(), List.of(),
                environment().withProperty("react-agent.mcp.timeout", "0s")).afterPropertiesSet())
                .hasMessageContaining("must be positive");
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @com.meg.agentsdk.annotation.ReactAgentRouter(name = "live", model = "${agent.model}",
        mcp = @com.meg.agentsdk.annotation.McpServer(name = "brain", url = "${test.mcp-url}"),
        allowedTools = "get_current_status")
    static class LiveConfiguration extends com.meg.agentsdk.core.Agent {}

    @Test void realHttpMcpToolCallWorksAcrossFreshAgents() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger toolCalls = new java.util.concurrent.atomic.AtomicInteger();
        AtomicInteger transientFailures = new AtomicInteger();
        AtomicInteger modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        CopyOnWriteArrayList<String> requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, -1); exchange.close(); return;
            }
            JsonNode input = mapper.readTree(exchange.getRequestBody());
            if (!input.has("id")) {
                exchange.sendResponseHeaders(202, -1); exchange.close(); return;
            }
            Object result = switch (input.path("method").asText()) {
                case "initialize" -> Map.of("protocolVersion", input.path("params").path("protocolVersion").asText(),
                        "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "test", "version", "1"));
                case "tools/list" -> Map.of("tools", List.of(Map.of("name", "get_current_status",
                        "description", "Get status", "inputSchema", Map.of("type", "object", "properties", Map.of()),
                        "annotations", Map.of("readOnlyHint", false))));
                case "tools/call" -> {
                    toolCalls.incrementAndGet();
                    yield Map.of("content", List.of(Map.of("type", "text", "text", "OTP_PENDING")), "isError", false);
                }
                default -> Map.of();
            };
            byte[] data = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", input.get("id"), "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(data); }
        });
        server.createContext("/v1", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (modelCalls.get() == 1 && transientFailures.compareAndSet(0, 1)) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            requests.add(body);
            int count = modelCalls.incrementAndGet();
            boolean callTool = count % 2 == 1;
            Object delta = callTool
                    ? Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "call-" + count,
                            "type", "function", "function", Map.of("name", "get_current_status", "arguments", "{}"))))
                    : Map.of("role", "assistant", "content", "Your status is OTP_PENDING.");
            Map<String, Object> chunk = Map.of("id", "test-" + count, "object", "chat.completion.chunk", "created", 1,
                    "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta,
                            "finish_reason", callTool ? "tool_calls" : "stop")));
            byte[] data = ("data: " + mapper.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(data); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withUserConfiguration(LiveConfiguration.class)
                    .withPropertyValues("agent.model=test-model", "agent.gateway.api-key=test",
                            "agent.gateway.base-url=" + base + "/v1", "test.mcp-url=" + base + "/mcp",
                            "react-agent.permissions.allow[0]=get_current_status", "react-agent.mcp.timeout=5s",
                            "react-agent.model.retry.max-attempts=2",
                            "react-agent.model.retry.initial-backoff=10ms",
                            "react-agent.model.retry.max-backoff=20ms")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        LiveConfiguration agent = context.getBean(LiveConfiguration.class);
                        for (int i = 0; i < 2; i++) {
                            io.agentscope.core.agent.RuntimeContext execution = io.agentscope.core.agent.RuntimeContext.builder()
                                    .userId("user").sessionId("session-" + i).build();
                            assertThat(agent.askAsync("What is my status?", execution, "Use the status tool.")
                                    .block(Duration.ofSeconds(15)).content()).contains("OTP_PENDING");
                        }
                    });
            assertThat(transientFailures.get()).isEqualTo(1);
            assertThat(toolCalls.get()).isEqualTo(2);
            assertThat(modelCalls.get()).isEqualTo(4);
            assertThat(requests.get(1)).contains("OTP_PENDING");
            assertThat(requests.get(0)).doesNotContain("mcp__brain__", "read_file", "wait_async_results");
        } finally { server.stop(0); }
    }



    @Test void discoveryTimeoutClosesTheConnection() throws Exception {
        McpClientWrapper client = client("brain", "status");
        when(client.listTools()).thenReturn(Mono.never());
        AgentBuilderFactory factory = factory(List.of(endpoint("brain")), List.of(),
                environment().withProperty("react-agent.mcp.timeout", "20ms"), client);
        assertThatThrownBy(factory::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
        verify(client).close();
    }
}

