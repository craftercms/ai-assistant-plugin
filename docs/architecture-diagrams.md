# Architecture & Diagrams

Visual reference for the **Studio AI Assistant** plugin: system context, configuration design, admin workflows, author surfaces, and developer build/runtime paths.

**Related deep dives:** [Intent recipe routing](internals/intent-recipe-routing.md) (pre-tools prelude) · [Stream endpoint](internals/stream-endpoint-design.md) · [Chat & tools runtime](internals/chat-and-tools-runtime.md) · [Specification](internals/spec.md) · [Configuration guide](using-and-extending/configuration-guide.md)

---

<a id="tool-terminology"></a>

## Tool terminology

| Term | Meaning |
|------|---------|
| **Tools** | Anything the model can invoke in the **tools loop** (function calling), plus site configuration that registers them. |
| **Built-in tools** | Plugin-shipped wires (`GetContent`, `WriteContent`, `publish_content`, …). Site **`disabledBuiltInTools`** / **`enabledBuiltInTools`** apply to this set only. |
| **Scripted tools** | Site Groovy under **`user-tools/`**, invoked via **`InvokeSiteUserTool`**. |
| **MCP tools** | Remote tools registered as **`mcp_<server>_<name>`** when **`mcpEnabled`** is true. |
| **Recipe skills** | **Intent recipes** — orchestration that runs **before** the tools loop (match, prefetch, prelude, optional **`toolsLoopDisable`**). They route and prepare tool use; they are not another wire name beside `GetContent`. |

---

## Diagram index by audience

