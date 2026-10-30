# ReAct Agent Spring SDK

An annotation-driven SDK that replaces manual AgentConfig setup. Annotated classes extend Agent and become injectable Spring beans with ask/askAsync methods. The SDK builds, executes, and closes each request agent. Business logic and HTTP response mapping stay in the consuming service. The optional prompt package retains the existing prompt-management flow.

Java **25**, Spring Boot **4.1.1**, AgentScope **2.0.0**, lakeFS SDK **1.84.0** match the AI service. Typed agents use inheritance and delegation, without an AOP execution interceptor. Existing builder-based configuration remains supported.

## Declare an agent

```java
@ReactAgentRouter(
    name = "gst-od-assistant",
    model = "${agent.model}",
    mcp = {@McpServer(name = "brain-tools", url = "${mcp.brain.url}")},
    allowedTools = {"get_current_status", "verify_otp"},
    workflow = "ddod",
    prompt = "onboarding_master_sop_7"
)
public class GstOdAgent extends Agent {}
```

Imports are `com.meg.agentsdk.annotation.ReactAgentRouter`, `McpServer`, and `com.meg.agentsdk.core.Agent`.

| Attribute | Purpose | Default |
|---|---|---|
| name | Agent identity and per-agent approval key | reactAgent |
| model | Model identifier or property placeholder | agent.model |
| mcp | Array of Streamable HTTP MCP servers | No servers |
| allowedTools | Exact advertised tool names visible to the model | All discovered MCP tools |
| workflow | Existing PromptService workflow identifier | No managed system prompt |
| prompt | Existing PromptService prompt kind | No managed system prompt |

Supply both workflow and prompt, or omit both. `prompt` is a kind such as `onboarding_master_sop_7`, **not a raw filename**. The unchanged PromptService maps this pair to:
`prompts/ddod/onboarding_master_sop_7.md`.

Put the annotated Agent subclass under the application component-scan package (or explicitly import it). The annotation makes it a component and imports prompt configuration; do not scan SDK packages. Keep the main application class separate. Each typed agent owns its existing factory internally, without publishing a HarnessAgent.Builder bean.

## Enable the optional prompt feature

```yaml
react-agent:
  prompts:
    enabled: true
    controller-enabled: false  # Explicitly set true to expose the prompt API.
  mcp:
    timeout: 30s
  permissions:
    allow:
      - get_current_status
      - verify_otp

agent:
  gateway:
    base-url: ${AGENT_GATEWAY_BASE_URL}
    api-key: ${AGENT_GATEWAY_API_KEY}
  model: ${AGENT_MODEL:llama3.1:8b}

mcp:
  brain:
    url: ${BRAIN_MCP_URL:http://localhost:4000/mcp}

lakefs:
  endpoint: ${LAKEFS_ENDPOINT}
  access-key: ${LAKEFS_ACCESS_KEY}
  secret-key: ${LAKEFS_SECRET_KEY}
  repository: ${LAKEFS_REPOSITORY:brain-config}
  branch: ${LAKEFS_BRANCH:main}

prompt:
  cache:
    ttl: 7d

spring:
  datasource:
    url: ${APP_DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      connect-timeout: 2s
      timeout: 2s
  flyway:
    locations: classpath:db/migration,classpath:db/react-agent-sdk
```

Both feature flags default to false. With prompts disabled and no workflow/prompt selection, the original model/MCP-only usage works without storage configuration. The controller is registered only in a servlet application with both flags enabled.

### Dependencies

Prompt dependencies are marked optional in the SDK POM, so adding the core SDK alone does not force a datasource/server into an application. To enable prompts, the consuming application needs the lakeFS SDK 1.84.0, Boot JDBC, Redis, validation and MVC dependencies, a PostgreSQL driver, and Flyway/PostgreSQL migration support. **The colleague's AI service already declares these dependencies at the matching versions.**

