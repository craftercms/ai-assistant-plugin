# Supported LLMs (`<llm>`) — IDs, Configuration, and Behavior

Defines **`<llm>`** identifiers, env/XML keys, merge rules, and the provider capability matrix. Keep this file and **[`spec.md`](../internals/spec.md)** aligned when those contracts change.

**For site admins:** [configuration-guide.md](configuration-guide.md) (**Secrets** tab → **`secrets.json`**; per-agent **`llmSecretKey`** in **Agents**)  
**For CMS tools, SSE, REST bodies, expert skills, MCP, and troubleshooting:** [chat-and-tools-runtime.md](../internals/chat-and-tools-runtime.md)  
**For script LLMs and `user-tools/`:** [studio-plugins-guide.md](studio-plugins-guide.md) · **Script LLM — full session bundle (BYO backend):** [script-llm-bring-your-own-backend.md](script-llm-bring-your-own-backend.md)  
**For pluggable image backends (`imageGenerator`, `imagegen/` scripts, site overrides):** [image-generation.md](image-generation.md) · **Integrators:** [scripted-tools-and-imagegen.md](scripted-tools-and-imagegen.md) (Groovy closure, `context` map, return shape)  
**For `ui.xml` contracts, macros, and REST:** [spec.md](../internals/spec.md) · **Doc index:** [README.md](../README.md)

---

## Summary Table

Rows list **supported** backends. **Hosted-only** SaaS adapters (**`aiassistant`**, **`hostedchat`**, …) are **not** supported — **`StudioAiLlmKind.normalize`** throws (**HTTP 400**). **`ai-assistant`** is **not** a valid `<llm>` value either (that string names the Studio plugin / form control path, not a model provider); use **`openAI`**, **`claude`**, etc.

| `<llm>` wire value | Aliases (normalized) | Required configuration | Optional `agents.json` / env | What you get |
|--------------------|----------------------|-------------------------|-------------------------|--------------|
| **`openAI`** | `openai`, `open-ai` | **API key:** host env **`OPENAI_API_KEY`** (recommended). | **`llmModel`**, **`imageModel`**, **`llmApiKey`** (testing only). | **CMS tools**, **GenerateImage** (when `imageModel` + key allow), **expertSkills** → **QueryExpertGuidance**. |
| **`xAI`** | `x-ai`, `grok` | **`XAI_API_KEY`** | **`XAI_OPENAI_BASE_URL`** (tools-loop chat base URL). **`<llmModel>`**. Same stack as **`openAI`**. | Same tool surface as **OpenAI** row. |
| **`deepSeek`** | `deep-seek` | **`DEEPSEEK_API_KEY`** | **`DEEPSEEK_OPENAI_BASE_URL`** (optional). **`<llmModel>`**. | Same tool surface as **OpenAI** row. |
| **`llama`** | `ollama`, `meta-llama`, `meta_llama` | Often **`LLAMA_API_KEY`** (Ollama may accept a placeholder). | **`LLAMA_OPENAI_BASE_URL`** or **`OLLAMA_OPENAI_BASE_URL`**. **`<llmModel>`**. | Same tool surface as **OpenAI** row. |
| **`genesis`** / **`gemini`** | `gemini`, `google`, `google-genai`, `google_genai` | **`GEMINI_API_KEY`** or **`GOOGLE_API_KEY`** | **`GEMINI_OPENAI_BASE_URL`** / **`GOOGLE_GENAI_OPENAI_BASE_URL`**. **`<llmModel>`**. | Same tool surface as **OpenAI** row. |
| **`claude`** | `anthropic` | **`ANTHROPIC_API_KEY`** | **`<llmModel>`**. **`<openAiApiKey>`** — *testing only* for Anthropic when no **`ANTHROPIC_API_KEY`** (see runtime doc). | **CMS tools** via Spring AI **Anthropic** (not the OpenAI RestClient loop). **GenerateImage** / embeddings that still use OpenAI key material are described in the runtime doc. **Expert skills** when configured. |
| **`script:{id}`** | — | Site Groovy under **`config/studio/scripts/aiassistant/llm/{id}/runtime.groovy`** (or `llm.groovy`) implementing **`StudioAiLlmRuntime`** or the documented **Map** bundle contract. | Bundle chooses **tools-loop** vs Anthropic-style transport. | **Configurable** by the script (CMS tools, custom behavior). |

---

## Configuration Examples (`agents.json`)

Configure agents in **Project Tools → AI Assistant → Agents** (file: **`config/studio/ai-assistant/agents.json`**). Register the Helper in **`ui.xml`** for widget placement only (see [configuration-guide.md](configuration-guide.md)).

