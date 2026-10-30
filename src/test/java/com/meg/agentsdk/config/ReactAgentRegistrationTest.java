package com.meg.agentsdk.config;

import com.meg.agentsdk.annotation.ReactAgentRouter;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReactAgentRegistrationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withPropertyValues(
                "agent.model=test-model", "agent.gateway.base-url=http://localhost:1/v1",
                "agent.gateway.api-key=test");
    }

    @Configuration(proxyBeanMethods = false)
    @ReactAgentRouter
    static class Defaults {}

    @Configuration(proxyBeanMethods = false)
    @ReactAgentRouter(name = "${app.agent-name}", model = "${app.model}")
    static class Placeholders {}

    @Configuration(proxyBeanMethods = false)
    @ReactAgentRouter(name = "other")
    static class Other {}

    @Configuration(proxyBeanMethods = false)
    @ReactAgentRouter
    static class Duplicate {}

    @Test void registersBuilderAndProvidesFreshInstancesWithoutMcp() {
        runner().withUserConfiguration(Defaults.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(HarnessAgent.Builder.class);
            ObjectProvider<HarnessAgent.Builder> provider = context.getBeanProvider(HarnessAgent.Builder.class);
            assertThat(provider.getObject()).isNotSameAs(provider.getObject());
            assertThat(context.getBean("reactAgentBuilder")).isInstanceOf(HarnessAgent.Builder.class);
        });
    }

    @Test void resolvesNameAndModelPlaceholders() {
        runner().withPropertyValues("app.agent-name=onboarding", "app.model=custom-model")
                .withUserConfiguration(Placeholders.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("onboardingBuilder")).isInstanceOf(HarnessAgent.Builder.class);
                });
    }

    @Test void registersMultipleNamedAgents() {
        runner().withUserConfiguration(Defaults.class, Other.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(HarnessAgent.Builder.class))
                    .containsOnlyKeys("reactAgentBuilder", "otherBuilder");
        });
    }

    @Test void rejectsDuplicateAgentNames() {
        runner().withUserConfiguration(Defaults.class, Duplicate.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void customFactoryHooksCanBeRegisteredAsASpringBean() {
        var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        var permissionCalls = new java.util.concurrent.atomic.AtomicInteger();
        runner().withInitializer(context -> ((org.springframework.context.support.GenericApplicationContext) context).registerBean("customBuilder", AgentBuilderFactoryBean.class,
                () -> new AgentBuilderFactoryBean("custom", "", List.of(), List.of(), context.getEnvironment()) {
                    @Override protected io.agentscope.core.model.Model createModel() {
                        modelCalls.incrementAndGet();
                        return super.createModel();
                    }
                    @Override protected io.agentscope.core.permission.PermissionContextState configurePermissions() {
                        permissionCalls.incrementAndGet();
                        return super.configurePermissions();
                    }
                })).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("customBuilder")).isInstanceOf(HarnessAgent.Builder.class);
                    assertThat(modelCalls.get()).isEqualTo(1);
                    assertThat(permissionCalls.get()).isGreaterThanOrEqualTo(2);
                });
    }

    @Test void modelMustBeConfiguredSomewhere() {
        new ApplicationContextRunner().withUserConfiguration(Defaults.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }

    @Test void springContextShutdownClosesSharedConnections() {
        var client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("brain");
        when(client.initialize()).thenReturn(Mono.empty());
        when(client.listTools()).thenReturn(Mono.just(List.of()));
        runner().withInitializer(context -> ((org.springframework.context.support.GenericApplicationContext) context).registerBean("manualBuilder", AgentBuilderFactoryBean.class,
                () -> new AgentBuilderFactoryBean("manual", "",
                        List.of(new AgentBuilderFactoryBean.Endpoint("brain", "http://localhost:1/mcp")),
                        List.of(), context.getEnvironment()) {
                    @Override protected McpClientWrapper getMcpClientWrapper(Endpoint endpoint, Duration timeout) {
                        return client;
                    }
                })).run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBeanProvider(HarnessAgent.Builder.class).getObject();
                    verify(client, never()).close();
                });
        verify(client).close();
    }
}


