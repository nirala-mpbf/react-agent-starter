package com.meg.agentsdk.core;

import com.meg.agentsdk.annotation.ReactAgentRouter;
import com.meg.agentsdk.config.AgentBuilderFactoryBean;
import com.meg.agentsdk.prompt.PromptService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A Spring-managed configuration. Each execution owns a fresh HarnessAgent.
 */
public abstract class Agent implements InitializingBean, DisposableBean {
    private AgentBuilderFactoryBean factory;

    @Autowired
    final void configure(Environment environment, ObjectProvider<PromptService> prompts) {

        ReactAgentRouter annotationDetails = AnnotatedElementUtils.findMergedAnnotation(ClassUtils.getUserClass(this), ReactAgentRouter.class);

        if (annotationDetails == null) {
            throw new IllegalStateException("Agent subclasses require @ReactAgentRouter");
        }

        String name = environment.resolveRequiredPlaceholders(annotationDetails.name()).trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Agent name must not be blank");
        }

        factory = new AgentBuilderFactoryBean(
                    name,
                    annotationDetails.model(),
                    Arrays.stream(annotationDetails.mcp())
                        .map(server -> new AgentBuilderFactoryBean.Endpoint(server.name(), server.url()))
                        .toList(),
                    List.of(annotationDetails.allowedTools()),
                    environment
                );

        factory.setWorkflow(annotationDetails.workflow());
        factory.setPrompt(annotationDetails.prompt());
        factory.setPromptServices(prompts);
    }

    @Override
    public final void afterPropertiesSet() {
        if (factory == null) {
            throw new IllegalStateException("Agent must be created by Spring");
        }
        factory.afterPropertiesSet();
    }


    // synchronous
    public AgentResponse ask(String query) {
        requireBlockingThread();
        return askAsync(query).block();
    }

    public AgentResponse ask(String query, RuntimeContext context) {
        requireBlockingThread();
        return askAsync(query, context).block();
    }


    private static void requireBlockingThread() {
        if (Schedulers.isInNonBlockingThread()) {
            throw new IllegalStateException("Use askAsync on reactive event-loop threads");
        }
    }

    //Reactive
    public Mono<AgentResponse> askAsync(String query) {
        return Mono.defer(() -> askAsync(query, RuntimeContext.builder()
                .sessionId(UUID.randomUUID().toString())
                .build()));
    }

    /**
     * Preserves caller context without adding conversation memory.
     */
    public Mono<AgentResponse> askAsync(String query, RuntimeContext context) {
        return Mono.defer(() -> {
            if (query == null || query.isBlank())
                return Mono.<AgentResponse>error(new IllegalArgumentException("Query must not be blank"));
            if (context == null)
                return Mono.<AgentResponse>error(new IllegalArgumentException("Runtime context is required"));
            if (factory == null)
                return Mono.<AgentResponse>error(new IllegalStateException("Agent must be created by Spring"));
            return Mono.using(() -> factory.getObject().build(),
                    agent -> agent.call(new UserMessage(query), context)
                            .map(reply -> new AgentResponse(reply.getTextContent())),
                    HarnessAgent::close);
            // Prompt storage calls are blocking; keep them off reactive event-loop threads.
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public final void destroy() {
        if (factory != null) factory.destroy();
    }
}
