package com.meg.agentsdk.prompt.api;
import com.meg.agentsdk.prompt.exception.LakefsException;

import io.lakefs.clients.sdk.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice(assignableTypes = PromptController.class)
@org.springframework.core.annotation.Order(0)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="react-agent.prompts", name={"enabled", "controller-enabled"}, havingValue="true")
public class PromptExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(PromptExceptionHandler.class);


    @ExceptionHandler(LakefsException.class)
    public ResponseEntity<Map<String, String>> lakefs(LakefsException e) {
        log.warn("Request rejected: status={}, reason={}", e.getStatus().value(), e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        if (e.getCode() == 400 && e.getResponseBody() != null
                && e.getResponseBody().contains("\"commit: no changes\"")) {
            log.info("Upload unchanged: lakeFS created no new commit");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "No changes detected; no new commit was created.",
                    "code", "PROMPT_UNCHANGED"));
        }
        log.error("lakeFS request failed: HTTP={}, response={}", e.getCode(), e.getResponseBody(), e);
        HttpStatus status = e.getCode() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY;
        String message = status == HttpStatus.NOT_FOUND
                ? "lakeFS resource not found" : "lakeFS API request failed (HTTP " + e.getCode() + ")";
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        log.warn("Request validation failed: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request content"));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> malformedRequest(Exception e) {
        log.warn("Malformed upload or lookup request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error",
                "Invalid request. Upload requires file and metadata parts; metadata must be JSON with workflow and kind."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> unsupportedType(Exception e) {
        log.warn("Unsupported request content type: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of("error",
                "Use multipart/form-data for upload and application/json for metadata and latest requests."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> oversizedUpload(Exception e) {
        log.warn("Upload exceeds configured size limit");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "Markdown file exceeds the upload size limit."));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> databaseFailure(DataAccessException e) {
        log.error("Prompt metadata database operation failed", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error",
                "Prompt metadata could not be read or saved. Check the application database and server logs."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("Unexpected request failure", e);
        return ResponseEntity.status(500).body(Map.of("error", "Unexpected server error"));
    }
}
