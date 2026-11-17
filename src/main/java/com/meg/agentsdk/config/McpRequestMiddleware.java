
package com.meg.agentsdk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;
import java.util.stream.Stream;

final class McpRequestMiddleware implements MiddlewareBase {

    static final String REQUEST_SOP = "react-agent.request-sop";
    private static final String REFERENCE_ID = "referenceId";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Mono<String> onSystemPrompt(
            Agent agent,
            RuntimeContext context,
            String basePrompt) {

        String referenceId = context.get(REFERENCE_ID, String.class);

        if (referenceId == null || referenceId.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "referenceId is required in the request"));
        }

        String sop = context.get(REQUEST_SOP, String.class);

        String prompt = Stream.of(
                        basePrompt,
                        sop,
                        """
                        TRUSTED REQUEST CONTEXT:
        
                        The referenceId is provided by the application.
                        Do not ask the user to provide it again.
        
                        Use this exact referenceId when invoking
                        tools that require it.
        
                        Follow the SOP and available MCP tool schemas.
                        Do not invent completed business operations.
                        """,
                        "referenceId = " + JSON.valueToTree(referenceId)
                )
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"));

        return Mono.just(prompt);
    }
}
