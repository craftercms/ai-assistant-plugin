# Supported LLMs (`<llm>`) — IDs, Configuration, and Behavior

Defines **`<llm>`** identifiers, env/XML keys, merge rules, and the provider capability matrix. Keep this file and **[`spec.md`](../internals/spec.md)** aligned when those contracts change.

**For site admins:** [configuration-guide.md](configuration-guide.md) (**Secrets** tab → **`secrets.json`**; per-agent **`llmSecretKey`** in **Agents**)  
**For tools, SSE, REST bodies, agent skills, MCP, and troubleshooting:** [chat-and-tools-runtime.md](../internals/chat-and-tools-runtime.md)  
**For script LLMs and `user-tools/`:** [studio-plugins-guide.md](studio-plugins-guide.md) · **Script LLM — full session bundle (BYO backend):** [script-llm-bring-your-own-backend.md](script-llm-bring-your-own-backend.md)  
**For pluggable image backends (`imageGenerator`, `imagegen/` scripts, site overrides):** [image-generation.md](image-generation.md) · **Integrators:** [scripted-tools-and-imagegen.md](scripted-tools-and-imagegen.md) (Groovy closure, `context` map, return shape)  
**For `ui.xml` contracts, macros, and REST:** [spec.md](../internals/spec.md) · **Doc index:** [README.md](../README.md)

---

## Summary Table

Rows list **supported** backends. **Hosted-only** SaaS adapters (**`aiassistant`**, **`hostedchat`**, …) are **not** supported — **`StudioAiLlmKind.normalize`** throws (**HTTP 400**). **`ai-assistant`** is **not** a valid `<llm>` value either (that string names the Studio plugin / form control path, not a model provider); use **`openAI`**, **`claude`**, etc.

| `<llm>` wire value | Aliases (normalized) | API key (production) | Default chat model (when agent omits `llmModel`) | Transport |
|--------------------|----------------------|----------------------|--------------------------------------------------|-----------|
| **`openAI`** | `openai`, `open-ai` | **`secrets.json`** → **`openai_api_key`** → **`crafter_openai_api_key`** on Studio host | **`crafter.openai.model`** in platform-settings (required for OpenAI row if unset) | Tools-loop **`/v1/chat/completions`** |
| **`xAI`** | `x-ai`, `grok` | **`xai_api_key`** → **`crafter_xai_api_key`** | **`grok-4.3`** (`crafter.xai.model`) | Tools-loop (same stack as **`openAI`**) |
| **`deepSeek`** | `deep-seek` | **`deepseek_api_key`** → **`crafter_deepseek_api_key`** | **`deepseek-chat`** | Tools-loop |
| **`llama`** | `ollama`, `meta-llama`, `meta_llama` | **`llama_api_key`** → **`crafter_llama_api_key`** (Ollama may use a placeholder) | **`llama3.2`** | Tools-loop |
| **`genesis`** / **`gemini`** | `gemini`, `google`, … | **`gemini_api_key`** / **`google_api_key`** → **`crafter_gemini_api_key`** / **`crafter_google_api_key`** | **`gemini-2.0-flash`** | Tools-loop |
| **`claude`** | `anthropic` | **`anthropic_api_key`** → **`crafter_anthropic_api_key`** | **`claude-sonnet-4-20250514`** (`crafter.anthropic.model`) | Spring AI **Anthropic** (main chat + tools). Auxiliary prose completions use Anthropic **`/v1/messages`** — see below. |
| **`script:{id}`** | — | Site Groovy runtime + optional bundle keys | Set in script / agent | Script chooses tools-loop vs Anthropic-style transport |

**Capabilities (by row):** **`openAI`** — built-in tools, **GenerateImage** (when configured), agent **skills** → **QueryExpertGuidance**. **Tools-loop family** (`xAI`, `deepSeek`, `llama`, `gemini`) — same tool surface as OpenAI. **`claude`** — built-in tools via Anthropic; **GenerateImage** / expert embeddings may still use **OpenAI** key material separately. **`script:{id}`** — configurable.

### Host environment (`crafter_*`)

On **Crafter Studio 4.x**, Groovy sandbox code reads provider secrets from host env vars with the **`crafter_`** prefix (see **`StudioAiCrafterEnv`**). Project Tools → **Secrets** seeds **`secrets.json`** with **`${env:crafter_<provider>_api_key}`** — that is the **recommended** production path.

| Secret key (`secrets.json`) | Host env var (Studio JVM) |
|-----------------------------|---------------------------|
| `openai_api_key` | **`crafter_openai_api_key`** |
| `anthropic_api_key` | **`crafter_anthropic_api_key`** |
| `xai_api_key` | **`crafter_xai_api_key`** |
| `deepseek_api_key` | **`crafter_deepseek_api_key`** |
| `llama_api_key` | **`crafter_llama_api_key`** |
| `gemini_api_key` | **`crafter_gemini_api_key`** |