**Always set `llm` on each row** from the summary table. Configure provider keys on the Studio host per vendor column.

### Recommended: OpenAI with CMS Tools (+ Optional Image)

```json
{
  "mode": "chat",
  "agentId": "00000000-0000-4000-8000-000000000002",
  "label": "OpenAI authoring",
  "llm": "openAI",
  "llmModel": "gpt-4o-mini",
  "imageModel": "gpt-image-1-mini",
  "enableTools": true
}
```

- Set **`OPENAI_API_KEY`** on the Studio host (recommended). Do **not** commit keys to Git.
- **`imageModel`** is required for **GenerateImage** on the default wire; there is no silent default in site config.

### Other providers

Use the same shape with **`llm`**: **`claude`**, **`xAI`**, **`deepSeek`**, **`llama`**, **`gemini`**, or **`script:mybackend`**. Set the matching env keys (**`ANTHROPIC_API_KEY`**, **`XAI_API_KEY`**, etc.). For **`script:{id}`**, implement **`config/studio/scripts/aiassistant/llm/{id}/runtime.groovy`** per **[studio-plugins-guide.md](studio-plugins-guide.md)**.

---

## Omitted `llm` And POST Body

The React client may omit **`llm`** on the stream/chat JSON when the selected agent row has no **`llm`**. The server **does not** infer a default adapter: after **agents.json merge** (when **`siteId`** + **`agentId`** are present), **`StudioAiLlmKind.normalize`** requires a **non-blank**, **recognized** `llm` string. Missing or invalid values produce **HTTP 400** on **`/ai/stream`** and **`/ai/agent/chat`**.

When **`siteId`** + **`agentId`** match a catalog row, the server may **copy `llm`**, **`llmModel`**, **`imageModel`**, and **`imageGenerator`** into the POST body before normalize.

**Always set `llm` explicitly** on each chat row in **`agents.json`**.

---

## Per-provider Notes

### Tools-loop Chat Family (`openAI`, `xAI`, `deepSeek`, `llama`, `gemini` / `genesis`)

- **Transport:** Spring AI **`OpenAiChatModel`** + **RestClient** **`/v1/chat/completions`** native tool loop (`AiOrchestrationTools`).
- **Image generation:** **`<imageModel>`** for the default **GenerateImage** wire (e.g. **`gpt-image-1`**). **`<imageGenerator>`** selects **`script:{id}`**, **`none`**, or default wire.

### `claude`

- **Transport:** Spring AI **`AnthropicChatModel`** — tools run inside Spring AI’s Anthropic integration, not the OpenAI RestClient loop.

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
| **`agentId`** / **`id`** | Chat rows | Stable id sent as **`agentId`** on stream/chat; used for catalog merge and form toggles. |
| **`llmSecretKey`** | Production | Optional; **`secrets.json`** entry key (custom secret or built-in provider row). Set in Project Tools → Agents. |
| **`llmApiKey`** | Testing | Per-agent key when no **env** / secrets row; discouraged in production. |
| **`enableTools`** | Tool-capable | When **`false`**, CMS tools are off for that agent (subject to per-request **`omitTools`**). |
| **`expertSkills`** | Tools-loop + Claude (tools on) | Markdown URL skills → **QueryExpertGuidance**. |

---

## REST / Stream Body Keys (Reference)

The client sends catalog fields on **`POST …/ai/stream`** and **`…/ai/agent/chat`**. Common keys: **`llm`**, **`llmModel`**, **`imageModel`**, **`imageGenerator`**, **`llmSecretKey`**, **`llmApiKey`**, **`agentId`**, **`expertSkills`**, preview **`contentPath`** / **`contentTypeId`**, **`omitTools`**, **`enableTools`**. Full list: [chat-and-tools-runtime.md § REST body](../internals/chat-and-tools-runtime.md#rest-body-advanced).

When **`siteId`** + **`agentId`** are present, the server may **merge** missing **`llm`**, **`llmModel`**, **`imageModel`**, and **`imageGenerator`** from **`config/studio/ai-assistant/agents.json`** before orchestration.

---

## Autonomous rows

**`mode: autonomous`** rows use **`openAI`**, **`xAI`**, **`deepSeek`**, **`llama`**, **`genesis`** / **`gemini`** for steps (**tools-loop** stack). **`claude`** is **not** supported for autonomous runs. Details: [chat-and-tools-runtime.md § Autonomous](../internals/chat-and-tools-runtime.md#autonomous-assistants) and [spec.md](../internals/spec.md).
