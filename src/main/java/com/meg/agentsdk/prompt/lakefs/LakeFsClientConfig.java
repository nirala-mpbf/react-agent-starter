package com.meg.agentsdk.prompt.lakefs;

import io.lakefs.clients.sdk.ApiClient;
import io.lakefs.clients.sdk.auth.HttpBasicAuth;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@ConditionalOnProperty(prefix="react-agent.prompts", name="enabled", havingValue="true")
public class LakeFsClientConfig  {

    @Bean
    @ConditionalOnMissingBean(ApiClient.class)
    public ApiClient lakeFsApiClient(
            @Value("${lakefs.endpoint}") String endpoint,
            @Value("${lakefs.access-key}") String accessKey,
            @Value("${lakefs.secret-key}") String secretKey,
            @Value("${lakefs.http.max-idle-connections:1}") int maxIdle,
            @Value("${lakefs.http.keep-alive-minutes:5}") int keepAlive) {

        ConnectionPool connectionPool = new ConnectionPool(
                maxIdle,
                keepAlive,
                TimeUnit.MINUTES
        );

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .build();

        ApiClient client = new ApiClient(httpClient);

        client.setBasePath(endpoint.replaceAll("/+$", "") + "/api/v1");

        HttpBasicAuth auth =
                (HttpBasicAuth) client.getAuthentication("basic_auth");

        auth.setUsername(accessKey);
        auth.setPassword(secretKey);

        return client;
    }

    @Bean
    @ConditionalOnMissingBean(name="lakeFsDownloadHttpClient")
    public HttpClient lakeFsDownloadHttpClient() {

        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}
