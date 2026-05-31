## AI Assistant — Crafter Studio Plugin Specification

**`docs/internals/spec.md`** is the **official product requirements and mechanics specification** for this repository: surfaces, `ui.xml` contracts, form pipeline, stream semantics, autonomous behavior, and REST field shapes. Implementations in **`sources/`**, **`sources/control/`**, **`authoring/scripts/`**, and install descriptors **must** match this document. Product changes that alter documented behavior require updating **this file** (or the owning companion below) in the same merge as the code, or in an immediately following PR linked from the code PR description.

**Companion specifications** (official for their topics; keep them aligned when you touch the same behavior):

| Document | Owns |
|----------|------|
| [stream-endpoint-design.md](stream-endpoint-design.md) | SSE/stream wire behavior and related server contracts |
| [intent-recipe-routing.md](intent-recipe-routing.md) | Pre-tools intent classifier, recipe match/prefetch, **turn goal** propagation, routing telemetry |
| [chat-and-tools-runtime.md](chat-and-tools-runtime.md) | Tool catalog, REST request/response fields, MCP client, operational troubleshooting contracts |
| [../using-and-extending/studio-plugins-guide.md](../using-and-extending/studio-plugins-guide.md) | **Build & install**: `yarn package`, Rollup outputs, canonical source paths vs generated `authoring/` paths, plugin id / descriptor invariants |
| [../using-and-extending/llm-configuration.md](../using-and-extending/llm-configuration.md) | **`<llm>`** identifiers, env + XML configuration, provider capability matrix, merge rules |
| [../using-and-extending/product-requirements.md](../using-and-extending/product-requirements.md) | Obligations for authors, admins, and integrators; wire-level and build contracts live in **spec.md** and the guides linked from this repository |

**Scope:** Site procedures — **[configuration-guide.md](../using-and-extending/configuration-guide.md)**. Product obligations — **[product-requirements.md](../using-and-extending/product-requirements.md)**. JVM **`-D`** flags — **[studio-aiassistant-platform-settings.md](../using-and-extending/studio-aiassistant-platform-settings.md)**. **`spec.md`** must record any **new** author-visible, wire-level, or cross-surface contract when it ships, including when the same material appears in a companion document.

**Review rule:** Code changes that alter documented behavior without updating **`spec.md`** / the relevant companion should be **blocked in review** unless the PR states a doc-only follow-up with a tracked issue (use sparingly—prefer same-merge updates).

**Audience:** Maintainers and advanced integrators — see **[developers documentation](../developers/README.md)**. **Site install & configuration:** **[admins & authors](../admins-and-authors/README.md)**. **Configuration & LLM keys:** [llm-configuration.md](../using-and-extending/llm-configuration.md). **Doc index:** [README.md](../README.md).

### Terminology

