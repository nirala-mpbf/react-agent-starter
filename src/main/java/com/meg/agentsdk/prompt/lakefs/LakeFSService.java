package com.meg.agentsdk.prompt.lakefs;

import com.meg.agentsdk.prompt.exception.LakefsException;
import io.lakefs.clients.sdk.*;
import io.lakefs.clients.sdk.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;

public class LakeFSService {

    private final ObjectsApi objectsApi;
    private final CommitsApi commitsApi;
    private final HttpClient httpClient;
    private final String endpoint;
    private final String credentials;

    private static final Logger log = LoggerFactory.getLogger(LakeFSService.class);

    public LakeFSService(ApiClient apiClient,
                         HttpClient lakeFsDownloadHttpClient,
                         @Value("${lakefs.endpoint}") String endpoint,
                         @Value("${lakefs.access-key}") String accessKey,
                         @Value("${lakefs.secret-key}") String secretKey) {

        this.objectsApi = new ObjectsApi(apiClient);
        this.commitsApi = new CommitsApi(apiClient);

        this.httpClient = lakeFsDownloadHttpClient;
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.credentials = "Basic " + Base64.getEncoder().encodeToString((accessKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
    }

    public String uploadAndCommit(String repository, String branch, String path, String content) throws ApiException {

        File tmp = null;
        String operation = "upload";
        try {
            tmp = File.createTempFile("lakefs-prompt-", ".md");
            Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);

            objectsApi.uploadObject(repository, branch, path)
                    .content(tmp)
                    .execute();

            log.info("lakeFS upload succeeded: repository={}, branch={}, path={}", repository, branch, path);

            operation = "commit";
            CommitCreation creation = new CommitCreation();
            creation.setMessage("Update " + path);
            Commit commit = commitsApi
                    .commit(repository, branch, creation)
                    .execute();

            log.info("lakeFS commit succeeded: repository={}, branch={}, path={}, commitId={}", repository, branch, path, commit.getId());
            return commit.getId();

        } catch (ApiException e) {

            if ("commit".equals(operation) && e.getCode() == 400 && e.getResponseBody() != null
                    && e.getResponseBody().contains("\"commit: no changes\"")) {

                log.info("lakeFS commit skipped: no changes for path={}", path);

            } else {
                log.error("lakeFS {} failed: repository={}, branch={}, path={}, HTTP={}, response={}",
                        operation, repository, branch, path, e.getCode(), e.getResponseBody());
            }
            throw e;
        } catch (IOException e) {
            log.error("Failed to prepare lakeFS upload", e);
            throw new LakefsException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to prepare prompt upload", e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp.toPath());
                } catch (IOException e) {
                    log.warn("Unable to delete temporary prompt file", e);
                }
            }
        }
    }

    public StringBuffer download(String repository, String ref, String path) {
        try {
            String url = endpoint + "/api/v1/repositories/" + encode(repository) + "/refs/" + encode(ref) + "/objects?path=" + encode(path);
            HttpRequest request = HttpRequest
                    .newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", credentials)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("lakeFS download failed: repository={}, ref={}, path={}, HTTP={}", repository, ref, path, response.statusCode());
            }
            if (response.statusCode() == 404) {
                throw new LakefsException(HttpStatus.NOT_FOUND, "Prompt not found");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LakefsException(HttpStatus.BAD_GATEWAY, "LakeFS download failed (HTTP " + response.statusCode() + ")");
            }
            log.debug("lakeFS download succeeded: repository={}, ref={}, path={}", repository, ref, path);
//            response.body().length();
//            String[] arr=new String[response.body().length()];
            StringBuffer sb = new StringBuffer();
            sb.append(response.body());
//            String content=response.body().toString();
            return sb;

        } catch (InterruptedException e) {
            log.error("lakeFS download interrupted: path={}", path, e);
            Thread.currentThread().interrupt();
            throw new LakefsException(HttpStatus.BAD_GATEWAY, "LakeFS request interrupted", e);
        } catch (java.io.IOException e) {
            log.error("lakeFS download connection failed: path={}", path, e);
            throw new LakefsException(HttpStatus.BAD_GATEWAY, "Unable to connect to LakeFS", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