Optional base URLs (tools-loop hosts): **`crafter_xai_base_url`**, **`crafter_deepseek_base_url`**, **`crafter_llama_base_url`**, **`crafter_gemini_base_url`**, or matching **`crafter.*.llmBaseUrl`** keys in **[platform-settings.json](studio-aiassistant-platform-settings.md)**.

Legacy unprefixed names (**`OPENAI_API_KEY`**, **`ANTHROPIC_API_KEY`**, **`XAI_API_KEY`**, …) may appear in older docs or external guides; the plugin’s sandbox-safe resolution path is **`secrets.json`** → **`crafter_*`** → **`platform-settings.json`** → testing-only agent **`llmApiKey`**.


## Configuration Examples (`agents.json`)

Configure agents in **Project Tools → AI Assistant → Agents** (file: **`config/studio/ai-assistant/agents.json`**). Register the Helper in **`ui.xml`** for widget placement only (see [configuration-guide.md](configuration-guide.md)).

**Always set `llm` on each row** from the summary table. Configure provider keys on the Studio host per vendor column.

### Recommended: OpenAI with tools (+ Optional Image)

```json
{
  "mode": "chat",
  "agentId": "00000000-0000-4000-8000-000000000002",
  "label": "OpenAI authoring",
  "llm": "openAI",
  "llmModel": "gpt-4.1-mini",
  "imageModel": "gpt-image-1-mini",
  "enableTools": true
}
```

- Set **`crafter_openai_api_key`** on the Studio host (via **`secrets.json`** `${env:…}` is recommended). Do **not** commit keys to Git.
- **`imageModel`** is required for **GenerateImage** on the default wire; there is no silent default in site config.

### Other providers

Use the same shape with **`llm`**: **`claude`**, **`xAI`**, **`deepSeek`**, **`llama`**, **`gemini`**, or **`script:mybackend`**. Set the matching **`crafter_*`** host env vars (see table above) or **`secrets.json`** rows. For **`script:{id}`**, implement **`config/studio/scripts/aiassistant/llm/{id}/runtime.groovy`** per **[studio-plugins-guide.md](studio-plugins-guide.md)**.

Example Claude agent:

```json
{
  "mode": "chat",
  "agentId": "00000000-0000-4000-8000-000000000003",
  "label": "Claude authoring",
  "llm": "claude",
  "llmModel": "claude-sonnet-4-20250514",
  "enableTools": true
}
```

---

## Omitted `llm` And POST Body

The React client may omit **`llm`** on the stream/chat JSON when the selected agent row has no **`llm`**. The server **does not** infer a default adapter: after **agents.json merge** (when POST **`siteId`** (working CMS site) + **`agentId`** are present), **`StudioAiLlmKind.normalize`** requires a **non-blank**, **recognized** `llm` string. Missing or invalid values produce **HTTP 400** on **`/ai/stream`** and **`/ai/agent/chat`**. The URL query **`siteId`** is the Studio session site only; merge reads **`agents.json`** from the site sandbox for the working id when present.

When POST **`siteId`** + **`agentId`** match a catalog row, the server may **copy `llm`**, **`llmModel`**, **`imageModel`**, and **`imageGenerator`** into the POST body before normalize.

**Always set `llm` explicitly** on each chat row in **`agents.json`**.

---

## Per-provider Notes

### Tools-loop Chat Family (`openAI`, `xAI`, `deepSeek`, `llama`, `gemini` / `genesis`)

- **Transport:** Spring AI **`OpenAiChatModel`** + **RestClient** **`/v1/chat/completions`** native tool loop (`AiOrchestrationTools`).
- **Image generation:** **`<imageModel>`** for the default **GenerateImage** wire (e.g. **`gpt-image-1`**). **`<imageGenerator>`** selects **`script:{id}`**, **`none`**, or default wire.

### `claude`

- **Main chat transport:** Spring AI **`AnthropicChatModel`** — interactive **`/ai/stream`** turns and native tool execution go to **Anthropic**, not the OpenAI RestClient tools-loop.
- **Intent recipe routing prelude** (pre-tools classifier in **[intent-recipe-routing.md](../internals/intent-recipe-routing.md)**) runs only on **tools-loop** providers; it is **skipped** when **`llm`** is **`claude`**.
- **Auxiliary prose completions** (recipe confirmation LLM refine, translate inner loops, **`GenerateTextNoTools`**, etc.) use **`StudioAiAnthropicSimpleCompletion`** → Anthropic **`POST /v1/messages`**, not **`/v1/chat/completions`**, when the session bundle is Claude.
- **Still OpenAI-backed (separate keys):** default **GenerateImage** wire; expert-skill **embeddings** / RAG typically use **`crafter_openai_api_key`**. Configure those independently if authors need images or **QueryExpertGuidance** on a Claude agent.
- **Platform tuning:** **`crafter.anthropic.maxTokens`** (default **8192**), **`crafter.anthropic.model`** — see **[studio-aiassistant-platform-settings.md](studio-aiassistant-platform-settings.md)**.
- **Autonomous assistants:** **`claude`** is **not** supported for **`mode: autonomous`** rows.

