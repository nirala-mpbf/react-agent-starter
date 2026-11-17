package com.example.ai.agent;

import com.meg.agentsdk.annotation.McpServer;
import com.meg.agentsdk.annotation.ReActAgent;
import com.meg.agentsdk.core.Agent;

@ReActAgent(
        name = "gst-od-assistant",
        model = "${agent.model}",
        mcp = {
                @McpServer(
                        name = "brain-tools",
                        url = "${agent.gateway.mcp-url}"
                )
        },
        allowedTools = {"processWorkflowCommand"},
        workflow = "${lakefs.path.workflow}",
        prompt = "${lakefs.path.prompt}"
)
public class GstOdAgent extends Agent {
}
