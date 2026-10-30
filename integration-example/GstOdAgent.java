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
                @McpServer(name = "brain-tools", url = "${mcp.brain.url}")
        },
        allowedTools = {
                "get_current_status",
                "verify_otp",
                "submit_profile",
                "submit_account_statement",
                "submit_udyam_document"
        }
)
public class GstOdAgent extends Agent {}