### `script:{id}` (Site Groovy LLM)

- **Wire:** **`<llm>script:mybackend</llm>`** → **`scriptLlm:mybackend`**.
- **Id pattern:** `{id}` = `a-z`, `0-9`, `_`, `-`, max **64** chars.
- Full contract: [studio-plugins-guide.md](studio-plugins-guide.md) and **`docs/examples/aiassistant-llm/demo/runtime.groovy`**. **Full vendor replacement (Groovy class, no built-in runtime delegation):** [script-llm-bring-your-own-backend.md](script-llm-bring-your-own-backend.md). **Groq (tools-loop):** **`docs/examples/aiassistant-llm/groq/runtime.groovy`**.

---

## Agent catalog fields (cross-LLM)

| Field | Applies to | Purpose |
|-------|------------|---------|
| **`llm`** | All | Selects backend; see summary table. Unsupported hosted ids (**`aiassistant`**, **`hostedchat`**, …) **fail normalize**. |
| **`llmModel`** | Tool-capable rows | Provider chat model id when the provider uses it. |
| **`imageGenerator`** | **GenerateImage** | Blank = default wire when configured; **`none`**/**`off`**/**`disabled`**; **`script:{id}`** for site Groovy under **`/scripts/aiassistant/imagegen/{id}/`**. |
| **`imageModel`** | **GenerateImage** (wire path) | Required when the model should call **GenerateImage** on the default wire. |
| **`agentId`** | Chat rows | System-generated UUID (Project Tools). Sent on stream/chat; used for catalog merge and form toggles. |
| **`llmSecretKey`** | Production | Optional; **`secrets.json`** entry key (custom secret or built-in provider row). Set in Project Tools → Agents. |
| **`llmApiKey`** | Testing | Per-agent key when no **env** / secrets row; discouraged in production. |

**Credential order (LLM):** resolve the agent’s **`llmSecretKey`** or provider default row from **`secrets.json`** first (`${env:…}` / `${enc:…}` expansion). Provider stacks may then apply documented **host env** or JVM property fallbacks when the resolved secret is still empty — see **[configuration-guide §4](configuration-guide.md#cg-4)**. This is **not** the same as inventing a default row at runtime when the file or key is missing. Built-in integration tools (e.g. **`SerpApiWebSearch`**) use **secrets only** — no second env bypass.
| **`enableTools`** | Tool-capable | When **`false`**, tools are off for that agent (subject to per-request **`omitTools`**). |
| **`skills`** | Tools-loop + Claude (tools on) | Per-agent markdown URL skills (enabled rows only) → **QueryExpertGuidance** during the tools loop (not plugin system RAG). Limits: **`tools.json`** → **`agentSkillsRag`**. See **[configuration-guide §9.2.1](configuration-guide.md#cg-9-2-1)** (plugin RAG vs agent skills). |

---

## REST / Stream Body Keys (Reference)

The client sends catalog fields on **`POST …/ai/stream`** and **`…/ai/agent/chat`**. Common keys: **`llm`**, **`llmModel`**, **`imageModel`**, **`imageGenerator`**, **`llmSecretKey`**, **`llmApiKey`**, **`agentId`**, enabled **`skills`**, preview **`contentPath`** / **`contentTypeId`**, **`omitTools`**, **`enableTools`**. Full list: [chat-and-tools-runtime.md § REST body](../internals/chat-and-tools-runtime.md#rest-body-advanced).

When **`siteId`** + **`agentId`** are present, the server may **merge** missing **`llm`**, **`llmModel`**, **`imageModel`**, and **`imageGenerator`** from **`config/studio/ai-assistant/agents.json`** before orchestration.

---

## Autonomous rows

**`mode: autonomous`** rows use **`openAI`**, **`xAI`**, **`deepSeek`**, **`llama`**, **`genesis`** / **`gemini`** for steps (**tools-loop** stack). **`claude`** is **not** supported for autonomous runs. Details: [chat-and-tools-runtime.md § Autonomous](../internals/chat-and-tools-runtime.md#autonomous-assistants) and [spec.md](../internals/spec.md).
