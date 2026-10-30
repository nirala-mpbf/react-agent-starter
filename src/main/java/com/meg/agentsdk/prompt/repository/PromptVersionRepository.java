package com.meg.agentsdk.prompt.repository;

import com.meg.agentsdk.prompt.exception.LakefsException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class PromptVersionRepository {

    private final JdbcTemplate jdbc;

    public PromptVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void markaActivation(String path, String commitId) {

        jdbc.update("UPDATE prompt_versions SET is_active = FALSE WHERE path = ? AND is_active = TRUE", path);
        jdbc.update("INSERT INTO prompt_versions (path, commit_id, is_active) VALUES (?, ?, TRUE)", path, commitId);
    }

    public String getActiveCommitId(String path) {
        final String sql = "SELECT commit_id FROM prompt_versions WHERE path = ? AND is_active = TRUE";
        return jdbc.query(sql, (rs, row) -> rs.getString("commit_id"), path)
                .stream().findFirst()
                .orElseThrow(() -> new LakefsException(HttpStatus.NOT_FOUND, "No active version found for prompt"));
    }
}