- **Studio AI assistant** — The authoring-facing assistant in Crafter Studio that this plugin provides: the form-engine control, the Helper widget on the Tools Panel or preview toolbar, and optional autonomous scheduled runs.
- **Agent catalog ids** — Chat rows in **`config/studio/ai-assistant/agents.json`** use **`agentId`** as the stable id sent on stream/chat as POST **`agentId`**.
- **Optional remote tools** — Sites may add **MCP** servers, **user tools** (Groovy), or custom integrations; this plugin does not ship a separate remote chat product.
- **Turn goal** — Plain-language objective for **this author message only** (`turnGoal`, optional `successCriteria`), classified before the tools loop and propagated into executor prompts and SSE telemetry. See § [Intent recipe routing & turn goal](#intent-recipe-routing-turn-goal).
- **Intent recipes** — Pre-tools orchestration (match, prefetch, prelude, optional `toolsLoopDisable`); not a separate wire name beside `GetContent`. Configured in site **`intent-recipes.json`** + **`tools.json`** → **`intentRecipeRouting`**.

### Overview

This repository is a Crafter Studio plugin with **two main surfaces**:

- **Interactive chat agent** — The Studio AI assistant surfaces above. Agents are configured per site; each agent selects an **LLM** and may enable **function tools**. Supported **`<llm>`** values, keys, and capabilities are listed in [llm-configuration.md](../using-and-extending/llm-configuration.md). Tools may include CMS operations, HTTP helpers, optional **MCP** remote tools when **`tools.json`** sets **`mcpEnabled: true`** (see [chat-and-tools-runtime.md](chat-and-tools-runtime.md#mcp-client-tools-streamable-http)), and site-defined Groovy tools.
- **Experimental autonomous agent framework** — Optional **AutonomousAssistants** widget in the Tools Panel: **scheduled**, **server-side**, **in-memory** runs that reuse the interactive tool catalog for supported LLMs; see § [Autonomous assistants widget](#autonomous-assistants-widget-tools-panel).

**Diagrams:** [Architecture & diagrams](../architecture-diagrams.md) — [system context](../architecture-diagrams.md#system-context-architecture), [logical layers](../architecture-diagrams.md#logical-architecture-design), [stream path](../architecture-diagrams.md#interactive-chat-request-path-developer), [turn goal propagation](../architecture-diagrams.md#turn-goal-propagation-developer), [component registration](../architecture-diagrams.md#studio-ui-component-registration-developer).

It currently focuses on:

- **Studio UI Helper widget**: Preview toolbar and/or Tools Panel entry points for the assistant (`craftercms.components.aiassistant.Helper`).
- **Autonomous runs (Tools Panel)**: Optional widget for scheduled in-memory assistant steps (prototype); see § [Autonomous assistants widget](#autonomous-assistants-widget-tools-panel).

The UI uses a combination of:

- **Crafter Studio UI** (`@craftercms/studio-ui`) components (e.g., `DialogHeader`, `MinimizedBar`), and
- **Material UI** (`@mui/*`) primitives and icons.

### Code Locations (Source vs Built)

- **Source code**: `sources/src/`
- **Built plugin assets served by Studio**: `authoring/static-assets/plugins/org/craftercms/aiassistant/studio/aiassistant/` (e.g. `components/index.js`)

### User-Facing Surfaces

#### Helper Widget (Studio UI)

- **Entry**: `sources/src/AiAssistantHelper.tsx`
- Embedded in Studio UI (e.g., Tools Panel, Preview toolbar) to open the **Studio AI assistant** (React chat UI). Each agent uses an explicit **`llm`** (**`openAI`**, **`claude`**, **`script:{id}`**, …).
- Current implementation renders:
  - either an `IconButton` whose glyph is the first agent’s `icon` from `agents.json` (mapped in `agentIcon.tsx`), falling back to the bundled assistant mark when unset, or
  - a `ToolsPanelListItemButton` (sidebar) with the bundled logo widget id (`logoWidgetId` in `consts.ts`)
  - With **one** configured agent, the toolbar click opens that agent directly (no menu). With **multiple** agents, a `Menu` lists each row.
- Chat agents load from **`config/studio/ai-assistant/agents.json`** (Project Tools → Agents). Placeholder-label dedupe uses **`AI_ASSISTANT_AGENT_LABEL_FALLBACK`** in **`agentConfig.ts`** when a catalog has both a fallback label and author-defined rows.
- Otherwise opens the Experience Builder ICE tools panel (or a floating dialog when `openAsPopup` is set on the agent).

#### Autonomous Assistants Widget (Tools Panel)

- **Widget id**: `craftercms.components.aiassistant.AutonomousAssistants` (constant `autonomousAssistantsWidgetId` in `sources/src/consts.ts`).
- **Component**: `sources/src/AiAssistantAutonomousAssistants.tsx` — registered in `sources/index.tsx` with the same **`plugin`** element as the Helper (`org.craftercms.aiassistant.studio` / `aiassistant` / `components` / `index.js`).
- **Purpose (prototype)**: **Studio AI assistant — autonomous** runs: server-side **in-memory** state and a small **supervisor** loop that **polls often** (fixed ~10s tick) and, on each tick, decides per agent whether to dispatch a step. Each agent’s **`schedule`** string is mapped to a **minimum period** between runs (`AutonomousScheduleProbe`); the tick interval is **not** the agent’s run interval — a tick only runs an agent when that period has elapsed since **`lastRunMillis`** (or **`nextStepRequired`** is set). For **`llm`** **`openAI`** (default), each step always uses the same **Studio native `tools[]` catalog** as interactive chat and runs the tool loop on the server, then expects a **final JSON-only** reply per the worker contract. For drafting or transforming text without other tools in that inner model call, the model uses the **`GenerateTextNoTools`** tool (one-shot completion; not a separate orchestrator mode). Legacy **`<enableTools>`** on autonomous agents is **ignored**. State lives in the Studio JVM until restart or **Destroy in-memory store** in the widget’s Advanced section.
- **Where to place it**: **`craftercms.components.ToolsPanel`** → `configuration` → `widgets` (merged on plugin install via **`craftercms-plugin.yaml`**). Optional **Helper** in the same list is manual **`ui.xml`** only; see **`docs/examples/studio-ui-aiassistant-fragments.xml`**.
- **How Studio passes props**: After the plugin registers, Studio’s **`Widget`** spreads the widget’s **`<configuration>`** onto the React component as **root props** (not only `props.configuration`). The autonomous parser reads **full widget props first**, then nested `configuration`, so `autonomousAgents` is found in either shape.

##### Configuration Shape (`autonomousAgents`)

Define autonomous rows in **`config/studio/ai-assistant/agents.json`** with **`mode: autonomous`** (Project Tools → Agents). The **AutonomousAssistants** widget in **`ui.xml`** is placement only (title, icon).

| Field (XML / JSON) | Required | Description |
|--------------------|----------|-------------|
| **`name`** or **`label`** | yes | Display name; used with site + **scope** to build the internal full agent id. |
| **`schedule`** | no | Quartz **6-field** cron (`sec min hour dom month dow`). Default `0 0 * * * ?` (hourly). `AutonomousScheduleProbe` maps a small set of patterns (e.g. `0 * * * * ?` → **once per minute**; `0/10 * * * * ?` → every **10 seconds**; `0 0 * * * ?` → **hourly**). |
| **`prompt`** | no | Base instructions for the run; the worker appends a strict JSON reply contract (report, next step, notes, optional human tasks). |
| **`scope`** | no | `project` (default), `user`, or `role` — controls which signed-in user may see that agent in status/UI and use agent-scoped control actions (`AutonomousScopeGuard`). |
| **`llm`** | no | Normalized default **`openAI`** for this feature. |
| **`llmModel`** | no | OpenAI model id (e.g. `gpt-4o-mini`). |
| **`imageModel`** | no | Reserved / aligned with other agents; autonomous worker does not call image generation today. |
| **`openAiApiKey`** | no | Same testing-only semantics as Helper agents — only when no server-side key (host env per **[llm-configuration.md](../using-and-extending/llm-configuration.md)**); see that doc. |
| **`startAutomatically`** | no | Default **true**. When **false**, `sync` registers the agent as **stopped** until **Start** on that agent. Aliases: **`start_automatically`**, **`automaticallyStart`**. **`disable_supervisor`** sets every non-disabled agent to **stopped** (preserves **`manualStop`** when the author had used **Stop** or `stopSelf`). **`enable_supervisor`** sets **waiting** only for agents with `startAutomatically` true **and** not **`manualStop`**; others stay **stopped** until **Start**. Re-sync restores **disabled** / **error** always; restores **stopped** only when **`manualStop`** or `startAutomatically` is false. While the supervisor is off, `sync` forces **stopped** for non-disabled / non-error agents. |
| **`stopOnFailure`** | no | Default **true**. When **true**, a failed worker run sets **`state.status`** to **`error`** (other agents keep running). When **false**, failure is stored in **`state.lastError`** and the agent returns to **`waiting`** with **`nextStepRequired`** so the next tick retries. Aliases: **`stop_on_failure`**. |
| **`skills`** | no | Optional markdown URL skills (enabled rows) for **QueryExpertGuidance**. Autonomous sync stores **`skills`** on the agent definition for the worker. |
| **`manageOtherAgentsHumanTasks`** | no | Cross-agent human-task ownership; see worker / control script behavior. |

Example (minimal):

```xml
<widget id="craftercms.components.aiassistant.AutonomousAssistants">
  <plugin id="org.craftercms.aiassistant.studio" type="aiassistant" name="components" file="index.js"/>
  <configuration>
    <autonomousAgents>
      <agent>
        <name>Site health check</name>
        <schedule>0 * * * * ?</schedule>
        <prompt>You are an autonomous assistant for this Crafter site. Reply with JSON only as instructed by the server.</prompt>
        <scope>project</scope>
        <llm>openAI</llm>
        <llmModel>gpt-4o-mini</llmModel>
      </agent>
    </autonomousAgents>
  </configuration>
</widget>
```

##### Plugin REST Scripts (`/studio/api/2/plugin/script/...`)

All require an authenticated Studio session (same cookies / auth as other plugin scripts).

| Method | Path suffix | Role |
|--------|-------------|------|
| `POST` | `…/autonomous/assistants/sync` | Body: `{ siteId, agents }` — agents mirror parsed definitions from the widget; registers agents, ensures state rows, ensures supervisor **threads** exist; the supervisor **enabled** flag stays **off** until **`enable_supervisor`** / **Start system**. |
| `GET` | `…/autonomous/assistants/status?siteId=…` | Agents the caller may see, supervisor flags/tick, per-agent **`definition`** and **`state`**, plus aggregate fields below. |
| `POST` | `…/autonomous/assistants/control` | Body: `{ siteId, action, agentId?, taskId? }`. |

**`status` aggregate fields** (top-level JSON alongside agents/supervisor)

- **`openHumanTaskCount`**: Count of human tasks with **`status: open`** across all agents visible to the caller (for a badge in the widget header).
- **`hasAgentError`**: `true` if any visible agent has **`state.status === "error"`**.
- **`agentsInError`**: Array of `{ agentId, name, lastError }` for agents in error (`lastError` includes **`message`**, **`at`**, **`exceptionClass`**, optional **`stackTrace`**, and **`stopOnFailure`** as recorded at failure time).
- Supervisor snapshot may include **`supervisorHaltReason`** when **`haltSupervisorAfterAgentFailure`** was used (legacy); worker failures **do not** halt the supervisor—only the failing agent is stopped or retried per **`stopOnFailure`**.

**`control` actions**

- **Supervisor**: `enable_supervisor`, `disable_supervisor`, `shutdown_pools`, `destroy_store` (no `agentId`).
- **Per agent** (require **`agentId`**; scope-checked): `start_agent`, `stop_agent`, `execute_now`, `disable_agent`, `enable_agent`.
- **Human tasks** (require **`agentId`** + **`taskId`**): `complete_human_task`, `dismiss_human_task`, `reopen_human_task`.
- **Recovery**: `clear_agent_error` (requires **`agentId`**; scope-checked) — clears **`state.lastError`**, sets **`state.status`** to **waiting** with **`nextStepRequired: false`** so ticks can run again for that agent (supervisor stays as-is).

##### Human Tasks (Model → UI)

On each successful worker step, the model may return JSON with optional **`humanTasks`**: `[{ "title": string, "prompt": string, "assignedUsername"?: string, "assignedName"?: string }, …]` (aliases `assigneeUsername` / `assigneeName` are accepted). Prompts must be self-contained text a human can execute or paste into another assistant. Optional assignee fields set the Studio user shown in the widget when the agent’s instructions call for routing a task to someone. The server merges new rows into **`state.humanTasks`** (deduped by prompt text against non-dismissed tasks), then **trims to at most 10** rows by removing the **oldest** (`createdAt`) first. Each worker run also appends an **OpenSearch digest** of indexed `/site/website/` pages (paths, types, titles) to the user message as optional site context for whatever mission the agent’s prompt defines (requires `sync`/`status` to have registered `applicationContext` + security on an HTTP thread). Each row has **`id`**, **`title`**, **`prompt`**, **`status`** (`open` \| `done` \| `dismissed`), optional **`assignedUsername`** / **`assignedName`**, and timestamps. The widget lists tasks across agents with filters, assignee controls, toggle done, dismiss, or **copy prompt** to the clipboard.

##### Model JSON “Tools” (Same Reply Object As `humanTasks`)

The worker instructs the model to optionally return **task id arrays** and **`stopSelf`** so a single JSON payload can update human tasks without extra REST calls (applied in memory before the final state write):

- **`dismissHumanTaskIds`**, **`completeHumanTaskIds`**, **`reopenHumanTaskIds`**: string arrays of existing task **`id`** values on that agent’s **`state.humanTasks`**.
- **`stopSelf`**: when `true` after a successful step, the agent is moved to a **stopped** (idle) disposition instead of **waiting** for the next tick.

If the worker throws or the model response cannot be parsed as JSON, **`state.lastError`** is always populated (**`message`**, **`at`**, **`exceptionClass`**, **`stackTrace`** capped on the server). If the agent’s definition has **`stopOnFailure: true`** (default), **`state.status`** becomes **`error`** and that agent is skipped on tick until **`clear_agent_error`** / **Clear error**. If **`stopOnFailure: false`**, status returns to **`waiting`** with **`nextStepRequired: true`** so the next supervisor tick retries; other agents are unaffected and the supervisor is **not** halted.

**Context size:** The worker sends a **summarized** copy of state in the user prompt (recent reports/history, trimmed task prompts). The native Chat Completions tool loop **truncates** each tool result wire payload (default cap **36k** characters) so huge **`ListPagesAndComponents`** / **`GetContent`** responses cannot exhaust the model context window. **`GetContent`** results on the wire include **repository metadata** (e.g. **`path`**, **`contentTypeIdFromXml`**) alongside **`contentXml`** so the model does not lose grounding when the XML body is long. After a successful **`FetchHttpUrl`** in the Studio tools loop, the server may inject a short **`role:user` authoring-goal anchor** (trimmed original author text) so reference HTML/CSS stays paired with intent when earlier messages are shrunk. **`FetchHttpUrl`** JSON may also include **`stylesheetHrefs`** parsed from HTML for CSS entry points. **`GenerateImage`** is a special case: a full **`data:image/...;base64,...`** must **not** be placed on the **`role:tool` wire** (many hosts reject the request with **`context_length_exceeded`**). The plugin stores the bitmap server-side by **`tool_call_id`**, sends a **compact** tool JSON (**`inlineImageRef`** + short instructions), and expands **`studio-ai-inline-image://…`** into the real image URL only in the **author-facing** assistant text (e.g. final SSE chunk). If the model omits markdown that references those placeholders, the server **appends** minimal **`![](studio-ai-inline-image://…)`** lines before expansion so the chat still renders the image. In **`MarkdownMessage`**, very long **`![alt](data:image/…)`** destinations are rewritten to short **`studio-ai-blob-ref://…`** tokens **before** markdown parse so micromark/GFM still emits **`img`** nodes; the renderer resolves the token back to the wire **`data:`** URL and **`StudioDraggableImage`** converts it to a **`blob:`** object URL for CSP-safe display and drag.

**Authoring “brain” parity:** Each autonomous native-tools step prepends the same system stack as interactive **`/ai/stream`** — **`ToolPrompts.getLlm_AUTHORING_INSTRUCTIONS()`**, optional **plugin RAG** (`PluginRagVectorRegistry.adjustAuthoringCore` — site **`pluginRag`**, system prompt before tools), site-id tool lines, optional **skills** appendix + **`QueryExpertGuidance`** registration when enabled **skills** are on the agent definition (per-agent URLs; limits in **`agentSkillsRag`**), and **`PlanOrchestration.machineInstructionsAddendum()`**, then the agent’s JSON reply contract in a following section. **Plugin RAG vs agent skills:** **[configuration-guide §9.2.1](../using-and-extending/configuration-guide.md#cg-9-2-1)**.

##### Widget UX (Errors and Open Tasks)

- Header shows a **warning badge** with **`openHumanTaskCount`** when greater than zero.
- When **`hasAgentError`** or a halt reason is present, the panel uses **error styling** (border/background) and lists **agents in error** with **`lastError.message`** (and detail when present); each row may call **`clear_agent_error`**.
- For site-level or custom Studio chrome, the widget sets **`document.body`** attributes **`data-cq-autonomous-open-tasks`** (count) and **`data-cq-autonomous-has-error`** (`"true"` / `"false"`) while mounted so CSS can tint a Tools icon or shell element if desired (Studio’s default Tools list icon is not modified by the plugin).

##### Implementation Pointers (Groovy)

- `authoring/scripts/classes/plugins/org/craftercms/aiassistant/studio/engine/autonomous/` — registry, state store, supervisor, worker, scope guard, id builder, schedule probe, **`AutonomousAssistantRuntimeHooks`** (Spring context + auth for worker threads), **`AutonomousSiteDigestBuilder`** (authoring OpenSearch digest for prompts).
- `authoring/scripts/rest/plugins/org/craftercms/aiassistant/studio/aiassistant/autonomous/assistants/` — `sync.post`, `status.get`, `control.post`.

### Assistant Popover (Floating Chat Shell)

- **Component**: `sources/src/AiAssistantPopover.tsx`
- **What it renders:** Crafter Studio **`DialogHeader`**, **`AiAssistantChat`** (streaming **`/ai/stream`** against your configured **`llm`**), **`MinimizedBar`** when minimized, and **`AlertDialog`** scaffolding for close/minimize confirmation (some close behavior may still be gated).

### Constants & Identifiers

Defined in `sources/src/consts.ts`:

- **Widget ids**
  - `logoWidgetId`
  - `chatWidgetId`
  - `popoverWidgetId`
  - `helperWidgetId`
  - `autonomousAssistantsWidgetId` (`craftercms.components.aiassistant.AutonomousAssistants`)
  - `projectToolsAiAssistantConfigWidgetId` (`craftercms.components.aiassistant.ProjectToolsConfiguration`) — **Project Tools** single entry with tabs **UI**, **Agents**, **Recipes**, **Integrations** (sub-tabs: LLMs, Image generators, Tools, MCP), **Secrets**, **Prompts and Context**; opened in a **large modal dialog** (legacy widget ids open the same shell with a different default tab).
  - `projectToolsCentralAgentsWidgetId`, `projectToolsScriptsSandboxWidgetId`, `projectToolsStudioUiSettingsWidgetId` — **legacy** widget ids; **`ScriptsSandboxConfiguration`** opens **Integrations → Tools** (`tools.json`, `user-tools/registry.json`, Groovy list); **`CentralAgentsConfiguration`** → **Agents**; **`StudioUiSettings`** → **UI**

### Plugin ID and Studio File URL

- **Plugin ID**: `org.craftercms.aiassistant.studio` — must be used in `ui.xml` for both the Tools Panel Helper and the Preview Toolbar icon so Studio serves the correct path.
- **Installed path**: Plugin assets are under `config/studio/static-assets/plugins/org/craftercms/aiassistant/studio/aiassistant/` (e.g. `components/index.js`).
- **Plugin file requests**: Studio serves plugin JS from `/studio/1/plugin/file?siteId=...&pluginId=org.craftercms.aiassistant.studio&type=aiassistant&name=components&file=index.js`. These requests require **authenticated session** (same as preview): send the same cookies (e.g. `JSESSIONID`, `XSRF-TOKEN`, `crafterPreview`, `crafterSite`) or JWT/bearer auth that you use for Studio and preview. Unauthenticated requests will redirect to login and can cause 404-like behavior in the UI.
- **Bundle `PluginDescriptor.id`** (`sources/index.tsx`): Must match `plugin.id` in `craftercms-plugin.yaml` (`org.craftercms.aiassistant.studio`). Studio deduplicates `registerPlugin` on that id; a different id can let an earlier registration win and leave **`craftercms.components.aiassistant.Helper`** or **`craftercms.components.aiassistant.AutonomousAssistants`** unregistered (“Component … not found”).

### UI Placement (Toolbar vs Sidebar)

- **Tools Panel**: **AutonomousAssistants** is installed by the plugin descriptor under **`ToolsPanel` → `configuration` → `widgets`**. The **Helper** in the left rail is optional manual **`ui.xml`** only (Preview toolbar Helper is installed by default).
- **Preview Toolbar**: Marketplace install merges the Helper under **`PreviewToolbar` → `configuration` → `rightSection` → `widgets`** (avoids Studio **`performConfigurationWiring`** singleton-descent failures on **`middleSection/widgets`**). For an icon **next to the address bar**, move the merged **`<widget id="craftercms.components.aiassistant.Helper">…</widget>`** to **`middleSection` → `widgets`** in `config/studio/ui.xml` (same **`<configuration ui="IconButton"/>`** shape). The **`element`** root in **`craftercms-plugin.yaml`** is the **`<widget>`**; existing sites can paste from **`docs/examples/studio-ui-aiassistant-fragments.xml`** instead.

#### Common Gotchas

- **Two widget entries**: If you configure the Helper in **both** Tools Panel and Preview Toolbar, update both widget entries when changing agent labels/prompts or you’ll still see old values depending on where you click.
- **Form assistant agents**: Chat agents come **only** from **`config/studio/ai-assistant/agents.json`** (sync XHR in `main.js`), deduped by **agentId** + **label** (same composite key as stream **`agentId`** + label).
- **Form read-only / view mode**: When the content form is opened read-only (field or whole form), the form AI assistant **does not** load the plugin UI for that field: no portaled panel, no form-shell widen, and no `html.aiassistant-form-panel-active` body inset.
- **Commit required**: Studio reads `config/studio/ui.xml` from the site sandbox repo; changes are most reliable after the `ui.xml` edits are **committed** in the site’s `sandbox` git repository.

<a id="studio-ui-flags-studio-uijson"></a>

### Studio UI Flags (`studio-ui.json`)

**Path:** `config/studio/scripts/aiassistant/config/studio-ui.json` (Studio module **`studio`**).

**Purpose:** Per-site **runtime** switches read by the React bundle (sync **`get_configuration`**, per-site cache). They **do not** remove **`ui.xml`** merges; they gate rendering or client-only augmentation.

| Key | Behavior |
|-----|----------|
| **`showAiAssistantsInTopNavigation`** | When **`false`**, **`AiAssistantHelper`** does not render the **`ui="IconButton"`** preview **toolbar** control. Tools Panel **`ListItemButton`** Helper is unchanged. |
| **`showAutonomousAiAssistantsInSidebar`** | When **`true`**, **`AutonomousAssistants`** renders in the Tools Panel sidebar (experimental). Omitted or **`false`** → no UI (widget may stay in **`ui.xml`**). |
| **`contentTypeImageAugmentationScope`** | **`all`** \| **`none`** \| **`selected`** — controls the preview **content-types** bus patch that sets **`allowImagesFromRepo`** on **image-picker** fields using the AI URL datasource (Experience Builder drag targets). |
| **`contentTypeIdsForImageAugmentation`** | Used when scope is **`selected`**: array of content-type ids (normalized with leading **`/`**). |

**Bulk form field:** **Project Tools → AI Assistant** → **UI** tab may insert/remove a marked **`ai-assistant`** field (`<type>` must match form-control **`getName()`**, not `pluginId/name`) in **`form-definition.xml`** (implementation: `sources/src/aiAssistantFormControlBulk.ts`). **Add** also repairs legacy bulk rows that used **`org.craftercms.aiassistant.studio/ai-assistant`** (Studio shows “Control not available” for that type).

**Catalog REST:** `GET /studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/content-types/list?siteId=<site>` — Groovy delegates to **`StudioToolOperations.listStudioContentTypes`**.

**Chat composer placeholders:** Example prompt text uses native **`placeholder`** on the main **`TextField`** (grey hint until the author types); central **`agents.json`** editor uses placeholders on quick-prompt and autonomous system-prompt fields.

### Site secrets (`secrets.json`)

**Path:** `config/studio/scripts/aiassistant/config/secrets.json` (Project Tools → **Secrets**). Stores named credential slots (built-in LLM provider rows and integration keys such as **`serpapi_api_key`** seeded on first install with **`${env:…}`** defaults authors may override). Values may use **`${env:VAR}`**, Crafter **`${enc:…}`** ciphertext, or encrypted literals; the admin UI does not return decrypted secrets after save.

**Load:** `StudioAiAssistantSecretsService` reads the file via the same Studio configuration path as **`tools.json`** (`readStudioConfigurationUtf8`). A missing or blank file yields an **empty** in-memory document at runtime — not a synthetic copy of the install-time default catalog.

**Resolve:** `resolveSecretKey` uses **only** the value stored for that key in the committed file (no catalog substitution when a row is absent). Macros expand via `StudioAiSecretMacroResolver`: **`${env:…}`** from the Studio JVM; **`${enc:…}`** via **`textEncryptor.decrypt`** on Studio 4.x (encrypt-only `EncryptionService`); **`${secret:…}`** for indirection without cycles. Resolved plaintext stays server-side for LLM calls, **`SerpApiWebSearch`**, and **`${secret:key}`** in MCP **`headers`**. **`SerpApiWebSearch`** does **not** read a separate host env bypass when resolution fails.

Per-agent **`llmSecretKey`** in **`agents.json`** selects a **custom** secret entry (or the built-in row for the agent’s current **`llm`** provider). LLM providers may still apply documented host env / JVM fallbacks **after** secrets resolution — see **[llm-configuration.md](../using-and-extending/llm-configuration.md)** and **[configuration-guide.md §4](../using-and-extending/configuration-guide.md#cg-4)**.

### Agent catalog (`agents.json`)

Chat and autonomous agents are defined in **`config/studio/ai-assistant/agents.json`** (Project Tools → AI Assistant → Agents). The Helper, form control, preview overlay, and server stream merge read this file only.

You can configure one or more **chat** agents (`mode: chat` or omitted) so the toolbar shows a **dropdown** when multiple agents exist, or opens chat **directly** when only one is configured. By default, chat opens in the **Experience Builder right (ICE) tools panel** and **edit mode** is turned on if it was off. Set **`openAsPopup: true`** on a row to use a **floating dialog** instead. Each chat row has **`label`**, optional **`icon`**, system-generated **`agentId`** (UUID, read-only in Project Tools; stream **`agentId`**), **`llm`** / **`llmModel`** / **`imageModel`**, optional **`llmSecretKey`** (credentials from **`secrets.json`**), optional **`prompts`** (quick chips), **`enableTools`**, **`enabledBuiltInTools`**, etc. See **[llm-configuration.md](../using-and-extending/llm-configuration.md)**.

**Autonomous** rows use **`mode: autonomous`** with **`name`**, **`schedule`**, **`prompt`**, **`scope`**, and the same LLM fields.

#### Example catalog excerpt

```json
{
  "version": 1,
  "agents": [
    {
      "mode": "chat",
      "agentId": "00000000-0000-4000-8000-000000000002",
      "label": "Authoring Assistant",
      "llm": "openAI",
      "llmModel": "gpt-4o-mini",
      "imageModel": "gpt-image-1-mini",
      "icon": "@mui/icons-material/AutoAwesomeRounded",
      "enableTools": true,
      "prompts": [
        { "userText": "What can you help me with?" },
        {
          "userText": "Summarize this page",
          "additionalContext": "Use bullet points. Prefer the current form/page context."
        }
      ]
    },
    {
      "mode": "autonomous",
      "name": "Prototype agent",
      "schedule": "0 * * * * ?",
      "prompt": "You are an autonomous assistant. Reply with JSON only as instructed by the server.",
      "scope": "project",
      "llm": "openAI",
      "llmModel": "gpt-4o-mini"
    }
  ]
}
```

- **`agentId`** — Stable id for stream **`agentId`** and form-engine visibility toggles.
- **`label`** — Display name in menus and chat chrome.
- **`icon`** — Optional Studio **`SystemIcon`** id (e.g. **`@mui/icons-material/ChatRounded`**) or plugin widget id.
- **`llm`** — Required for predictable routing (**`openAI`**, **`claude`**, **`script:{id}`**, …). Unsupported values (**`aiassistant`**, **`hostedchat`**, …) **fail** **`StudioAiLlmKind.normalize`**.
- **`llmModel`**, **`imageModel`**, **`imageGenerator`**, **`llmApiKey`** (testing only), **`enableTools`**, **`enabledBuiltInTools`**, **`translateBatchConcurrency`** (1–64), **`skills`**, **`openAsPopup`**, **`prompts`** — See Project Tools editor and **[llm-configuration.md](../using-and-extending/llm-configuration.md)**.

**`ui.xml`** only registers widget **placement** (Helper, AutonomousAssistants, **`plugin`** line, optional **`ui="IconButton"`**). It does **not** define agents.

### Server-side Chat (AiOrchestration; Spring AI Is Multi-vendor)

When the plugin’s **REST** chat or stream endpoints are used (`ai/agent/chat` or `ai/stream`), the server uses **`AiOrchestration`**, which selects the backend from **`llm`** in the JSON body (mirrors widget `<llm>`). **Spring AI** supplies several **`ChatModel`** integrations (including **`OpenAiChatModel`** for hosts on the **OpenAISpec** chat/tools wire, and **`AnthropicChatModel`** for Anthropic); Spring’s **`OpenAi*`** type names reflect that wire client shape, not “only OpenAI Inc.’s product.” Built-in tools-loop sessions are built by **`OpenAiSpecSpringAiLlmRuntime`**.

<a id="intent-recipe-routing-turn-goal"></a>

#### Intent recipe routing & turn goal (interactive chat)

**Full pipeline:** **[intent-recipe-routing.md](intent-recipe-routing.md)** · **SSE shape:** **[stream-endpoint-design.md § Intent recipe routing SSE](stream-endpoint-design.md#intent-recipe-routing-sse)** · **Diagram:** [turn goal propagation](../architecture-diagrams.md#turn-goal-propagation-developer).

Before the native **tools loop** runs on an interactive turn, the server may classify **what the author wants this message to accomplish** and optionally bind a whole-turn **intent recipe**. This prelude is **not** used for **autonomous** workers (they call **`llmHeadlessNativeToolsCompletion`** directly).

**When the prelude runs**

| Condition | Prelude |
|-----------|---------|
| **`llm`** uses the **tools-loop RestClient** path (`openAI`, `xAI`, `deepSeek`, `llama`, `gemini` / `genesis`, or **`script:{id}`** with tools-loop wire) | Eligible |
| **`llm`** is **`claude`** (Anthropic tool loop) | **Skipped** |
| POST **`omitTools: true`** or form-engine client-forward path that bypasses prelude | **Skipped** |
| Site **`tools.json`** → **`intentRecipeRouting.enabled`** is **`false`** | **Skipped** (`outcome: skipped_disabled`) |
| Preconditions fail (empty prompt, no API key, empty catalog, …) | **Skipped** (`skipped_*`); tools loop may still run **without** a turn goal |

Entry: **`AiOrchestration.intentRecipeRoutingPrelude`** → **`Router.route`** → **`Router.matchPass`** (JSON-only LLM classifier). Classification uses the **`Current request:`** slice of the wire prompt as primary author text; repository anchor is context, not the sole routing signal.

**Router LLM JSON** (parsed by **`AuthoringIntentRecipeRouter.parseRouterJson`**; **`extractJsonPayload`** tolerates leading prose before `{…}`):

| Field | Required | Meaning |
|-------|----------|---------|
| **`mode`** | yes | **`chat_only`** \| **`recipe`** \| **`tool`** \| **`plan`** |
| **`turnGoal`** | yes | Plain-language objective for **this turn only** (server fallbacks when omitted) |
| **`successCriteria`** | no | Optional “done when …” verification phrase |
| **`recipeId`** | when `mode: recipe` | Whole-turn recipe id from catalog |
| **`toolName`** | when `mode: tool` | Single wired tool allowlist |
| **`confidence`** | no | `0..1`; **`recipe`** binds only when ≥ **`intentRecipeRouting.minConfidence`** (default **0.55**) |
| **`reason`** | no | Short classifier rationale (telemetry / fallbacks) |

**Prelude outcomes** (`intentRecipeRoutingTelemetry.outcome`):

| `outcome` | Effect |
|-----------|--------|
| **`matched`** | Recipe prefetch + prelude on **`userTextForToolsLoop`**; may set **`toolsLoopDisable`**, **`toolsLoopAllowlist`**, **`toolsLoopForceTool`** |
| **`chat_only`** | Tools loop disabled for the turn |
| **`router_tool`** | Tools loop restricted to one tool |
| **`plan`** / **`no_match`** | Plan-defer hint + catalog; tools loop runs with **## Plan**; **`turnGoal`** guides tool choice |
| **`skipped_*`** | Classifier not run or gate blocked; **no** **`turnGoal`** wired |

**Turn goal propagation** (classifier outcomes only — not **`skipped_*`**): **`AuthoringTurnGoal.wireIntoRouteResult`** via **`Router.wireAuthorTurnGoal`** sets **`turnGoal`** / **`successCriteria`** on telemetry, prepends **`[Studio — turn goal …]`** to **`userTextForToolsLoop`**, and stores **`authorTurnGoalBlock`** on the session bundle. The tools-loop executor also receives **`AuthoringTurnGoal.appendToSystemWireMessage`** (system appendix) and **`formatMidLoopReminder`** (user message between tool rounds). Each new author message gets a **fresh** router classification; prior turns are context only.

**Stream SSE:** On **`/ai/stream`**, after prelude completes, the server emits one early frame with **`metadata.status: "intent-recipe-routing"`** and **`metadata.intentRecipeRouting`** (full telemetry map, including **`turnGoal`**). When **`outcome: matched`**, optional **`text`** may carry a short recipe line for the chat UI. Non-stream **`/ai/agent/chat`** runs the same prelude logic but does **not** emit this SSE row.

**Configuration:** Project Tools → **Recipes** tab; **`config/studio/scripts/aiassistant/config/tools.json`** → **`intentRecipeRouting`**; site catalog **`config/studio/scripts/aiassistant/config/intent-recipes.json`** ( **`customRecipesPath`** ). Admin overview: **[configuration-guide §9.0](../using-and-extending/configuration-guide.md#cg-9-0)**.

- **`openAI`** / **`xAI`** / **`deepSeek`** / **`llama`** / **`gemini`**: **`OpenAiChatModel`** with **`StudioAiToolRegistry`** / **`AiOrchestrationTools.build`** (GetContent, **ListContentDependencyScope**, WriteContent, ListPagesAndComponents, **GenerateImage**, etc.) and native tool calling when tools are enabled.
- **`claude`**: **`AnthropicChatModel`** with the Spring AI Anthropic tool loop (`AiOrchestrationTools` registration path for Claude).
- **`WriteContent` (site `*.xml`):** For **required** top-level **image-picker** fields that are still **empty**, **`StudioToolOperations`** may set the field text to a **`data:image/png;base64,...`** placeholder generated in-process (same pattern as studio-ui **`generatePlaceholderImageDataUrl`** / Experience Builder). No fixed repository path and no copying of arbitrary form **defaultValue** text into the item. For **required** or **`minSize`‑constrained** top-level **`checkbox-group`** fields backed by a **taxonomy** datasource (datasource **`type`** contains `taxonomy`, e.g. simple taxonomy), **`WriteContent`** may append **`item`** rows (`key` + typed value element such as **`value_smv`**) from the taxonomy list XML under **`/site/...`** until the constraint is satisfied (deterministic order: first unused keys from the taxonomy file).

- **LLM behavior (all surfaces) — conversation continuity and tool omission:** **`AiAssistantChat`** prepends an abbreviated **prior user/assistant turns** block to the wire prompt on every send (XB/ICE sidebar, floating dialog, and form-engine assistant). Optional POST **`omitTools: true`** or quick prompt **`&lt;omitTools&gt;true&lt;/omitTools&gt;`** drops tools for that single request on **any** surface; otherwise **`enableTools`** / agent defaults apply.

<a id="working-cms-site-cross-site"></a>

- **Working CMS site (cross-site chat):** Authors may keep Studio on site **A** while directing CMS tools at site **B** (sticky **`set site to B`**, per-turn **`in site B`**, or equivalent). The client sends POST **`siteId`** = working site and uses the **active Studio site** only for the plugin script URL query (`pluginRequestSiteId`). The server sets request attribute **`aiassistant.siteId`** from the POST body; **`StudioToolOperations.resolveEffectiveSiteId`** and **`AuthoringPreviewContext.ensureToolArgsSiteId`** **always** use that working site for CMS tools (they override a model-supplied **`siteId`** that echoes session context). When working site ≠ session site, the client **omits** preview **`contentPath`** / **`contentTypeId`** / **`displayTemplate`** / **`studioPreviewPageUrl`** so session preview is not treated as repository truth for **B**; the orchestration prompt includes a **Working CMS site** block. Plugin-bundled **`authoring-intent-recipes-default.json`** and empty **`tools.json`** fall back to the **session** site sandbox when the working site has no copy. **`open_page_inquiry`** requires a servlet-bound **`/site/.../*.xml`** anchor on the working turn; without it, routing defers to the tools-loop **## Plan** (see **[intent-recipe-routing.md](intent-recipe-routing.md)**).

- **Experience Builder / ICE (`embedTarget=icePanel`)** — Chat uses Studio **preview** hooks. The stream request may include **`contentPath`** / **`contentTypeId`** when working site matches the session site; the server appends **repository** authoring context so tools align with **saved** content in git.
- **Content-type form assistant** (`getAuthoringFormContext` from `control/ai-assistant`) — Authoritative item state is the browser **`form.model`** until the author clicks **Save**. The UI sends **`authoringSurface: "formEngine"`** and **omits** preview `contentPath` / `contentTypeId` so the server does not imply repo == open form. Each send appends a form appendix: **form-definition.xml**, **`CStudioForms.Util.serializeModelToXml(form, false)`** (Save-shaped live XML), optional **model JSON**, plus instructions for a fenced JSON object **`aiassistantFormFieldUpdates`** (maps field ids to string values). When the stream completes, the client parses that block and applies updates via **`form.updateModel`**, control **`setValue`**, **`renderValidation`**, and section **`notifyValidation`**. **`AuthoringPreviewContext.appendFormEngineAuthoringNotice`** (only when `authoringSurface: formEngine`) adds a **short** note that tools read/write the repo, not the open form. **Strong** “return `aiassistantFormFieldUpdates` JSON for the browser to apply” instructions are appended **only** when the client sends **`formEngineClientJsonApply: true`** (`appendFormEngineClientJsonApplyInstructions`). **Experience Builder / ICE** must **not** send `authoringSurface: formEngine` or that flag — they use **`contentPath`** / **`contentTypeId`** and the normal preview block so Spring AI tools can update the repository.

- **Non-streaming** (`ai/agent/chat`): `AiOrchestration.chatProxy()`.
- **Streaming** (`ai/stream`): `AiOrchestration.chatStreamWithSpringAi()` — SSE shape unchanged for the UI.
- **Tools**: Built-in wire tools are **`StudioAiOrchestrationTool`** classes registered in **`StudioAiToolRegistry`** (`CORE_TOOLS`); **`AiOrchestrationTools.build`** assembles the per-request catalog (core + optional **`mcp_*`**) and applies site **`tools.json`** filters. Tools attach when the session supports **native Studio tools** (**tools-loop** **`llm`** values such as **`openAI`** / **`xAI`** / **`deepSeek`** / **`llama`** / **`gemini`**, **Claude**, and **script** bundles that opt into the tools-loop or Anthropic tool transports). Legacy hosted-only **`llm`** strings are **rejected** by **`StudioAiLlmKind.normalize`**. See **[stream-endpoint-design.md](stream-endpoint-design.md)**, **[llm-configuration.md](../using-and-extending/llm-configuration.md)**, and **[chat-and-tools-runtime.md](chat-and-tools-runtime.md)**.
  - **MCP (Model Context Protocol) client:** **Opt-in** via `config/studio/scripts/aiassistant/config/tools.json`: set JSON boolean **`mcpEnabled`** to **`true`**, then declare **`mcpServers`** (Streamable HTTP endpoints; see **[studio-plugins-guide.md](../using-and-extending/studio-plugins-guide.md)** and **[chat-and-tools-runtime.md](chat-and-tools-runtime.md#mcp-client-tools-streamable-http)**). If **`mcpEnabled`** is omitted or not **`true`**, **`mcpServers` is ignored**. On each Studio chat request that builds the tool catalog with MCP on, the plugin **initializes** each server (`initialize` → `notifications/initialized` → `tools/list`), then registers one **native function tool per MCP tool** whose tools-loop wire name is **`mcp_<serverId>_<mcpToolName>`** (sanitized, max 64 characters). Tool calls use **`tools/call`** on the same per-request **MCP session** (including **`Mcp-Session-Id`** when the server returns one). URLs must pass the same **SSRF** gate as **`FetchHttpUrl`**. JVM knobs that can disable outbound HTTP (including MCP) are documented in **[studio-aiassistant-platform-settings.md](../using-and-extending/studio-aiassistant-platform-settings.md)**. MCP tools are **not** dropped when **`enabledBuiltInTools`** is a whitelist (they are extension catalog entries alongside **`InvokeSiteUserTool`**). Admins may hide individual MCP wire tools with **`disabledMcpTools`** or **`disabledBuiltInTools`** (name match).
  - **MCP `headers` and `${env:…}`:** Each header value string expands **`${env:VARIABLE_NAME}`** to **`System.getenv(VARIABLE_NAME)`** on the Studio JVM (unset or missing variable → empty string). Multiple placeholders per value are supported. Applied before outbound MCP requests (including Project Tools **List MCP tools** / save preview).
  - Backward-compatible: `<prompt>Text</prompt>`
  - Structured (recommended):
    - `<prompt><userText>...</userText><additionalContext>...</additionalContext><omitTools>true</omitTools></prompt>` — optional **`omitTools`** omits tools for that chip’s request only (XB, ICE, dialog, or form-engine).
  - Macros (expanded at send time):
    - `DATE_TODAY`, `TIME_NOW`, `CURRENT_PAGE`, `CURRENT_USERNAME`
    - `CURRENT_CONTENT_TYPE` — Replaced with the **form definition XML** of the content type of the item currently being previewed (loaded from Studio config). If there is no preview item or no type, a short message is used instead.
    - `CONTENT_TYPE:<contentTypeId>` — Replaced with the **form definition XML** for the given content type (e.g. `CONTENT_TYPE:page/home`, `CONTENT_TYPE:component/hero`). The form is loaded from `/config/studio/content-types/{contentTypeId}/form-definition.xml`. In the chat bubble, these macros are shown as short placeholders (e.g. `[Form: page/home]`) so the full XML does not appear in the log.
    - `CURRENT_CONTENT` — Replaced with the **raw content XML** of the item currently being previewed (loaded via Studio content API). If there is no preview item, a short message is used instead. **Exception:** In the **form-engine AI assistant**, this is replaced with **live XML from `serializeModelToXml`** when available, else **live `model` JSON**, so unsaved edits are included.
    - `CONTENT:<path>` — Replaced with the **raw content XML** of the content item at the given path (e.g. `CONTENT:/site/website/index.xml`, `CONTENT:/site/components/headers/main.xml`). The path must start with `/`. In the chat bubble, content macros are shown as short placeholders (e.g. `[Content: /site/website/index.xml]`). **Exception:** In the form-engine assistant, if `<path>` is the same item as the open form (`form.path`), substitution uses the **same live XML/JSON** rules as `CURRENT_CONTENT`; other paths still load XML from the repository.

### Build / Packaging

Defined in `sources/package.json`:

- `yarn start`: Vite dev server for local development (`sources/`)
- `yarn build`: TypeScript + Vite build
- `yarn package`: Rollup build used to produce the plugin bundle artifacts

### Current Known Gaps / Limitations (As-Is)

- **`agentId` in `agents.json`**: Stable id for stream/chat and form-engine visibility toggles.
- **Optional remote tools**: MCP, user tools, or site Groovy (see **Terminology**).
- **Studio AI assistant — autonomous**: Prototype only — in-memory state, no persistence across JVM restarts; not a replacement for scheduled jobs in production. See § Autonomous assistants above.

### AI Streaming Endpoint (Server-side)

A single **streaming** endpoint accepts `agentId`, `prompt`, optional `llm` / `llmModel` / `imageModel` / `openAiApiKey` (testing), and streams the response (SSE). See **[stream-endpoint-design.md](stream-endpoint-design.md)** and **[llm-configuration.md](../using-and-extending/llm-configuration.md)**.

### Related Docs

- **[llm-configuration.md](../using-and-extending/llm-configuration.md)** — Supported `<llm>` ids, required configuration, env + XML; autonomous widget allowed `llm` values.
- **[intent-recipe-routing.md](intent-recipe-routing.md)** — Pre-tools classifier, recipe prefetch, turn goal, telemetry, maintainer checklist.
- **[chat-and-tools-runtime.md](chat-and-tools-runtime.md)** — tools family, SSE, REST body fields, agent skills, MCP, key precedence, troubleshooting.
- **[stream-endpoint-design.md](stream-endpoint-design.md)** — SSE contract and stream `/ai/stream` behavior (including **`intent-recipe-routing`** metadata).
- **[studio-plugins-guide.md](../using-and-extending/studio-plugins-guide.md)** — Build and install guide for Crafter Studio plugins (plugin ID, paths, ui.xml, auth, Rollup, checklist). Use when creating or debugging plugins.
- **Crafter Studio UI (reference):** [craftercms/studio-ui @ `support/4.x`](https://github.com/craftercms/studio-ui/tree/support/4.x) — Use this branch to see how Studio implements widgets, hooks (e.g. `useActiveSiteId`, `useCurrentPreviewItem`, `useActiveUser`), and config; build features and code consistently with Studio.

### Appendix: Key Files

- `sources/src/AiAssistantPopover.tsx`: Popover shell + **`AiAssistantChat`**
- `sources/src/AiAssistantHelper.tsx`: Helper widget for Studio UI
- `sources/src/AiAssistantAutonomousAssistants.tsx`: Studio AI assistant — autonomous (Tools Panel widget)
- `sources/src/autonomousAssistantsConfig.ts` / `sources/src/autonomousApi.ts`: autonomous catalog types; REST client for sync/status/control
- `sources/src/consts.ts`: widget ids