| Audience | Section | What you get |
|----------|---------|----------------|
| **Administrators** | [Configuration model](#configuration-model-design) · [Setup workflow](#administrator-setup-workflow) · [Project Tools map](#project-tools-configuration-map) | What to configure, where files live, recommended order |
| **Authors (users)** | [Author surfaces](#author-experience-user) · [Interactive chat journey](#interactive-chat-journey-user) | Where chat appears in Studio and what happens on send |
| **Integrators / extension developers** | [Logical architecture](#logical-architecture-design) · [Site extension layout](#site-extension-layout-developer) · [Build pipeline](#build-and-deploy-pipeline-developer) | Scripts, tools, MCP, Rollup outputs |
| **Plugin maintainers** | [System context](#system-context-architecture) · [Stream request path](#interactive-chat-request-path-developer) · [Component registration](#studio-ui-component-registration-developer) | End-to-end server and UI wiring |

---

<a id="system-context-architecture"></a>

## System context (architecture)

High-level actors and dependencies. The plugin runs **inside Crafter Studio**; LLM calls go to **your** configured providers, not a bundled hosted chat product.

```mermaid
flowchart LR
  subgraph people["People"]
    Author["Content author"]
    Admin["Studio administrator"]
  end

  subgraph studio["Crafter Studio"]
    UI["Studio UI\n(React + Form Engine)"]
    Plugin["AI Assistant plugin\n(static JS + Groovy)"]
    UI --> Plugin
  end

  subgraph site["Site sandbox (Git)"]
    Agents["agents.json"]
    Secrets["secrets.json"]
    Scripts["scripts/aiassistant/…"]
    Content["/site/… content & types"]
  end

  subgraph external["External"]
    LLM["LLM providers\n(OpenAI, Claude, script host, …)"]
    MCP["Optional MCP servers"]
  end

  Author --> UI
  Admin --> UI
  Admin --> site
  Plugin --> Agents
  Plugin --> Secrets
  Plugin --> Scripts
  Plugin --> Content
  Plugin --> LLM
  Plugin -.-> MCP
```

---

<a id="logical-architecture-design"></a>

## Logical architecture (design)

Layers inside Studio for a single **interactive chat** turn. Autonomous runs reuse the orchestration and tool catalog on a **scheduler** path (see [spec — Autonomous](internals/spec.md#autonomous-assistants-widget-tools-panel)).

```mermaid
flowchart TB
  subgraph presentation["Presentation (browser)"]
    Helper["Helper widget"]
    FormCtl["Form control\n(ai-assistant)"]
    Chat["AiAssistantChat\n(SSE client)"]
    TinyMCE["TinyMCE plugin\n(optional)"]
    Helper --> Chat
    FormCtl --> Chat
    TinyMCE --> Chat
  end

  subgraph studioIntegration["Studio integration"]
    UiXml["ui.xml widget placement"]
    AgentsFile["agents.json catalog"]
    StudioUi["studio-ui.json flags"]
  end

  subgraph rest["Plugin REST (Groovy)"]
    Stream["stream.post.groovy"]
    ChatRest["chat.post.groovy"]
    Merge["AiAssistantCentralAgentsMerge"]
    Stream --> Merge
    ChatRest --> Merge
  end

  subgraph orchestration["Orchestration (JVM)"]
    Recipes["Intent recipe prelude\n(optional)"]
    Orch["AiOrchestration"]
    Tools["StudioAiToolRegistry\nAiOrchestrationTools.build\n+ StudioToolOperations"]
    Stream --> Recipes
    Recipes --> Orch
    Orch --> Tools
  end

  subgraph cms["Crafter CMS (Studio APIs)"]
    ReadWrite["Content read/write\nsearch, deploy, …"]
  end

  Chat -->|"POST SSE"| Stream
  presentation --> studioIntegration
  Merge --> AgentsFile
  Tools --> ReadWrite
  Orch --> LLM["LLM provider API"]
  Tools -.-> MCP["MCP tools/call"]
```

---

<a id="configuration-model-design"></a>

## Configuration model (design)

Site-scoped configuration the plugin reads at runtime. **Project Tools → AI Assistant** edits most JSON under `config/studio/`.

```mermaid
flowchart TB
  subgraph adminUi["Project Tools → AI Assistant"]
    TabUI["UI tab"]
    TabAgents["Agents tab"]
    TabRecipes["Recipes tab"]
    TabInt["Integrations\n(LLMs · Tools · MCP · Image gen)"]
    TabSecrets["Secrets tab"]
    TabPrompts["Prompts & Context"]
  end

  subgraph studioConfig["config/studio/"]
    UiXml["ui.xml\n(Helper · Autonomous · TinyMCE)"]
    Agents["ai-assistant/agents.json\nchat + autonomous rows"]
    Secrets["ai-assistant/secrets.json"]
    StudioUi["ai-assistant/studio-ui.json"]
  end

  subgraph siteScripts["config/studio/scripts/aiassistant/"]
    ToolsJson["config/tools.json"]
    RecipesJson["intent-recipes.json"]
    PromptsMd["prompts/*.md"]
    UserTools["user-tools/registry.json\n+ *.groovy"]
    ScriptLlm["llm/{id}/runtime.groovy"]
    ImageGen["imagegen/{id}/generate.groovy"]
  end

  TabUI --> StudioUi
  TabUI --> UiXml
  TabAgents --> Agents
  TabSecrets --> Secrets
  TabRecipes --> RecipesJson
  TabRecipes --> ToolsJson
  TabInt --> ToolsJson
  TabInt --> ScriptLlm
  TabInt --> ImageGen
  TabInt --> UserTools
  TabPrompts --> PromptsMd
```

| Artifact | Purpose |
|----------|---------|
| **`agents.json`** | Chat agents (`mode: chat`) and autonomous agents (`mode: autonomous`); **`agentId`**, **`llm`**, models, tools policy, prompts |
| **`secrets.json`** | Site credential slots (`${env:…}`, `${enc:…}`); resolve at runtime only stored rows — **`${secret:…}`** in MCP/agents; **`serpapi_api_key`** for **SerpApiWebSearch** |
| **`ui.xml`** | Registers **Helper**, optional **AutonomousAssistants**, **TinyMCE** external plugin URL |
| **`studio-ui.json`** | Toolbar/sidebar visibility, XB image scope, bulk form-control helpers |
| **`tools.json`** | Built-in tool allow/deny, **`builtInToolSettings`** (e.g. **SerpApiWebSearch** defaults), MCP **`mcpEnabled`** + **`mcpServers`**, intent recipe routing flags |
| **`intent-recipes.json`** | Pre-tools workflow recipes (phases, **`toolsLoopForceTool`**, **`{{studio.today-7D}}`** hints; site override of plugin defaults) |
| **Site scripts** | User tools, script LLMs, image generators, prompt markdown overrides |

---

<a id="administrator-setup-workflow"></a>

## Administrator setup workflow

Recommended order for a new site (matches [configuration guide §1–§7](using-and-extending/configuration-guide.md#cg-basic)).

```mermaid
flowchart TD
  Start([Install plugin on site]) --> PT[Open Project Tools → AI Assistant]
  PT --> Agents[Configure Agents tab → save agents.json]
  Agents --> Secrets[Secrets tab → API keys]
  Secrets --> UiXml[ui.xml: add Helper plugin widget]
  UiXml --> Form{Form assistant needed?}
  Form -->|yes| FormDef[Add ai-assistant field to content types]
  Form -->|no| Flags
  FormDef --> Flags[Optional: UI tab → studio-ui.json flags]
  Flags --> Adv{Advanced integrations?}
  Adv -->|yes| AdvScripts[Integrations + Recipes + Prompts tabs\n→ site scripts under aiassistant/]
  Adv -->|no| Tiny
  AdvScripts --> Tiny{TinyMCE needed?}
  Tiny -->|yes| TinyCfg[ui.xml TinyMCE toolbar + external_plugins]
  Tiny -->|no| Check[Run checklist §7]
  TinyCfg --> Check
  Check --> Done([Authors can use assistant])
```

---

<a id="project-tools-configuration-map"></a>

## Project Tools configuration map

How admin UI tabs map to repository paths (for troubleshooting “I saved but Studio still …”).

```mermaid
flowchart LR
  subgraph tabs["Configuration modal tabs"]
    T1[UI]
    T2[Agents]
    T3[Recipes]
    T4[Integrations]
    T5[Secrets]
    T6[Prompts]
  end

  subgraph paths["Saved under config/studio/"]
    P1["ai-assistant/studio-ui.json\n+ bulk form-definition edits"]
    P2["ai-assistant/agents.json"]
    P3["scripts/aiassistant/intent-recipes.json\n+ tools.json flags"]
    P4["scripts/aiassistant/config/tools.json\nllm/ · imagegen/ · user-tools/"]
    P5["ai-assistant/secrets.json"]
    P6["scripts/aiassistant/prompts/*.md"]
  end

  T1 --> P1
  T2 --> P2
  T3 --> P3
  T4 --> P4
  T5 --> P5
  T6 --> P6
```

---

<a id="author-experience-user"></a>

## Author experience (user)

Where authors open the assistant (admin enables each surface in **`ui.xml`** / form definitions).

```mermaid
flowchart TB
  Author([Author in Studio])

  subgraph surfaces["Author-facing surfaces"]
    Preview["Experience Builder / Preview\nHelper → ICE panel or popup"]
    Form["Content type form\nAI Assistant accordion per agent"]
    RTE["Rich text field\nTinyMCE aiAssistantOpen / shortcuts"]
    Auto["Tools Panel\nAutonomous status\n(experimental)"]
  end

  subgraph outcome["Typical outcome"]
    ChatUI["Chat panel\nstreaming reply"]
    FormUpdate["Optional: form field updates\nfrom fenced JSON block"]
    RTEInsert["Optional: insert text into RTE"]
  end

  Author --> Preview
  Author --> Form
  Author --> RTE
  Author --> Auto
  Preview --> ChatUI
  Form --> ChatUI
  Form --> FormUpdate
  RTE --> ChatUI
  RTE --> RTEInsert
```

---

<a id="interactive-chat-journey-user"></a>

## Interactive chat journey (user)

What authors perceive on one message (tools may run server-side without exposing tool JSON in the UI).

```mermaid
sequenceDiagram
  participant A as Author
  participant UI as Studio UI chat
  participant S as Plugin stream endpoint
  participant L as LLM provider

  A->>UI: Type message / pick quick prompt
  UI->>S: POST /ai/stream (agentId, prompt, context)
  Note over S: Merge agents.json · optional intent recipes · run tools
  S-->>UI: SSE text chunks (+ tool-progress lines)
  L-->>S: Model tokens / tool calls
  S-->>UI: metadata.completed
  UI-->>A: Assistant reply in panel
  opt Form engine surface
    UI-->>A: Apply aiassistantFormFieldUpdates to form
  end
```

---

<a id="interactive-chat-request-path-developer"></a>

## Interactive chat request path (developer)

Server path from REST script through orchestration (simplified; see [intent-recipe-routing.md](internals/intent-recipe-routing.md) for prelude branches).

```mermaid
flowchart TD
  POST["POST stream.post.groovy"] --> Body["Parse JSON body\nagentId · prompt · llm · …"]
  Body --> Merge["AiAssistantCentralAgentsMerge\nfill missing llm/model from agents.json"]
  Merge --> Norm["StudioAiLlmKind.normalize"]
  Norm --> IR{intentRecipeRouting.enabled?}
  IR -->|yes| Prelude["AuthoringIntentRecipeEngine\nmatch · prefetch · prelude"]
  IR -->|no| Orch
  Prelude --> Orch["AiOrchestration.chatStreamWithSpringAi"]
  Orch --> Branch{llm transport}
  Branch -->|openAI xAI deepSeek llama gemini| Loop["Tools-loop RestClient\n+ StudioAiToolRegistry"]
  Branch -->|claude| Anthropic["Spring AI Anthropic tool loop"]
  Branch -->|script:id| Script["Site scriptLlm runtime.groovy"]
  Loop --> Tools["StudioToolOperations\ncontent · search · deploy · …"]
  Loop --> SSE["SSE frames to client"]
  Anthropic --> SSE
  Script --> SSE
```

---

<a id="build-and-deploy-pipeline-developer"></a>

## Build and deploy pipeline (developer)

Canonical sources vs generated assets (required invariant from [studio-plugins-guide](using-and-extending/studio-plugins-guide.md)).

```mermaid
flowchart LR
  subgraph repo["Plugin repository"]
    Sources["sources/\nTS · React · control/main.js"]
    Authoring["authoring/\nstatic-assets · scripts/classes"]
    Yaml["craftercms-plugin.yaml"]
  end

  subgraph build["yarn package (sources/)"]
    Rollup["Rollup → index.js\n+ tinymce bundle"]
    Copy["Copy control/ai-assistant/main.js"]
    Verify["verify-aiassistant-form-pipeline.mjs"]
  end

  subgraph install["Install on site"]
    CopyPlugin["Marketplace copy / crafter-cli copy-plugin"]
    SitePath["site/config/studio/static-assets/plugins/…"]
    SiteClasses["site/config/studio/scripts/classes/…\n(if not copied — manual)"]
  end

  Sources --> build
  build --> Authoring
  Authoring --> CopyPlugin
  CopyPlugin --> SitePath
  Authoring --> SiteClasses
  Yaml --> CopyPlugin
```

| Edit here | Do not hand-edit |
|-----------|------------------|
| `sources/src/**`, `sources/index.tsx` | `authoring/.../components/index.js` (generated) |
| `sources/control/ai-assistant/main.js` | `authoring/.../control/ai-assistant/main.js` (copied on package) |
| `authoring/scripts/classes/**` | — (ship with plugin; may need manual copy to site) |

---

<a id="studio-ui-component-registration-developer"></a>

## Studio UI component registration (developer)

How widgets and the form control reach Studio.

```mermaid
flowchart TB
  subgraph descriptor["craftercms-plugin.yaml"]
    PluginId["plugin.id = org.craftercms.aiassistant.studio"]
  end

  subgraph bundle["components/index.js (Rollup)"]
    Index["sources/index.tsx\nPluginDescriptor.widgets"]
    W1["Helper"]
    W2["FormControl"]
    W3["ProjectToolsConfiguration"]
    W4["AutonomousAssistants · …"]
  end

  subgraph formEngine["Form Engine (main.js)"]
    FC["CStudioForms.Controls.AiAssistant\ngetName() → ai-assistant"]
    Import["importPlugin → FormControl widget"]
  end

  subgraph siteUi["Site ui.xml"]
    HelperXml["widget Helper + plugin element"]
    AutoXml["widget AutonomousAssistants"]
  end

  PluginId --> bundle
  Index --> W1 & W2 & W3 & W4
  HelperXml --> W1
  FC --> Import
  Import --> W2
  AutoXml --> W4
```

---

<a id="site-extension-layout-developer"></a>

## Site extension layout (developer)

Optional site-authored extensions under `config/studio/scripts/aiassistant/`.

```mermaid
flowchart TB
  Root["config/studio/scripts/aiassistant/"]
  Root --> Config["config/tools.json"]
  Root --> Recipes["intent-recipes.json"]
  Root --> Prompts["prompts/*.md"]
  Root --> UT["user-tools/\nregistry.json + *.groovy"]
  Root --> LLM["llm/{id}/runtime.groovy"]
  Root --> IMG["imagegen/{id}/generate.groovy"]

  Config --> Builtin["disabledBuiltInTools / enabledBuiltInTools"]
  Config --> MCP["mcpEnabled · mcpServers[]"]
  Config --> IRR["intentRecipeRouting{…}"]

  UT --> Invoke["InvokeSiteUserTool at runtime"]
  LLM --> ScriptLlm["script:{id} agent llm"]
  IMG --> GenImg["GenerateImage via imageGenerator"]
```

---

<a id="autonomous-scheduler-architecture"></a>

## Autonomous scheduler (architecture)

Experimental path: same tool catalog as chat, different trigger and persistence.

```mermaid
flowchart LR
  Widget["AutonomousAssistants widget"] --> Sync["POST …/autonomous/assistants/sync"]
  Sync --> Registry["In-memory agent registry\n+ supervisor threads"]
  Registry --> Tick["~10s supervisor tick"]
  Tick --> Worker["AutonomousAssistantWorker"]
  Worker --> Orch["AiOrchestration\n(tools-loop + JSON contract)"]
  Widget --> Status["GET …/status"]
  Widget --> Control["POST …/control\nstart · stop · …"]
```

Details: [autonomous-assistants-widget.md](using-and-extending/autonomous-assistants-widget.md) · [spec — Autonomous](internals/spec.md#autonomous-assistants-widget-tools-panel).

---

<a id="llm-transport-design"></a>

## LLM transport selection (design)

How **`llm`** on an agent row selects server code paths (see [llm-configuration.md](using-and-extending/llm-configuration.md)).

```mermaid
flowchart TD
  Llm["agents.json llm field"] --> Norm["StudioAiLlmKind.normalize"]
  Norm --> O["openAI"]
  Norm --> X["xAI · deepSeek · llama · gemini"]
  Norm --> C["claude"]
  Norm --> S["script:id → scriptLlm:id"]
  Norm --> Bad["unknown / hosted-only → HTTP 400"]

  O --> TL["Tools-loop RestClient"]
  X --> TL
  C --> AN["Anthropic ChatClient tools"]
  S --> SG["Site Groovy StudioAiLlmRuntime"]
  TL --> CMS["Built-in tools + MCP"]
  AN --> CMS
```

---

## See also

| Topic | Document |
|-------|----------|
| Intent recipe prelude (detailed flowchart) | [intent-recipe-routing.md](internals/intent-recipe-routing.md) |
| SSE contract & stream URL | [stream-endpoint-design.md](internals/stream-endpoint-design.md) |
| REST body fields, MCP, tools | [chat-and-tools-runtime.md](internals/chat-and-tools-runtime.md) |
| Product surfaces & contracts | [spec.md](internals/spec.md) |
| Admin procedures & screenshots | [configuration-guide.md](using-and-extending/configuration-guide.md) |
| Rollup, paths, plugin id | [studio-plugins-guide.md](using-and-extending/studio-plugins-guide.md) |
