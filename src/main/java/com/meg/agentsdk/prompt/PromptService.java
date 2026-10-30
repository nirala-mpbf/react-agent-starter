package com.meg.agentsdk.prompt;

import com.meg.agentsdk.prompt.dto.PromptResponse;
import com.meg.agentsdk.prompt.dto.SavePromptResponse;
import com.meg.agentsdk.prompt.exception.LakefsException;
import com.meg.agentsdk.prompt.cache.PromptCacheService;
import com.meg.agentsdk.prompt.lakefs.LakeFSService;
import com.meg.agentsdk.prompt.repository.PromptVersionRepository;
import io.lakefs.clients.sdk.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class PromptService {

    private final Logger log = LoggerFactory.getLogger(PromptService.class);

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_-]{1,100}");

    private final LakeFSService lakeFSService;
    private final PromptVersionRepository versionRepository;
    private final PromptCacheService cacheService;
    private final String repository;
    private final String branch;

    public PromptService(LakeFSService lakeFSService,
                         PromptVersionRepository versionRepository,
                         PromptCacheService cacheService,
                         @Value("${lakefs.repository}") String repository,
                         @Value("${lakefs.branch}") String branch) {

        this.lakeFSService = lakeFSService;
        this.versionRepository = versionRepository;
        this.cacheService = cacheService;
        this.repository = repository;
        this.branch = branch;
    }


    public SavePromptResponse save(String workflow, String kind, Resource markdownFile) throws ApiException {

        if (markdownFile == null) {
            throw new LakefsException(HttpStatus.BAD_REQUEST, "Markdown resource is required");
        }
        try ( InputStream input = markdownFile.getInputStream() ) {

            byte[] bytes = input.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) {
                throw new LakefsException(HttpStatus.PAYLOAD_TOO_LARGE, "Markdown exceeds 1 MB");
            }

            return saveMarkdown(workflow, kind, new String(bytes, StandardCharsets.UTF_8));

        } catch (IOException e) {
            throw new LakefsException(HttpStatus.BAD_REQUEST, "Cannot read Markdown resource", e);
        }
    }

    private SavePromptResponse saveMarkdown(String workflow, String kind, String mdContent) throws ApiException {

        String path = path(workflow, kind);
        if (mdContent == null || mdContent.isBlank()) {
            throw new LakefsException(HttpStatus.BAD_REQUEST, "Markdown content must not be blank");
        }

        String commitId = lakeFSService.uploadAndCommit(repository, branch, path, mdContent);

        try {
            versionRepository.markaActivation(path, commitId);

        } catch (RuntimeException e) {
            log.error("lakeFS commit {} for path {} succeeded but metadata persistence failed", commitId, path, e);
            throw e;
        }

        log.info("Prompt metadata activated: path={}, commitId={}", path, commitId);
        cacheService.evict(path);
        return new SavePromptResponse(workflow + "/" + kind, commitId, "Saved " + path);
    }


    public PromptResponse latest(String workflow, String kind) {
        String path = path(workflow, kind);

        log.info("[PROMPT-TRACE] latest() called: workflow={}, kind={}, path={}", workflow, kind, path);

        return cacheService.get(workflow + "/" + kind, path)
                .map(response -> {
                    log.info(
                            "[PROMPT-TRACE] SOURCE=PROMPT_CACHE, promptId={}, ref={}, length={}",
                            response.promptId(),
                            response.ref(),
                            response.content() == null ? 0 : response.content().length()
                    );
                    return response;
                })
                .orElseGet(() -> {
                    log.info("[PROMPT-TRACE] Cache miss. Fetching active commit: path={}", path);

                    String commitId = versionRepository.getActiveCommitId(path);
                    log.info(
                            "[PROMPT-TRACE] SOURCE=LAKEFS_DOWNLOAD, repository={}, branch={}, path={}, commitId={}",
                            repository, branch, path, commitId
                    );

                    StringBuffer content = lakeFSService.download(repository, commitId, path);

                    PromptResponse response = new PromptResponse(
                            workflow + "/" + kind,
                            commitId,
                            content.toString()
                    );

                    log.info("[PROMPT-TRACE] LakeFS prompt loaded: length={}", content == null ? 0 : content.length());

                    cacheService.put(path, response);
                    return response;
                });
    }

    private static String path(String workflow, String kind) {
        if (workflow == null || kind == null || !NAME.matcher(workflow).matches() || !NAME.matcher(kind).matches()) {
            throw new LakefsException(HttpStatus.BAD_REQUEST, "Invalid workflow or prompt name");
        }
        return "prompts/" + workflow + "/" + kind + ".md";
    }
}
