package com.meg.agentsdk.core;

import com.meg.agentsdk.config.AgentBuilderFactory;
import com.meg.agentsdk.prompt.PromptService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A typed Spring bean that delegates requests to its shared HarnessAgent.
 */
public abstract class Agent implements InitializingBean, DisposableBean {
    private AgentBuilderFactory factory;

    @Autowired
    final void configure(Environment environment, ObjectProvider<PromptService> prompts,
                         @Qualifier("reactAgentStateStore") ObjectProvider<AgentStateStore> stateStores) {

        factory = AgentBuilderFactory.fromAnnotation(ClassUtils.getUserClass(this), environment, prompts, stateStores);
    }

    @Override
    public final void afterPropertiesSet() {
        if (factory == null) {
            throw new IllegalStateException("Agent must be created by Spring");
        }
        factory.afterPropertiesSet();
    }


    /** Runs a chat request using the supplied conversation context. */
    public Mono<AgentResponse> askAsync(String query, RuntimeContext context) {
        return Mono.defer(() -> {

            if (query == null || query.isBlank())
                return Mono.<AgentResponse>error(new IllegalArgumentException("Query must not be blank"));

            if (context == null)
                return Mono.<AgentResponse>error(new IllegalArgumentException("Runtime context is required"));

            if (factory == null)
                return Mono.<AgentResponse>error(new IllegalStateException("Agent must be created by Spring"));

            return factory
                    .call(query, context)
                    .map(response -> new AgentResponse(response.getTextContent()));

        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public final void destroy() {
        if (factory != null) {
            factory.destroy();
        }
    }

}
