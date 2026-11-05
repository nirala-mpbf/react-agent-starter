package com.meg.agentsdk.config;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import com.meg.agentsdk.prompt.PromptService;
import io.agentscope.core.agent.RuntimeContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Duration;
import java.util.*;

/**
 * Reads environment settings, prepares shared model/MCP resources once, and
 * supplies a fresh builder per Spring lookup. Chat execution stays in the application.
 */
public class AgentBuilderFactoryBean
        implements FactoryBean<HarnessAgent.Builder>, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgentBuilderFactoryBean.class);

    private final String agentName;
    private final String modelName;
    private final List<Endpoint> endpoints;
    private final List<String> allowedTools;
    private final Environment environment;
    private final List<McpClientWrapper> clients = new ArrayList<>();
    private Model model;
    private Toolkit toolkit;
    private Set<String> exposedTools = Set.of();
    private String workflow = "";
    private String prompt = "";
    private ObjectProvider<PromptService> promptServices;
    private boolean initialized;
    private boolean closed;

    public AgentBuilderFactoryBean(String agentName, String modelName, List<Endpoint> endpoints,
                                   List<String> allowedTools, Environment environment) {
        this.agentName = agentName;
        this.modelName = modelName;
        this.endpoints = List.copyOf(endpoints);
        this.allowedTools = List.copyOf(allowedTools);
        this.environment = environment;
    }

    public void setWorkflow(String workflow) {
        this.workflow = workflow;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    @Autowired
    public void setPromptServices(ObjectProvider<PromptService> promptServices) {
        this.promptServices = promptServices;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            workflow = environment.resolveRequiredPlaceholders(workflow).trim();
            prompt = environment.resolveRequiredPlaceholders(prompt).trim();

            if (workflow.isEmpty() != prompt.isEmpty()) {
                throw new IllegalArgumentException("Supply both workflow and prompt, or omit both");
            }
            if (!workflow.isEmpty() && !environment.getProperty("react-agent.prompts.enabled", Boolean.class, false)) {
                throw new IllegalStateException("Enable react-agent.prompts.enabled to select a system prompt");
            }

            model = createModel();
            toolkit = connectMcpServers();
            filterTools();
            configurePermissions(); // Fail startup for invalid approvals.
            initialized = true;
        } catch (RuntimeException error) {
            destroy();
            throw error;
        }
    }

    /**
     * Override when an application needs a different AgentScope model provider.
     */
    protected Model createModel() {

        String selectedModel = environment.resolveRequiredPlaceholders(modelName).trim();

        if (selectedModel.isEmpty()) {
            selectedModel = requiredProperty("agent.model");
        }

        return OpenAIChatModel.builder()
                .baseUrl(requiredProperty("agent.gateway.base-url"))
                .apiKey(requiredProperty("agent.gateway.api-key"))
                .modelName(selectedModel)
                .build();
    }

    private Toolkit connectMcpServers() {

        Duration timeout = Binder.get(environment)
                .bind("react-agent.mcp.timeout", Duration.class).
                orElse(Duration.ofSeconds(30));

        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("react-agent.mcp.timeout must be positive");
        }

        List<Endpoint> resolvedEndpoints =
                endpoints.stream()
                .map(endpoint -> new Endpoint( resolve(endpoint.name()), resolve(endpoint.url())))
                .toList();

        Set<String> serverNames = new HashSet<>();

        for (Endpoint endpoint : resolvedEndpoints) {
            if (!serverNames.add(endpoint.name())) {
                throw new IllegalArgumentException("Duplicate MCP server name: " + endpoint.name());
            }
            URI uri = URI.create(endpoint.url());

            if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "")) || uri.getHost() == null) {
                throw new IllegalArgumentException("MCP URL must be HTTP(S): " + endpoint.name());
            }
        }

        Toolkit preparedToolkit = new Toolkit();
        Set<String> discoveredNames = new HashSet<>();

        try {
            for (Endpoint endpoint : resolvedEndpoints) {
                McpClientWrapper clientWrapper = Objects.requireNonNull(getMcpClientWrapper(endpoint, timeout), "MCP client");
                clients.add(clientWrapper); // Track before initialization so failures also release this clientWrapper.

                clientWrapper.initialize().block(timeout);

                List<McpSchema.Tool> discoveredTools = Objects.requireNonNull(clientWrapper.listTools().block(timeout), "MCP tool discovery");
                for (Tool tool : discoveredTools) {
                    if (!discoveredNames.add(tool.name())) {
                        throw new IllegalArgumentException("Duplicate MCP tool name: " + tool.name()
                                + ". Configure servers with distinct advertised tool names.");
                    }
                }
                preparedToolkit.registerMcpClient(clientWrapper).block(timeout);
            }

            return preparedToolkit;

        } catch (RuntimeException error) {
            throw new IllegalStateException("MCP setup failed: " + error.getMessage(), error);
        }
    }

    /**
     * Connection hook for an authenticated connector, another transport, or tests.
     */
    protected McpClientWrapper getMcpClientWrapper(Endpoint endpoint, Duration timeout) {

        return McpClientBuilder.create(endpoint.name())
                .streamableHttpTransport(endpoint.url())
                .buildAsync()
                .block(timeout);
    }

    private void filterTools() {

        Set<String> discoveredToolsName = Set.copyOf(toolkit.getToolNames());
        Set<String> selected = new HashSet<>();

        for (String configured : allowedTools) {
            String tool = resolve(configured);
            if (!discoveredToolsName.contains(tool)) {
                throw new IllegalArgumentException("allowedTools references an undiscovered MCP tool: " + tool);
            }
            selected.add(tool);
        }
        exposedTools = allowedTools.isEmpty() ? discoveredToolsName : Set.copyOf(selected);
        for (String tool : discoveredToolsName) {
            if (!exposedTools.contains(tool)) {
                toolkit.removeTool(tool);
            }
        }
    }

    protected PermissionContextState configurePermissions() {

        List<String> approvals = Binder.get(environment)
                .bind("react-agent.permissions.agents[" + agentName + "].allow", Bindable.listOf(String.class))
                .orElseGet(() -> Binder.get(environment)
                    .bind("react-agent.permissions.allow", Bindable.listOf(String.class))
                    .orElse(List.of())
                );

        PermissionContextState.Builder policyBuilder = PermissionContextState.builder();
        for (String configured : approvals) {
            String tool = environment.resolveRequiredPlaceholders(configured).trim();
            if (tool.isEmpty() || !exposedTools.contains(tool)) {
                throw new IllegalArgumentException("Execution approval references an unavailable tool for " + agentName + ": " + tool);
            }
            policyBuilder.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, "application"));
        }
        return policyBuilder.build();
    }

    public HarnessAgent.Builder getObject(RuntimeContext execution) {
        return getObject();
    }


    @Override
    public HarnessAgent.Builder getObject() {
        return buildWithPrompt(null);
    }


    public HarnessAgent.Builder buildWithPrompt(String systemPrompt) {
        return buildWithPrompt(systemPrompt, null);
    }

    public HarnessAgent.Builder buildWithPrompt(String systemPrompt, RuntimeContext execution) {

        if (!initialized || closed) {
            throw new IllegalStateException("Agent builder factory is not active");
        }

        ToolsConfig toolsConfig = new ToolsConfig();

        // AgentScope treats an empty allowlist as unrestricted. A no-MCP agent must expose no tools.
        toolsConfig.setAllow(exposedTools.isEmpty() ? List.of("__react_agent_no_tools__") : List.copyOf(exposedTools));
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agentName)
                .agentId(agentName)
                .model(model)
                .stateStore(new InMemoryAgentStateStore())
                .toolkit(toolkit.copy())
                .permissionContext(configurePermissions())
                .toolsConfig(toolsConfig)
                .disableSubagents()
                .disableDynamicSubagents()
                .disableShellTool()
                .disableFilesystemTools()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableSessionPersistence()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableCompaction()
                .disableToolResultEviction()
                .enablePendingToolRecovery(false)
                .enableAgentTracingLog(false);
        if (systemPrompt != null) {
            builder.sysPrompt(systemPrompt);
        } else if (!workflow.isEmpty()) {
            PromptService service = promptServices == null ? null : promptServices.getIfAvailable();
            if (service == null) {
                throw new IllegalStateException("PromptService is unavailable; enable prompts and configure its JDBC, Redis and lakeFS dependencies");
            }
            if (environment.getProperty("react-agent.prompts.sop-reference", Boolean.class, false)) {
                builder.sysPrompt(service.loadSystemPrompt(workflow, prompt, execution));
            } else {
                builder.sysPrompt(service.latest(workflow, prompt).content());
            }
        }
        return builder;
    }

    @Override
    public Class<?> getObjectType() {
        return HarnessAgent.Builder.class;
    }

    @Override
    public boolean isSingleton() {
        return false;
    }

    @Override
    public synchronized void destroy() {
        if (closed) {
            return;
        }
        closed = true;
        for (McpClientWrapper client : clients) {
            try {
                client.close();
            } catch (Exception error) {
                log.warn("Failed closing MCP client", error);
            }
        }
    }

    private String requiredProperty(String key) {
        String value = environment.getRequiredProperty(key);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Property must not be blank: " + key);
        }
        return value;
    }

    private String resolve(String value) {
        String resolved = environment.resolveRequiredPlaceholders(value).trim();
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("Agent configuration must not be blank");
        }
        return resolved;
    }

    public record Endpoint(String name, String url) {
    }

}




