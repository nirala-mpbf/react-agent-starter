package com.example.ai.agent;
import com.meg.agentsdk.annotation.McpServer;
import com.meg.agentsdk.annotation.ReactAgentRouter;
import com.meg.agentsdk.core.Agent;
@ReactAgentRouter(
        name = "gst-od-assistant",
        model = "${agent.model}",
        workflow = "ddod",
        prompt = "onboarding_master_sop_7",
        mcp = {
                @McpServer(name = "brain-tools", url = "${agent.gateway.mcp-url}")
        },
        allowedTools = {"processWorkflowCommand"}
)
public class GstOdAgent extends Agent {}