The host's Boot JDBC configuration supplies JdbcTemplate and a transaction manager. If configuring Spring manually, supply those beans yourself. Default SDK prompt beans back off when the application supplies a bean of the corresponding SDK type.

### Schema

The migration is `db/react-agent-sdk/V2026102902__prompt_versions.sql`. Its SQL is the colleague's original V1 script, with CREATE IF NOT EXISTS and the original partial unique active-path index. It preserves the `prompt_versions` table and existing records. Its distinct version avoids a V1 version collision.

**Merge** the SDK location into the application's existing Flyway locations. Preserve other locations and existing history/baseline settings. The SDK does not change Flyway configuration automatically and does not enable baseline-on-migrate for you.

Like the original service, one prompt metadata database represents the configured repository/branch. Separate metadata schemas/databases are needed for independent repository/branch scopes.

## Typed agent and ChatService flow

```java
// Synchronous caller:
AgentResponse response = gstOdAgent.ask("What is my status?");
String text = response.content();

// Reactive caller, preserving request context:
return gstOdAgent.askAsync(request.message(), context)
    .map(reply -> new ChatResponse(request.sessionId(), reply.content()));
```

Inject GstOdAgent directly through the service constructor. See integration-example/GstOdAgent.java and ChatService.java.

```text
ChatService -> gstOdAgent.askAsync(query, context)
            -> internal factory creates a fresh builder
            -> PromptService.latest(workflow, prompt): Redis first, then DB/lakeFS
            -> attach system prompt, build, call, map AgentResponse, close
```

The agent bean is shared; each execution gets independent request state and a fresh builder. Model/MCP resources are prepared once per typed agent and closed on Spring shutdown. Mono.using closes each request agent on completion, failure, or cancellation. Prompt lookup runs for each execution/subscription and is scheduled on boundedElastic because storage calls block. A Mono is lazy: calling askAsync without subscribing does not execute a request.

ask is blocking; use askAsync in reactive services. Calling ask on Reactor nonblocking threads fails before starting work. Without an explicit RuntimeContext, each execution receives a fresh session ID. Neither API adds persistent conversation memory. Object[] parameters have not been added because their meaning has not been defined; use the typed RuntimeContext overload for user/session information.

### Existing builder API

An annotation on a configuration/application class that does not extend Agent still registers the original <name>Builder factory. Existing ObjectProvider<HarnessAgent.Builder> consumers continue to work. For typed agents, do not also put a duplicate annotation on the application class. The existing internal factory implementation remains the common setup path for both APIs.

## Reused prompt behavior

The following logic was moved from the colleague's project, retaining its methods and behavior:

- Save: validate Markdown -> upload to lakeFS -> create commit -> mark the PostgreSQL version active -> evict Redis cache.
- Latest: Redis cache first -> on miss, PostgreSQL active commit -> download that lakeFS version -> populate Redis.
- Database activation uses the original transaction: deactivate the prior row, insert the active row.
- Redis access failures fall back to the original lakeFS/database flow.
- Existing limits, workflow/kind validation, DTO fields, Basic authentication, and API error mappings are retained.

The original behavior is intentionally preserved, including its limits: if cache invalidation fails after an upload, stale Redis content can remain until TTL; lakeFS commits and PostgreSQL transactions are not a single distributed transaction. A metadata failure is surfaced after a successful lakeFS commit. No new cache/versioning algorithm has been substituted.

You can also inject `com.meg.agentsdk.prompt.PromptService` directly to save or retrieve prompts.

## Optional prompt controller

Set `react-agent.prompts.controller-enabled=true` to expose the existing API:

- POST /api/v1/prompts with X-Action: upload, multipart file + JSON metadata.
- POST /api/v1/prompts with X-Action: latest, JSON workflow + kind.

Example lookup:

```json
{"workflow":"ddod","kind":"onboarding_master_sop_7"}
```

The SDK's PromptExceptionHandler applies only to PromptController. It does not install a global catch-all handler for other application endpoints. Authentication/authorization for these administration routes remains the consuming application's responsibility; enable them only when that host protects them.

