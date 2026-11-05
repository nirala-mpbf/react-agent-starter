package com.meg.agentsdk.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "react-agent.memory", name = "postgres-enabled", havingValue = "true")
public class PostgresMemoryConfiguration {

    @Bean("reactAgentPostgresStateStore")
    AgentStateStore reactAgentPostgresStateStore(DataSource dataSource) {
        return new PostgresAgentStateStore(dataSource, true);
    }
}
