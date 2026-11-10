package com.meg.agentsdk.prompt;

import com.meg.agentsdk.prompt.api.PromptController;
import com.meg.agentsdk.prompt.api.PromptExceptionHandler;
import com.meg.agentsdk.prompt.cache.PromptCacheService;
import com.meg.agentsdk.prompt.lakefs.LakeFSService;
import com.meg.agentsdk.prompt.lakefs.LakeFsClientConfig;
import com.meg.agentsdk.prompt.repository.PromptVersionRepository;
import io.lakefs.clients.sdk.ApiClient;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

import java.net.http.HttpClient;

/**
 * Optional prompt feature; properties and infrastructure are owned by the consuming application.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "react-agent.prompts", name = "enabled", havingValue = "true")
@ConditionalOnClass({JdbcTemplate.class, StringRedisTemplate.class, ApiClient.class})
@EnableTransactionManagement
@Import(LakeFsClientConfig.class)
public class PromptConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PromptVersionRepository sdkPromptVersionRepository(BeanFactory beans, Environment environment) {
        String name = environment.getProperty("react-agent.prompts.datasource-bean", "").trim();
        if (name.isEmpty()) {
            return new PromptVersionRepository(beans.getBean(JdbcTemplate.class),
                    beans.getBean(PlatformTransactionManager.class));
        }
        DataSource dataSource = beans.getBean(name, DataSource.class);
        return new PromptVersionRepository(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean
    PromptCacheService sdkPromptCacheService(StringRedisTemplate redis, Environment environment) {
        return new PromptCacheService(
                redis,
                environment.getProperty("prompt.cache.ttl", "7d"),
                environment.getRequiredProperty("lakefs.repository"),
                environment.getRequiredProperty("lakefs.branch")
        );
    }

    @Bean
    @ConditionalOnMissingBean
    LakeFSService sdkLakeFSService(ApiClient apiClient,
                                   @Qualifier("lakeFsDownloadHttpClient") HttpClient downloadsHttpClient,
                                   Environment environment) {
        return new LakeFSService(
                apiClient,
                downloadsHttpClient,
                environment.getRequiredProperty("lakefs.endpoint"),
                environment.getRequiredProperty("lakefs.access-key"),
                environment.getRequiredProperty("lakefs.secret-key")
        );
    }

    @Bean
    @ConditionalOnMissingBean
    PromptService sdkPromptService(LakeFSService lakefsService, PromptVersionRepository promptVersionsRepository,
                                   PromptCacheService cacheService, Environment environment) {
        return new PromptService(
                lakefsService,
                promptVersionsRepository,
                cacheService,
                environment.getRequiredProperty("lakefs.repository"),
                environment.getRequiredProperty("lakefs.branch"));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(PromptController.class)
    @ConditionalOnProperty(prefix = "react-agent.prompts", name = "controller-enabled", havingValue = "true")
    static class PromptApiConfiguration {
        @Bean
        @ConditionalOnMissingBean
        PromptController sdkPromptController(PromptService prompts) {
            return new PromptController(prompts);
        }

        @Bean
        @ConditionalOnMissingBean
        PromptExceptionHandler sdkPromptExceptionHandler() {
            return new PromptExceptionHandler();
        }
    }
}