## Packages

```text
com.meg.agentsdk/
├── annotation/
│   ├── ReactAgentRouter.java
│   └── McpServer.java
├── core/
│   ├── Agent.java
│   └── AgentResponse.java
├── config/
│   ├── ReactAgentRegistrar.java
│   └── AgentBuilderFactoryBean.java
└── prompt/
    ├── PromptConfiguration.java
    ├── PromptService.java
    ├── lakefs/
    │   ├── LakeFsClientConfig.java
    │   └── LakeFSService.java
    ├── cache/PromptCacheService.java
    ├── repository/PromptVersionRepository.java
    ├── api/
    │   ├── PromptController.java
    │   └── PromptExceptionHandler.java
    ├── dto/
    │   ├── PromptLookupRequest.java
    │   ├── PromptResponse.java
    │   └── SavePromptResponse.java
    └── exception/LakefsException.java
```

The existing four core classes remain. AgentBuilderFactoryBean depends on PromptService, not on the lakeFS/cache/repository implementation details.

## Integrate into the existing AI service

1. Build/install the SDK; add `com.meg:react-agent-spring-sdk:0.1.0-SNAPSHOT`.
2. Remove the old AgentConfig/annotation on the application class and add integration-example/GstOdAgent.java under the application scan package. Keep the main application class as in integration-example/AiOrchestrationApplication.java.
3. Enable prompt services and, if wanted, the controller. Merge integration-example/application-additions.yml into the existing YAML; do not create duplicate top-level keys.
4. Keep existing datasource/Redis/lakeFS settings and merge the SDK Flyway location.
5. Remove the old prompt service/cache/repository/client-configuration/controller/DTO classes that have moved to the SDK. Do not keep duplicate prompt routes.
6. Update application imports from com.example.ai prompt types to the corresponding com.meg.agentsdk.prompt types. An application-specific global exception handler can stay, but update its LakefsException import if it uses the moved exception.
7. Replace ChatService using the example: inject GstOdAgent, call askAsync with request context, and map the SDK response.
8. Keep ChatController, authentication, business validation, and application response handling in the AI service.

Tests and integration examples are not packaged into the SDK JAR. The SDK has no main application or global application.yml.

## Existing core behavior

Tool visibility and execution permissions remain separate. Unknown names/approvals and duplicate MCP tool/server names are rejected. MCP tool names are used exactly as advertised, without invented prefixes. Unapproved tools retain AgentScope's standard behavior; this API does not implement confirmation/resume.

Fresh builders use independent in-memory state. Passing the same session ID does not persist conversation history. Workspace, filesystem/shell tools, memory hooks, dynamic skills, and agent tracing remain disabled by default. Existing builder-API consumers can explicitly customize a returned builder.

The original global `react-agent.permissions.allow` remains the fallback. For agents with different visible tools, provide per-agent approval lists; each replaces the global list for that agent and is validated against that agent's visible tools:

```yaml
react-agent:
  permissions:
    agents:
      gst:
        allow: [get_current_status]
      dd:
        allow: [verify_otp]
```

The keys match annotation `name` values. Approval does not expose a hidden tool. Core customization hooks in the factory remain available for existing builder-based consumers.

## Build and verification

```powershell
# JAVA_HOME should point to JDK 25+.
.\mvnw.cmd clean install
```

Tests cover two component-scanned typed agents with separate prompts/tools and approval lists, sync/async calls, concurrent requests, shutdown rejection, prompt refresh, and original core behavior, optional dependency/feature gating, paired prompt selection, custom prompt beans, transactional activation, Redis fallback/eviction, real local model/MCP/lakeFS HTTP calls, prompt refresh, upload/latest endpoints, and scoped exception handling.

Default JDBC tests use H2. An additional run uses disposable PostgreSQL with the actual migration/index. Real Redis behavior is represented by test doubles; no existing business infrastructure is called.



