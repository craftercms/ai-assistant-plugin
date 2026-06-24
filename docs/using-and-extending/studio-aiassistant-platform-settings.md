# Studio AI Assistant — platform settings

Site-level tuning and LLM fallbacks live in:

**`config/studio/scripts/aiassistant/config/platform-settings.json`**

(Project Tools → **Platform settings**, or edit the file in the site sandbox.)

Values are read per request via **`StudioAiPlatformSettings`** (loaded on the stream/chat thread). Use this file for **defaults and caps** — not for secrets (use **[Secrets / `secrets.json`](configuration-guide.md#cg-4)** and **`crafter_*`** host env vars per **[llm-configuration.md](llm-configuration.md)**).

---

## LLM provider keys and models

After **`secrets.json`** resolution, empty provider keys may fall back to **`crafter.*.apiKey`** properties here (discouraged for production — prefer **`${env:crafter_*_api_key}`** in Secrets).

| Property | Purpose | Code default when agent omits `llmModel` |
|----------|---------|------------------------------------------|
| **`crafter.openai.apiKey`** | OpenAI API key fallback | — |
| **`crafter.openai.model`** | OpenAI chat model | *(required if unset on agent/request)* |
| **`crafter.openai.imagesBaseUrl`** | Images API base | `https://api.openai.com` |
| **`crafter.anthropic.apiKey`** | Anthropic key fallback | — |
| **`crafter.anthropic.model`** | Claude chat model | **`claude-sonnet-4-20250514`** |
| **`crafter.anthropic.maxTokens`** | Claude max output tokens | **8192** (clamped 256–65536) |
| **`crafter.xai.apiKey`** | xAI key fallback | — |
| **`crafter.xai.model`** | xAI chat model | **`grok-4.3`** |
| **`crafter.xai.llmBaseUrl`** | xAI tools-loop base (no trailing `/v1`) | `https://api.x.ai` |
| **`crafter.deepseek.model`** | DeepSeek model | **`deepseek-chat`** |
| **`crafter.deepseek.llmBaseUrl`** | DeepSeek base | `https://api.deepseek.com` |
| **`crafter.llama.model`** | Ollama / compatible model | **`llama3.2`** |
| **`crafter.llama.llmBaseUrl`** | Local / remote OpenAI-compatible host | `http://127.0.0.1:11434` |
| **`crafter.gemini.model`** | Gemini model | **`gemini-2.0-flash`** |
| **`crafter.gemini.llmBaseUrl`** | Gemini OpenAI-compat base | Google generative language OpenAI endpoint |

Host env overrides for base URLs: **`crafter_xai_base_url`**, **`crafter_deepseek_base_url`**, **`crafter_llama_base_url`**, **`crafter_gemini_base_url`**, **`crafter_openai_images_base_url`**.

---

## Orchestration and HTTP tuning

| Property | Default | Purpose |
|----------|---------|---------|
| **`aiassistant.chatFluxAwaitMs`** | 300000 | Max wait for Spring AI chat flux / tool loop completion |
| **`aiassistant.openai.restReadTimeoutMs`** | *(derived)* | RestClient read timeout for tools-loop `/v1/chat/completions` |
| **`aiassistant.openai.restConnectTimeoutMs`** | *(derived)* | RestClient connect timeout |
| **`aiassistant.openai.sseWaitHeartbeatMs`** | *(derived)* | SSE heartbeat while awaiting upstream chat |
| **`aiassistant.springAiHttpDebug`** | false | Verbose Spring AI HTTP trace |
| **`aiassistant.openai.reviewMaxChars`** | *(derived)* | Cap for review / refine wire payloads |
| **`aiassistant.translateContentItemMaxOutTokens`** | *(derived)* | Inner translate completion token cap |

---

## Tools, MCP, preview, autonomous

| Property | Default | Purpose |
|----------|---------|---------|
| **`aiassistant.httpFetch.enabled`** | true | Allow **FetchHttpUrl** / **PostHttpUrl** / MCP outbound HTTP |
| **`aiassistant.httpFetch.maxChars`** | 400000 | Max response body chars for generic HTTP fetch |
| **`aiassistant.httpFetch.allowedHostSuffixes`** | *(empty)* | Extra SSRF allowlist suffixes (comma-separated) |
| **`aiassistant.mcp.maxResponseChars`** | 500000 | MCP HTTP body cap |
| **`aiassistant.preview.fetch.maxChars`** | 400000 | **GetPreviewHtml** body cap |
| **`aiassistant.preview.fetch.allowedHosts`** | *(empty)* | Extra preview fetch hosts |
| **`aiassistant.preview.fetch.forwardAuthorization`** | false | Forward author Authorization to preview fetch |
| **`aiassistant.siteContentResearch.enabled`** | true | **ResearchSiteContent** toggle |
| **`aiassistant.siteContentResearch.maxSearchHits`** | 12 | Search hit cap |
| **`aiassistant.siteContentResearch.maxFetchItems`** | 5 | Follow-up fetch cap |
| **`aiassistant.siteContentResearch.excerptChars`** | 1800 | Excerpt length |
| **`aiassistant.autonomous.worker.max`** | *(derived)* | Autonomous worker pool max threads |
| **`aiassistant.autonomous.worker.core`** | *(derived)* | Autonomous worker pool core |
| **`aiassistant.autonomous.worker.queue`** | *(derived)* | Autonomous worker queue capacity |
| **`aiassistant.crafterizingPlaybook.path`** | *(bundled)* | Override Crafterizing playbook markdown path |
| **`aiassistant.maintainerObservability.enabled`** | true | Maintainer SSE observability blocks |

Parallel tool pool sizing uses **`aiassistant.parallelTools.*`** keys via **`StudioAiSandboxConcurrency`**.

---

## Example `platform-settings.json`

```json
{
  "crafter.anthropic.model": "claude-sonnet-4-20250514",
  "crafter.anthropic.maxTokens": "8192",
  "crafter.xai.model": "grok-4.3",
  "aiassistant.httpFetch.maxChars": "400000"
}
```

Do **not** store raw API keys in Git-tracked site config unless encrypted; prefer **Secrets** → **`${env:crafter_*}`**.
