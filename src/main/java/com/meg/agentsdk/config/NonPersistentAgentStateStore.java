package com.meg.agentsdk.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Keeps turn state inside AgentScope's current execution without saving it for the next call. */
final class NonPersistentAgentStateStore implements AgentStateStore {

    @Override
    public void save(String userId, String sessionId, String key, State value) {}

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {}

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        return Optional.empty();
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> type) {
        return List.of();
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return false;
    }

    @Override
    public void delete(String userId, String sessionId) {}

    @Override
    public Set<String> listSessionIds(String userId) {
        return Set.of();
    }
}
