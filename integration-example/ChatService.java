package com.example.ai.service;

import com.example.ai.agent.GstOdAgent;
import com.example.ai.api.ChatController.ChatRequest;
import com.example.ai.api.ChatController.ChatResponse;
import io.agentscope.core.agent.RuntimeContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ChatService {
    private final GstOdAgent agent;

    public ChatService(GstOdAgent agent) {
        this.agent = agent;
    }

    public Mono<ChatResponse> chat(ChatRequest request) {
        RuntimeContext context = RuntimeContext.builder()
                .userId(request.userId()).sessionId(request.sessionId()).build();
        return agent.askAsync(request.message(), context)
                .map(reply -> new ChatResponse(request.sessionId(), reply.content()));
    }
}
