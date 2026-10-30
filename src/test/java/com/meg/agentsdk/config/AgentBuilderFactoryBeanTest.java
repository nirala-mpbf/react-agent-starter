package com.meg.agentsdk.config;

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

class AgentBuilderFactoryBeanTest {
    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("agent.model", "test-model")
                .withProperty("agent.gateway.base-url", "http://localhost:1/v1")
                .withProperty("agent.gateway.api-key", "test");
    }

    private McpClientWrapper client(String name, String... names) throws Exception {
        var client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn(name);
        when(client.initialize()).thenReturn(Mono.empty());
        var tools = new ArrayList<McpSchema.Tool>();
        for (String tool : names) {
            tools.add(new ObjectMapper().readValue("""
                    {"name":"%s","description":"test","inputSchema":{"type":"object","properties":{}}}
                    """.formatted(tool), McpSchema.Tool.class));
        }
        when(client.listTools()).thenReturn(Mono.just(tools));
        return client;
    }

    private AgentBuilderFactoryBean factory(List<AgentBuilderFactoryBean.Endpoint> endpoints,
            List<String> tools, MockEnvironment env, McpClientWrapper... clients) {
        var queue = new ArrayDeque<>(List.of(clients));
        return new AgentBuilderFactoryBean("test", "", endpoints, tools, env) {
            @Override protected McpClientWrapper getMcpClientWrapper(Endpoint endpoint, Duration timeout) {
                return queue.removeFirst();
            }
        };
    }

    private AgentBuilderFactoryBean.Endpoint endpoint(String name) {
        return new AgentBuilderFactoryBean.Endpoint(name, "http://localhost:1/mcp");
    }

    @Test void preservesAdvertisedNamesAndFiltersOnlyRequestedTools() throws Exception {
        var client = client("brain", "get_current_status", "verify_otp");
        var factory = factory(List.of(endpoint("brain")), List.of("get_current_status"), environment(), client);
        factory.afterPropertiesSet();
        try {
            try (var agent = factory.getObject().sysPrompt("one").build()) {
                assertThat(agent.getToolkit().getToolNames()).containsExactly("get_current_status");
            }
            try (var agent = factory.getObject().sysPrompt("two").build()) {
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
        var client = client("brain", "get_current_status", "verify_otp");
        var factory = factory(List.of(endpoint("brain")), List.of(), environment(), client);
        factory.afterPropertiesSet();
        try (var agent = factory.getObject().build()) {
            assertThat(agent.getToolkit().getToolNames()).containsExactlyInAnyOrder("get_current_status", "verify_otp");
            assertThat(factory.configurePermissions().getAllowRules()).isEmpty();
        } finally { factory.destroy(); }
    }

    @Test void noMcpMeansNoToolsIncludingBuiltInTools() {
        var factory = factory(List.of(), List.of(), environment());
        factory.afterPropertiesSet();
        try (var agent = factory.getObject().build()) {
            assertThat(agent.getToolkit().getToolNames()).isEmpty();
        } finally { factory.destroy(); }
    }

    @Test void executionApprovalsAreExplicitAndIndependent() throws Exception {
        var env = environment().withProperty("react-agent.permissions.allow[0]", "verify_otp");
        var factory = factory(List.of(endpoint("brain")), List.of(), env,
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
        var client = client("brain", "get_current_status");
        var factory = factory(List.of(endpoint("brain")), List.of("not_discovered"), environment(), client);
        assertThatThrownBy(factory::afterPropertiesSet).hasMessageContaining("undiscovered");
        verify(client).close();
    }

    @Test void invalidApprovalClosesConnectionsDuringFailedStartup() throws Exception {
        var client = client("brain", "get_current_status");
        var env = environment().withProperty("react-agent.permissions.allow[0]", "not_discovered");
        var factory = factory(List.of(endpoint("brain")), List.of(), env, client);
        assertThatThrownBy(factory::afterPropertiesSet).hasMessageContaining("unavailable");
        verify(client).close();
    }

    @Test void supportsMultipleServersWithDistinctActualToolNames() throws Exception {
        var brain = client("brain", "get_current_status");
        var lentra = client("lentra", "submit_profile");
        var factory = factory(List.of(endpoint("brain"), endpoint("lentra")),
                List.of("get_current_status", "submit_profile"), environment(), brain, lentra);
        factory.afterPropertiesSet();
        try (var agent = factory.getObject().build()) {
            assertThat(agent.getToolkit().getToolNames()).containsExactlyInAnyOrder("get_current_status", "submit_profile");
        } finally { factory.destroy(); }
        verify(brain).close();
        verify(lentra).close();
    }

    @Test void rejectsDuplicateToolNamesAndClosesEveryOpenedClient() throws Exception {
        var first = client("brain", "status");
        var second = client("lentra", "status");
        var factory = factory(List.of(endpoint("brain"), endpoint("lentra")), List.of(), environment(), first, second);
        assertThatThrownBy(factory::afterPropertiesSet).hasMessageContaining("Duplicate MCP tool name");
        verify(first).close();
        verify(second).close();
    }

    @Test void discoveryFailureClosesTheFailingAndEarlierClients() throws Exception {
        var first = client("brain", "status");
        var second = client("lentra", "submit");
        when(second.listTools()).thenReturn(Mono.error(new IllegalStateException("server disconnected")));
        var factory = factory(List.of(endpoint("brain"), endpoint("lentra")), List.of(), environment(), first, second);
        assertThatThrownBy(factory::afterPropertiesSet).hasMessageContaining("server disconnected");
        verify(first).close();
        verify(second).close();
    }

    @Test void closingOneClientDoesNotPreventClosingTheOthers() throws Exception {
        var first = client("brain", "status");
        var second = client("lentra", "submit");
        doThrow(new IllegalStateException("close failed")).when(first).close();
        var factory = factory(List.of(endpoint("brain"), endpoint("lentra")), List.of(), environment(), first, second);
        factory.afterPropertiesSet();
        factory.destroy();
        verify(first).close();
        verify(second).close();
    }

    @Test void validatesEndpointsAndTimeoutBeforeConnecting() {
        assertThatThrownBy(() -> factory(List.of(endpoint("same"), endpoint("same")),
                List.of(), environment()).afterPropertiesSet()).hasMessageContaining("Duplicate MCP server");
        assertThatThrownBy(() -> factory(List.of(new AgentBuilderFactoryBean.Endpoint("brain", "file:/bad")),
                List.of(), environment()).afterPropertiesSet()).hasMessageContaining("HTTP(S)");
        assertThatThrownBy(() -> factory(List.of(), List.of(),
                environment().withProperty("react-agent.mcp.timeout", "0s")).afterPropertiesSet())
                .hasMessageContaining("must be positive");
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @com.meg.agentsdk.annotation.ReactAgentRouter(name = "live", model = "${agent.model}",
        mcp = @com.meg.agentsdk.annotation.McpServer(name = "brain", url = "${test.mcp-url}"),
        allowedTools = "get_current_status")
    static class LiveConfiguration {}

    @Test void realHttpMcpToolCallWorksAcrossFreshAgents() throws Exception {
        var mapper = new ObjectMapper();
        var toolCalls = new java.util.concurrent.atomic.AtomicInteger();
        var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, -1); exchange.close(); return;
            }
            var input = mapper.readTree(exchange.getRequestBody());
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
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.createContext("/v1", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            requests.add(body);
            int count = modelCalls.incrementAndGet();
            boolean callTool = count % 2 == 1;
            Object delta = callTool
                    ? Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "call-" + count,
                            "type", "function", "function", Map.of("name", "get_current_status", "arguments", "{}"))))
                    : Map.of("role", "assistant", "content", "Your status is OTP_PENDING.");
            var chunk = Map.of("id", "test-" + count, "object", "chat.completion.chunk", "created", 1,
                    "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta,
                            "finish_reason", callTool ? "tool_calls" : "stop")));
            byte[] data = ("data: " + mapper.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withUserConfiguration(LiveConfiguration.class)
                    .withPropertyValues("agent.model=test-model", "agent.gateway.api-key=test",
                            "agent.gateway.base-url=" + base + "/v1", "test.mcp-url=" + base + "/mcp",
                            "react-agent.permissions.allow[0]=get_current_status", "react-agent.mcp.timeout=5s")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var provider = context.getBeanProvider(io.agentscope.harness.agent.HarnessAgent.Builder.class);
                        for (int i = 0; i < 2; i++) {
                            try (var agent = provider.getObject().sysPrompt("Use the status tool.").build()) {
                                var reply = agent.call(new io.agentscope.core.message.UserMessage("What is my status?"),
                                        io.agentscope.core.agent.RuntimeContext.builder()
                                                .userId("user").sessionId("session-" + i).build()).block(Duration.ofSeconds(15));
                                assertThat(reply.getTextContent()).contains("OTP_PENDING");
                            }
                        }
                    });
            assertThat(toolCalls.get()).isEqualTo(2);
            assertThat(modelCalls.get()).isEqualTo(4);
            assertThat(requests.get(1)).contains("OTP_PENDING");
            assertThat(requests.get(0)).doesNotContain("mcp__brain__", "read_file", "wait_async_results");
        } finally { server.stop(0); }
    }



    @Test void discoveryTimeoutClosesTheConnection() throws Exception {
        var client = client("brain", "status");
        when(client.listTools()).thenReturn(Mono.never());
        var factory = factory(List.of(endpoint("brain")), List.of(),
                environment().withProperty("react-agent.mcp.timeout", "20ms"), client);
        assertThatThrownBy(factory::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
        verify(client).close();
    }
}

