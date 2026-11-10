package com.example.ai.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
public class DataSourceConfiguration {

    @Bean
    @Primary
    @ConfigurationProperties("app.datasource.prompt")
    public HikariDataSource promptMetadataDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @ConfigurationProperties("app.datasource.memory")
    public HikariDataSource agentMemoryDataSource() {
        return new HikariDataSource();
    }
}
