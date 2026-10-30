package com.meg.agentsdk.prompt;

import com.meg.agentsdk.annotation.ReactAgentRouter;
import com.meg.agentsdk.annotation.McpServer;
import com.meg.agentsdk.prompt.api.*;
import com.meg.agentsdk.prompt.cache.PromptCacheService;
import com.meg.agentsdk.prompt.dto.*;
import com.meg.agentsdk.prompt.exception.LakefsException;
import com.meg.agentsdk.prompt.lakefs.LakeFSService;
import com.meg.agentsdk.prompt.repository.PromptVersionRepository;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PromptManagementTest {
    @ReactAgentRouter(name = "gst", workflow = "gst", prompt = "master",
            mcp = @McpServer(name = "brain", url = "${test.mcp-url}"), allowedTools = "get_current_status")
    static class GstAgent extends com.meg.agentsdk.core.Agent {}

    @ReactAgentRouter(name = "dd", workflow = "dd", prompt = "master",
            mcp = @McpServer(name = "brain", url = "${test.mcp-url}"), allowedTools = "verify_otp")
    static class DdAgent extends com.meg.agentsdk.core.Agent {}

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = PromptManagementTest.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {GstAgent.class, DdAgent.class}))
    static class TypedScan {}

    @Test void scannedTypedAgentsKeepToolsAndPromptsSeparateAndRefreshEachCall() throws Exception {
        try (var backend = new HttpBackend()) {
            var ds = database();
            var cache = new ConcurrentHashMap<String,String>();
            var savedAgent = new java.util.concurrent.atomic.AtomicReference<GstAgent>();
            enabled(backend, ds, redis(cache)).withUserConfiguration(TypedScan.class)
                    .withPropertyValues("react-agent.permissions.agents[gst].allow[0]=get_current_status",
                            "react-agent.permissions.agents[dd].allow[0]=verify_otp").run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(GstAgent.class).hasSingleBean(DdAgent.class);
                assertThat(context.getBeanNamesForType(HarnessAgent.Builder.class)).isEmpty();
                var versions = context.getBean(PromptVersionRepository.class);
                versions.markaActivation("prompts/gst/master.md", "c0");
                backend.prompts.put("dd0", "DD SOP");
                versions.markaActivation("prompts/dd/master.md", "dd0");
                var gst = context.getBean(GstAgent.class);
                var dd = context.getBean(DdAgent.class);
                savedAgent.set(gst);
                assertThatThrownBy(() -> reactor.core.publisher.Mono.fromCallable(() -> gst.ask("bad blocking call"))
                        .subscribeOn(reactor.core.scheduler.Schedulers.parallel()).block())
                        .hasMessageContaining("Use askAsync");
                assertThat(backend.modelRequests).isEmpty();
                assertThat(gst.ask("first").content()).isEqualTo("System SOP|first");
                assertThat(dd.ask("second").content()).isEqualTo("DD SOP|second");
                assertThat(backend.downloads.get()).isEqualTo(2);
                assertThat(gst.ask("cached").content()).isEqualTo("System SOP|cached");
                assertThat(backend.downloads.get()).isEqualTo(2);
                try {
                    context.getBean(PromptService.class).save("gst", "master",
                            new ByteArrayResource("System SOP version c1".getBytes()));
                } catch (Exception error) { throw new AssertionError(error); }
                var replies = reactor.core.publisher.Flux.range(0, 4)
                        .flatMap(i -> gst.askAsync("parallel-" + i)).collectList().block(Duration.ofSeconds(20));
                assertThat(replies).hasSize(4).allSatisfy(reply ->
                        assertThat(reply.content()).startsWith("System SOP version c1|parallel-"));
                for (String body : backend.modelRequests) {
                    if (body.contains("DD SOP")) assertThat(body).contains("verify_otp").doesNotContain("get_current_status");
                    else assertThat(body).contains("get_current_status").doesNotContain("verify_otp");
                }
                assertThat(backend.initializations.get()).isEqualTo(2);
                assertThat(backend.toolCalls.get()).isEqualTo(7);
                assertThatThrownBy(() -> gst.ask(" ")).hasMessageContaining("Query must not be blank");
                assertThatThrownBy(() -> gst.ask("query", null)).hasMessageContaining("Runtime context is required");
            });
            assertThatThrownBy(() -> savedAgent.get().ask("after shutdown")).hasMessageContaining("not active");
            assertThat(backend.errors).isEmpty();
        }
    }

    @ReactAgentRouter(name = "plain")
    static class PlainAgent extends com.meg.agentsdk.core.Agent {}

    @Test void typedAgentWorksWithoutOptionalPromptLibraries() {
        new ApplicationContextRunner().withUserConfiguration(PlainAgent.class)
                .withPropertyValues("agent.model=test", "agent.gateway.base-url=http://localhost:1/v1", "agent.gateway.api-key=test")
                .withClassLoader(new FilteredClassLoader("io.lakefs", "org.springframework.jdbc",
                        "org.springframework.data.redis", "org.springframework.web", "jakarta.servlet"))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PlainAgent.class)
                        .doesNotHaveBean(HarnessAgent.Builder.class));
    }

    @Configuration(proxyBeanMethods = false)
    @ReactAgentRouter
    static class CoreAgent {}

    @Configuration(proxyBeanMethods = false)
    @ReactAgentRouter(name = "prompted", workflow = "${test.workflow:ddod}", prompt = "${test.prompt:master}",
            mcp = @McpServer(name = "brain", url = "${test.mcp-url}"), allowedTools = "get_current_status")
    static class PromptedAgent {}

    @Configuration(proxyBeanMethods = false)
    @ReactAgentRouter(workflow = "ddod")
    static class IncompleteSelection {}

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Web {}

    @RestController
    static class UnrelatedController {
        @GetMapping("/unrelated")
        String unrelated() { throw new IllegalArgumentException("application error"); }
    }

    private ApplicationContextRunner core() {
        return new ApplicationContextRunner().withUserConfiguration(CoreAgent.class)
                .withPropertyValues("agent.model=test-model", "agent.gateway.base-url=http://localhost:1/v1", "agent.gateway.api-key=test");
    }

    private static DataSource database() {
        String pg = System.getenv("SDK_PROMPT_TEST_POSTGRES_URL");
        var ds = new DriverManagerDataSource();
        ds.setUrl(pg == null ? "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1" : pg);
        ds.setUsername(pg == null ? "sa" : "postgres");
        ds.setPassword("");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS prompt_versions (
                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    path TEXT NOT NULL, commit_id TEXT NOT NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)
                """);
        if (pg != null) {
            org.flywaydb.core.Flyway.configure().dataSource(ds)
                    .locations("classpath:db/react-agent-sdk").baselineOnMigrate(true).baselineVersion("0").load().migrate();
        }
        jdbc.update("DELETE FROM prompt_versions");
        return ds;
    }

    private StringRedisTemplate redis(Map<String,String> entries) {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0, String.class)));
        doAnswer(call -> {
            entries.put(call.getArgument(0, String.class), call.getArgument(1, String.class));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(call -> entries.remove(call.getArgument(0, String.class)) != null);
        return redis;
    }

    private String[] properties(HttpBackend backend) {
        return new String[] {"react-agent.prompts.enabled=true", "agent.model=test-model",
                "agent.gateway.base-url=" + backend.base() + "/v1", "agent.gateway.api-key=test",
                "test.mcp-url=" + backend.base() + "/mcp", "lakefs.endpoint=" + backend.base(),
                "lakefs.repository=repo", "lakefs.branch=main", "lakefs.access-key=access",
                "lakefs.secret-key=secret"};
    }

    private ApplicationContextRunner enabled(HttpBackend backend, DataSource ds, StringRedisTemplate redis) {
        return new ApplicationContextRunner().withBean(DataSource.class, () -> ds)
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(ds))
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds))
                .withBean(StringRedisTemplate.class, () -> redis).withPropertyValues(properties(backend));
    }

    private WebApplicationContextRunner web(HttpBackend backend, DataSource ds, StringRedisTemplate redis) {
        return new WebApplicationContextRunner().withBean(DataSource.class, () -> ds)
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(ds))
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds))
                .withBean(StringRedisTemplate.class, () -> redis)
                .withPropertyValues(properties(backend)).withUserConfiguration(CoreAgent.class, Web.class);
    }

    @Test void disabledFeatureDoesNotCreatePromptBeansOrRequireInfrastructure() {
        core().run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(PromptService.class)
                .doesNotHaveBean(PromptController.class));
    }

    @Test void coreWorksWithoutOptionalStorageAndWebLibraries() {
        core().withClassLoader(new FilteredClassLoader("io.lakefs", "org.springframework.jdbc",
                "org.springframework.data.redis", "org.springframework.web", "jakarta.servlet"))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(HarnessAgent.Builder.class));
    }

    @Test void selectionRequiresBothAttributesAndFeatureOptIn() {
        new ApplicationContextRunner().withUserConfiguration(IncompleteSelection.class)
                .withPropertyValues("agent.model=test", "agent.gateway.base-url=http://localhost:1", "agent.gateway.api-key=test")
                .run(context -> assertThat(context).hasFailed());
        core().withUserConfiguration(PromptedAgent.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("Enable react-agent.prompts.enabled");
        });
    }

    @Test void enabledServicesDoNotAutomaticallyExposeControllerAndCustomServiceIsPreserved() throws Exception {
        try (var backend = new HttpBackend()) {
            var ds = database();
            var custom = mock(PromptService.class);
            enabled(backend, ds, redis(new ConcurrentHashMap<>())).withUserConfiguration(CoreAgent.class)
                    .withBean(PromptService.class, () -> custom).run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(PromptService.class).doesNotHaveBean(PromptController.class);
                        assertThat(context.getBean(PromptService.class)).isSameAs(custom);
                    });
        }
    }

    @Test void freshBuildersFetchActivePromptAndUseOriginalCacheInvalidationFlow() throws Exception {
        try (var backend = new HttpBackend()) {
            var ds = database();
            var cache = new ConcurrentHashMap<String,String>();
            enabled(backend, ds, redis(cache)).withUserConfiguration(PromptedAgent.class)
                    .withPropertyValues("react-agent.permissions.allow[0]=get_current_status").run(context -> {
                assertThat(context).hasNotFailed();
                var versions = context.getBean(PromptVersionRepository.class);
                versions.markaActivation("prompts/ddod/master.md", "c0");
                var builders = context.getBeanProvider(HarnessAgent.Builder.class);
                try (var first = builders.getObject().build()) {
                    var reply = first.call(new io.agentscope.core.message.UserMessage("first"),
                            io.agentscope.core.agent.RuntimeContext.builder().userId("u").sessionId("s1").build()).block();
                    assertThat(reply.getTextContent()).isEqualTo("System SOP|first");
                }
                assertThat(backend.downloads.get()).isEqualTo(1);
                try (var second = builders.getObject().build()) {
                    assertThat(second.getToolkit().getToolNames()).containsExactly("get_current_status");
                }
                assertThat(backend.downloads.get()).isEqualTo(1); // Original Redis hit path.
                var prompts = context.getBean(PromptService.class);
                var saved = prompts.save("ddod", "master", new ByteArrayResource("System SOP version c1".getBytes()));
                assertThat(saved.commitId()).isEqualTo("c1");
                assertThat(cache).isEmpty(); // Original save flow evicts the old entry.
                try (var third = builders.getObject().build()) {
                    var reply = third.call(new io.agentscope.core.message.UserMessage("after"),
                            io.agentscope.core.agent.RuntimeContext.builder().userId("u").sessionId("s2").build()).block();
                    assertThat(reply.getTextContent()).isEqualTo("System SOP version c1|after");
                }
                assertThat(backend.downloads.get()).isEqualTo(2);
                assertThat(backend.initializations.get()).isEqualTo(1);
                assertThat(backend.downloadAuthorization).isEqualTo("Basic YWNjZXNzOnNlY3JldA==");
                assertThat(backend.uploadBodies.getFirst()).contains("System SOP version c1");
            });
            assertThat(backend.errors).isEmpty();
        }
    }

    @Test void activationRollsBackOnInsertFailureAndKeepsHistory() throws Exception {
        try (var backend = new HttpBackend()) {
            var ds = database();
            enabled(backend, ds, redis(new ConcurrentHashMap<>())).withUserConfiguration(CoreAgent.class).run(context -> {
                var versions = context.getBean(PromptVersionRepository.class);
                versions.markaActivation("prompts/ddod/master.md", "c0");
                assertThatThrownBy(() -> versions.markaActivation("prompts/ddod/master.md", null))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(versions.getActiveCommitId("prompts/ddod/master.md")).isEqualTo("c0");
                versions.markaActivation("prompts/ddod/master.md", "c1");
                var jdbc = new JdbcTemplate(ds);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prompt_versions", Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prompt_versions WHERE is_active=TRUE", Integer.class)).isEqualTo(1);
            });
        }
    }

    @Test void redisOutageFallsBackToLakefsAndHttpErrorsRemainExplicit() throws Exception {
        try (var backend = new HttpBackend()) {
            var ds = database();
            var redis = mock(StringRedisTemplate.class);
            when(redis.opsForValue()).thenThrow(new DataAccessResourceFailureException("Redis offline"));
            enabled(backend, ds, redis).withUserConfiguration(CoreAgent.class).run(context -> {
                var versions = context.getBean(PromptVersionRepository.class);
                versions.markaActivation("prompts/ddod/master.md", "c0");
                assertThat(context.getBean(PromptService.class).latest("ddod", "master").content()).isEqualTo("System SOP");
                versions.markaActivation("prompts/ddod/master.md", "missing");
                assertThatThrownBy(() -> context.getBean(PromptService.class).latest("ddod", "master"))
                        .isInstanceOf(LakefsException.class);
            });
        }
    }

    @Test void controllerRequiresExplicitFlagAndServesTheExistingLatestApi() throws Exception {
        try (var backend = new HttpBackend()) {
            var ds = database();
            web(backend, ds, redis(new ConcurrentHashMap<>())).run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(PromptController.class);
            });
            web(backend, ds, redis(new ConcurrentHashMap<>())).withPropertyValues("react-agent.prompts.controller-enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(PromptController.class);
                        context.getBean(PromptVersionRepository.class).markaActivation("prompts/ddod/master.md", "c0");
                        MockMvcBuilders.webAppContextSetup(context).build()
                                .perform(post("/api/v1/prompts").header("X-Action", "latest").contentType("application/json")
                                        .content("{\"workflow\":\"ddod\",\"kind\":\"master\"}"))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.ref").value("c0"))
                                .andExpect(jsonPath("$.content").value("System SOP"));
                    });
        }
    }

    @Test void uploadEndpointKeepsMarkdownValidationAndActivatesUploadedPrompt() throws Exception {
        try (var backend = new HttpBackend()) {
            var ds = database();
            web(backend, ds, redis(new ConcurrentHashMap<>())).withPropertyValues("react-agent.prompts.controller-enabled=true")
                    .run(context -> {
                        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
                        var metadata = new org.springframework.mock.web.MockMultipartFile("metadata", "", "application/json",
                                "{\"workflow\":\"ddod\",\"kind\":\"master\"}".getBytes());
                        mvc.perform(multipart("/api/v1/prompts").file(metadata)
                                        .file(new org.springframework.mock.web.MockMultipartFile("file", "wrong.txt", "text/plain", "SOP".getBytes()))
                                        .header("X-Action", "upload")).andExpect(status().isBadRequest());
                        mvc.perform(multipart("/api/v1/prompts").file(metadata)
                                        .file(new org.springframework.mock.web.MockMultipartFile("file", "GST_OD.md", "text/markdown",
                                                "System SOP version c1".getBytes())).header("X-Action", "upload"))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.commitId").value("c1"));
                        assertThat(context.getBean(PromptVersionRepository.class).getActiveCommitId("prompts/ddod/master.md"))
                                .isEqualTo("c1");
                    });
        }
    }

    @Test void promptExceptionAdviceDoesNotHandleOtherControllers() throws Exception {
        try (var backend = new HttpBackend()) {
            var ds = database();
            web(backend, ds, redis(new ConcurrentHashMap<>())).withBean(UnrelatedController.class)
                    .withPropertyValues("react-agent.prompts.controller-enabled=true").run(context -> {
                        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
                        mvc.perform(post("/api/v1/prompts").header("X-Action", "latest").contentType("application/json")
                                        .content("{\"workflow\":\"ddod\",\"kind\":\"missing\"}"))
                                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").exists());
                        assertThatThrownBy(() -> mvc.perform(get("/unrelated"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
                    });
        }
    }

    @Test void originalPromptValidationRejectsBlankOversizedAndInvalidNames() {
        var prompts = new PromptService(mock(LakeFSService.class), mock(PromptVersionRepository.class),
                mock(PromptCacheService.class), "repo", "main");
        assertThatThrownBy(() -> prompts.save("../bad", "master", new ByteArrayResource("SOP".getBytes()))).isInstanceOf(LakefsException.class);
        assertThatThrownBy(() -> prompts.save("ddod", "master", new ByteArrayResource(" ".getBytes()))).isInstanceOf(LakefsException.class);
        assertThatThrownBy(() -> prompts.save("ddod", "master", new ByteArrayResource(new byte[1_048_577]))).isInstanceOf(LakefsException.class);
    }
}



