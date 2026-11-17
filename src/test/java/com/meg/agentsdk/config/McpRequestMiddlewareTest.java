
package com.meg.agentsdk.config;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class McpRequestMiddlewareTest {

    private final McpRequestMiddleware middleware =
            new McpRequestMiddleware(null);

    @Test
    void shouldIncludeReferenceIdInSystemPrompt() {

        RuntimeContext context =
                RuntimeContext.builder().build();

        context.put("referenceId", "OTP-05");

        context.put(
                McpRequestMiddleware.REQUEST_SOP,
                "Use registered MCP tools to check status."
        );

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

        RuntimeContext context =
                RuntimeContext.builder().build();

        context.put(
                McpRequestMiddleware.REQUEST_SOP,
                "Example SOP"
        );

        assertThatThrownBy(() ->
                middleware.onSystemPrompt(
                        null,
                        context,
                        "Base prompt"
                ).block()
        ).hasMessageContaining(
                "referenceId is required"
        );
    }
}
