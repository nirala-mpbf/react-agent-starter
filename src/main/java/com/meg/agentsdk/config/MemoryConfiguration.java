package com.meg.agentsdk.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.core.env.Environment;

import javax.sql.DataSource;

@ConditionalOnProperty(prefix = "react-agent.memory", name = "postgres-enabled", havingValue = "true")
public class MemoryConfiguration {

    @Bean({"reactAgentStateStore", "reactAgentPostgresStateStore"})
    AgentStateStore reactAgentStateStore(BeanFactory beans, Environment environment) {

        String name = environment.getProperty("react-agent.memory.datasource-bean", "").trim();
        DataSource dataSource = name.isEmpty() ? beans.getBean(DataSource.class)  : beans.getBean(name, DataSource.class);
        return new PostgresAgentStateStore(dataSource, true);
    }
}
