
package com.meg.agentsdk.config;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class McpRequestMiddlewareTest {

    private final McpRequestMiddleware middleware =
            new McpRequestMiddleware();

    @Test
    void shouldIncludeReferenceIdInSystemPrompt() {

        RuntimeContext context = RuntimeContext.builder()
                .put("referenceId", "OTP-05")
                .put(
                        McpRequestMiddleware.REQUEST_SOP,
                        "Use registered MCP tools to check status."
                )
                .build();

        String prompt = middleware.onSystemPrompt(
                null,
                context,
                "Harness base instructions"
        ).block();

        assertThat(prompt)
                .contains("Harness base instructions")
                .contains("Use registered MCP tools")
                .contains("referenceId = \"OTP-05\"")
                .contains("Do not ask the user");
    }

    @Test
    void shouldRejectMissingReferenceId() {

        RuntimeContext context = RuntimeContext.builder()
                .put(
                        McpRequestMiddleware.REQUEST_SOP,
                        "Example SOP"
                )
                .build();

        assertThatThrownBy(() ->
                middleware.onSystemPrompt(
                        null,
                        context,
                        "Base prompt"
                ).block()
        ).hasMessageContaining("referenceId is required");
    }

    @Test
    void shouldKeepReferenceIdsSeparateAcrossRequests() {

        RuntimeContext firstContext = RuntimeContext.builder()
                .put("referenceId", "OTP-01")
                .put(McpRequestMiddleware.REQUEST_SOP, "First SOP")
                .build();

        RuntimeContext secondContext = RuntimeContext.builder()
                .put("referenceId", "OTP-02")
                .put(McpRequestMiddleware.REQUEST_SOP, "Second SOP")
                .build();

        String firstPrompt = middleware.onSystemPrompt(
                null, firstContext, "Base prompt"
        ).block();

        String secondPrompt = middleware.onSystemPrompt(
                null, secondContext, "Base prompt"
        ).block();

        assertThat(firstPrompt)
                .contains("OTP-01")
                .contains("First SOP")
                .doesNotContain("OTP-02", "Second SOP");

        assertThat(secondPrompt)
                .contains("OTP-02")
                .contains("Second SOP")
                .doesNotContain("OTP-01", "First SOP");
    }
}
