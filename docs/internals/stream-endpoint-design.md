# AI Streaming Endpoint Design

Companion to [spec.md](spec.md) for SSE/stream wire behavior. When stream URLs, body fields, or server-side stream semantics change, update **this file** and the relevant sections of **`spec.md`**.

**Internals** — SSE contract and server-side stream behavior. **Intent routing & turn goal:** [spec.md § Intent recipe routing](spec.md#intent-recipe-routing-turn-goal) · [intent-recipe-routing.md](intent-recipe-routing.md). **LLM matrix & keys:** [llm-configuration.md](../using-and-extending/llm-configuration.md). **Doc index:** [README.md](../README.md). **Diagram:** [Interactive chat request path](../architecture-diagrams.md#interactive-chat-request-path-developer) · [Turn goal propagation](../architecture-diagrams.md#turn-goal-propagation-developer) · [User sequence](../architecture-diagrams.md#interactive-chat-journey-user).

## Goal

One endpoint: **agent ID + full prompt in, streamed response out**. The UI does not know or care about tools; all tool execution (getContentType, getContent, writeContent, etc.) and Spring AI orchestration happen server-side inside the endpoint.

## Contract

| | |
|--|--|
| **Method** | `POST` |
| **URL** | Plugin script path: `/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream?siteId=...` (script at `authoring/scripts/rest/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream.post.groovy`; path follows Trello pattern: plugin id path + extra segment so Studio resolves pluginId and classpath). |
| **Request body** | JSON: `{ "agentId", "prompt", "chatId?", "llm", "llmModel?", "imageModel?", "llmApiKey?", "siteId?", "contentPath?", "contentTypeId?", "displayTemplate?", "studioPreviewPageUrl?", "authoringSurface?", "formEngineClientJsonApply?", "formEngineItemPath?", "enableTools?", "omitTools?", "enabledBuiltInTools?", "skills?", "translateBatchConcurrency?", "previewToken?", … }` — see **[chat-and-tools-runtime.md § REST body](chat-and-tools-runtime.md#rest-body-advanced)**. |
| **URL `siteId` query** | **Studio session site** (where the plugin is loaded). The React client passes **`pluginRequestSiteId`** here; it must **not** be confused with POST **`siteId`** (working CMS site for tools). |
| **POST `siteId`** | **Working CMS site** for **`GetContent`**, **`ResearchSiteContent`**, writes, and other CMS tools. When it differs from the URL/session site, the client omits preview anchor fields and the server injects **Working CMS site** context. Server sets **`aiassistant.siteId`** from this field. |
| **Request headers** | Standard Studio session cookies / auth for the plugin REST call. Outbound chat traffic goes to **your configured LLM provider** (tools-loop **`/v1/chat/completions`**, Anthropic, or site **`script:{id}`**), not to a separate hosted SaaS chat stack. |
| **Response** | `Content-Type: text/event-stream` — SSE chunks consumed by **`AiAssistantChat`**. |

## Server-Side Behavior

- **LLM selection**: Per-agent **`&lt;llm&gt;`** in `ui.xml` (or widget JSON). **`StudioAiLlmKind.normalize`** accepts tool-loop vendors (**`openAI`**, **`xAI`**, **`deepSeek`**, **`llama`**, **`gemini`** / **`genesis`**, **`claude`**, **`script:{id}`**) and **rejects** blank values and unsupported ids (**`aiassistant`**, **`hostedchat`**, …). The client may omit **`llm`** when the agent has no **`<llm>`**; the server then **400**s unless **`siteId`** + **`agentId`** allow copying **`llm`** from **`/ui.xml`** — see **[llm-configuration.md](../using-and-extending/llm-configuration.md)**.
- **Tools-loop (`openAI`, `xAI`, `deepSeek`, `llama`, `gemini`, …):** Spring AI **`OpenAiChatModel`** + **RestClient** with **`AiOrchestrationTools`** (native function calling). Requires provider API keys per **[llm-configuration.md](../using-and-extending/llm-configuration.md)**. When site **`intentRecipeRouting.enabled`** is true, **`Router.route`** runs **before** the tools loop (classifier + optional recipe prefetch + **turn goal** wiring). **`claude`** and non-tools-loop transports **skip** this prelude — see **[spec.md § Intent recipe routing](spec.md#intent-recipe-routing-turn-goal)**.
- **`claude`:** Spring AI **`AnthropicChatModel`** tool loop (not the tools-loop RestClient path; **no** intent-recipe prelude).
- **`script:{id}`:** Site Groovy under **`config/studio/scripts/aiassistant/llm/{id}/`** returns a **`StudioAiLlmRuntime`** bundle; capabilities depend on the script.
- **Prompt length:** Bounded only by **your** chat host / provider limits and orchestration timeouts (**[studio-aiassistant-platform-settings.md](../using-and-extending/studio-aiassistant-platform-settings.md)**). There is **no** separate hosted SaaS prompt compaction path.
- **Note**: REST scripts depend on Groovy under `authoring/scripts/classes/plugins/<plugin-id-path>/` (this plugin: `…/org/craftercms/aiassistant/studio/`). `copy-plugin` / marketplace copy deploys that tree; if the stream fails with “unable to resolve class”, re-run plugin copy from a current plugin tree.
- **Studio plugin classpath**: Classes under `scripts/classes` compile in a **restricted** Groovy environment. **`groovy.util.XmlSlurper`** is not available there — use **JDK** `javax.xml.parsers.DocumentBuilderFactory` / `org.w3c.dom` for XML parsing (see `AiOrchestrationTools.extractFormFieldIdsFromFormDefinitionXml`).

## UI

- The Studio plugin (React) sends one prompt and displays streamed chunks.
- No tool list, no tool parameters, no tool results in the client — everything is encapsulated in the streamed reply.

<a id="intent-recipe-routing-sse"></a>

## Intent recipe routing (SSE)

On **`/ai/stream`**, after **`AiOrchestration.intentRecipeRoutingPrelude`** completes (tools-loop LLMs only), the server emits **one** SSE data frame **before** assistant tokens and tool-progress lines. Non-stream **`/ai/agent/chat`** applies the same prelude effects on the server but **does not** emit this event.

**Frame shape** (JSON in `data:` line, same envelope as other stream chunks):

```json
{
  "text": "",
  "metadata": {
    "status": "intent-recipe-routing",
    "intentRecipeRouting": { }
  }
}
```

| Field | Meaning |
|-------|---------|
| **`metadata.status`** | Always **`intent-recipe-routing`** for this event |
| **`metadata.intentRecipeRouting`** | Full routing telemetry map (see table below) |
| **`text`** | When **`outcome`** is **`matched`**, optional short recipe line for the chat UI (emoji + title); otherwise usually empty |

**`metadata.intentRecipeRouting`** — contract fields integrators and session debug logs rely on:

| Field | When present | Meaning |
|-------|----------------|---------|
| **`outcome`** | always | **`matched`**, **`chat_only`**, **`router_tool`**, **`plan`**, **`no_match`**, or **`skipped_*`** (precondition / gate / disabled) |
| **`turnGoal`** | classifier ran and goal wired | Plain-language objective for **this author message only** |
| **`successCriteria`** | optional | “Done when …” verification phrase |
| **`recipeId`**, **`recipeTitle`**, **`confidence`**, **`matchPass`**, **`routingMode`** | classifier / match | Bound recipe and classifier metadata |
| **`routerReason`** | optional | Short LLM rationale |
| **`toolsLoopDisable`**, **`toolsLoopAllowlist`**, **`toolsLoopForceTool`** | matched / tool modes | Effects applied by **`applyIntentRecipeRouteEffects`** |
| **`prefetchSteps`**, **`prefetchRan`** | **`matched`** | Recipe engine prefetch telemetry |
| **`eligibilitySkipReason`** | **`skipped_eligibility`** only | Gate reason when **`eligibilityGateEnabled: true`** |
| **`planDeferCatalogSent`**, **`planDeferWiredToolCount`**, … | plan defer | Planner catalog wire (see **[intent-recipe-routing.md](intent-recipe-routing.md)**) |

**Turn goal on the wire:** When **`turnGoal`** is set, the server also prepends **`[Studio — turn goal …]`** to the tools-loop user message and reinforces the goal in the system prompt and between tool rounds — authors do **not** see that block as a separate SSE event; it shapes server-side LLM input only.

**Ordering:** Typical stream order is `: connected` → optional **`prompt-assembly`** → **`intent-recipe-routing`** → assistant **`text`** chunks → **`tool-progress`** lines → final **`metadata.completed`**. See **[chat-and-tools-runtime.md § Prompt assembly observability](chat-and-tools-runtime.md#prompt-assembly-observability)**.

**Deep dive:** Pipeline phases, eligibility gate, confirmation JVM steps, maintainer checklist — **[intent-recipe-routing.md](intent-recipe-routing.md)**.

## Files

- **LLM / keys**: **[llm-configuration.md](../using-and-extending/llm-configuration.md)**
- **Stream endpoint**: `authoring/scripts/rest/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream.post.groovy`
- **Chat endpoint**: `authoring/scripts/rest/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/agent/chat.post.groovy`
- **Classes** (required for Spring AI + tools): `authoring/scripts/classes/plugins/org/craftercms/aiassistant/studio/` — `AiOrchestration.groovy`, etc. Deployed by plugin copy into `config/studio/scripts/classes/plugins/org/craftercms/aiassistant/studio/`.
