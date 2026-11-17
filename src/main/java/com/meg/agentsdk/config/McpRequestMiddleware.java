package com.meg.agentsdk.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpTool;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class McpRequestMiddleware implements MiddlewareBase {
    static final String REQUEST_SOP = "react-agent.request-sop";
    private static final String REFERENCE_ID = "referenceId";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Toolkit toolkit;

    McpRequestMiddleware(Toolkit toolkit) {
        this.toolkit = toolkit;
    }
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String basePrompt) {

        String referenceId = context.get(REFERENCE_ID, String.class);

        if (referenceId == null || referenceId.isBlank()) {
            return Mono.error(new IllegalArgumentException("referenceId is required in the request"));
        }

        String sop = context.get(REQUEST_SOP, String.class);

        StringBuilder prompt = new StringBuilder();

        if (basePrompt != null && !basePrompt.isBlank()) {
            prompt.append(basePrompt);
        }

        if (sop != null && !sop.isBlank()) {
            if (!prompt.isEmpty()) {
                prompt.append("\n\n");
            }

            prompt.append(sop);
        }

        if (!prompt.isEmpty()) {
            prompt.append("\n\n");
        }

        prompt.append("""
        TRUSTED REQUEST CONTEXT:

        The referenceId is provided by the calling application.

        Do not ask the user to provide referenceId again.

        Use this referenceId when invoking tools that require it.

        Select MCP tools based on:
        - The SOP
        - The user's query
        - Available tool definitions

        Do not invent completed business operations.

        """);

        prompt.append("referenceId = ").append(JSON.valueToTree(referenceId).toString());

        return Mono.just(prompt.toString());
    }
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        String referenceId = context.get(REFERENCE_ID, String.class);
        return Flux.defer(() -> next.apply(new ActingInput(input.toolCalls().stream()
                .map(call -> bindReferenceId(call, referenceId))
                .toList())));
    }

    private ToolUseBlock bindReferenceId(ToolUseBlock call, String referenceId) {

        if (!(toolkit.getTool(call.getName()) instanceof McpTool tool)) {
            return call;
        }

        String rawArguments = call.getContent();
        try {
            if (rawArguments != null && !rawArguments.isBlank()
                    && !JSON.readTree(rawArguments).isObject()) {
                return call;
            }
        } catch (JsonProcessingException invalidArguments) {
            return call; // Preserve the existing invalid-argument handling.
        }

        Map<String, Object> arguments = call.getInput();
        Map<String, Object> schema = tool.getParameters();
        Map<String, Object> updated = bindObject(arguments, schema, schema, referenceId, Set.of());
        if (updated.equals(arguments)) {
            return call;
        }

        try {
            return new ToolUseBlock(call.getId(), call.getName(), updated, JSON.writeValueAsString(updated),
                    call.getMetadata(), call.getState());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize MCP arguments", error);
        }
    }

    private static Map<String, Object> bindObject(Map<String, Object> arguments, Map<?, ?> schema,
            Map<?, ?> root, String referenceId, Set<String> references) {
        Map<String, Object> result = new LinkedHashMap<>(arguments);
        if (schema.get("$ref") instanceof String ref && ref.startsWith("#/") && !references.contains(ref)) {
            Set<String> nextReferences = new HashSet<>(references);
            nextReferences.add(ref);
            if (resolveLocalReference(root, ref) instanceof Map<?, ?> target) {
                result = bindObject(result, target, root, referenceId, nextReferences);
            }
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> property : properties.entrySet()) {
                String name = (String) property.getKey();
                if (REFERENCE_ID.equals(name)) {
                    if (referenceId == null || referenceId.isBlank()) {
                        throw new IllegalArgumentException("referenceId is required in the request");
                    }
                    result.put(name, referenceId);
                } else if (property.getValue() instanceof Map<?, ?> childSchema) {
                    Object value = result.get(name);
                    if (value == null && result.containsKey(name)) {
                        continue;
                    }
                    Object updated = bindValue(value, childSchema, root, referenceId, references);
                    if (updated != null) {
                        result.put(name, updated);
                    }
                }
            }
        }
        if (schema.get("allOf") instanceof List<?> requirements) {
            for (Object requirement : requirements) {
                if (requirement instanceof Map<?, ?> branch) {
                    result = bindObject(result, branch, root, referenceId, references);
                }
            }
        }
        for (String alternatives : List.of("oneOf", "anyOf")) {
            if (schema.get(alternatives) instanceof List<?> branches) {
                Map<String, Object> candidate = null;
                for (Object choice : branches) {
                    if (choice instanceof Map<?, ?> branch && matchesDiscriminators(result, branch)) {
                        Map<String, Object> updated = bindObject(result, branch, root, referenceId, references);
                        if (candidate != null && !candidate.equals(updated)) {
                            throw new IllegalArgumentException("Ambiguous referenceId field in MCP tool schema");
                        }
                        candidate = updated;
                    }
                }
                if (candidate != null) {
                    result = candidate;
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object bindValue(Object value, Map<?, ?> schema, Map<?, ?> root, String referenceId,
            Set<String> references) {

        if (value instanceof Map<?, ?> object) {
            return bindObject((Map<String, Object>) object, schema, root, referenceId, Set.of());
        }

        if (value instanceof List<?> array && schema.get("items") instanceof Map<?, ?> itemSchema) {
            List<Object> updated = new ArrayList<>(array.size());
            for (Object item : array) {
                updated.add(item == null ? null : bindValue(item, itemSchema, root, referenceId, Set.of()));
            }
            return updated;
        }

        if (value == null) {
            Map<String, Object> missing = bindObject(Map.of(), schema, root, referenceId, references);
            return missing.isEmpty() ? null : missing;
        }
        return value;
    }

    private static boolean matchesDiscriminators(Map<?, ?> arguments, Map<?, ?> schema) {
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> property : properties.entrySet()) {
                Object value = arguments.get(property.getKey());
                if (value == null || REFERENCE_ID.equals(property.getKey()) || !(property.getValue() instanceof Map<?, ?> child)) {
                    continue;
                }
                if ((child.containsKey("const") && !value.equals(child.get("const")))
                        || (child.get("enum") instanceof List<?> values && !values.contains(value))
                        || (value instanceof Map<?, ?> object && !matchesDiscriminators(object, child))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Object resolveLocalReference(Map<?, ?> root, String ref) {
        Object current = root;
        for (String key : ref.substring(2).split("/", -1)) {
            current = current instanceof Map<?, ?> object ? object.get(key.replace("~1", "/").replace("~0", "~")) : null;
        }
        return current;
    }
}
