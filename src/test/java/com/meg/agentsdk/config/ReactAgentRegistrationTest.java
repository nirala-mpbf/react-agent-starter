package com.meg.agentsdk.config;

import com.meg.agentsdk.annotation.ReactAgentRouter;
import com.meg.agentsdk.core.Agent;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ReactAgentRegistrationTest {
    @ReactAgentRouter(name = "${app.agent-name}", model = "${app.model}")
    static class FirstAgent extends Agent {}

    @ReactAgentRouter(name = "other")
    static class OtherAgent extends Agent {}

    @Test
    void registersTypedAgentsWithoutPublishingBuilders() {
        new ApplicationContextRunner()
                .withUserConfiguration(FirstAgent.class, OtherAgent.class)
                .withPropertyValues("app.agent-name=onboarding", "app.model=custom-model",
                        "agent.model=test-model", "agent.gateway.base-url=http://localhost:1/v1",
                        "agent.gateway.api-key=test")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(FirstAgent.class).hasSingleBean(OtherAgent.class)
                        .doesNotHaveBean(HarnessAgent.Builder.class));
    }

    @Test
    void modelMustBeConfiguredSomewhere() {
        new ApplicationContextRunner().withUserConfiguration(OtherAgent.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }
}
