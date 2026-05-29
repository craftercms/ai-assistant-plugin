# Plugin internals (developers)

**Audience:** Maintainers and integrators debugging **server-side** behavior, contracts, and orchestration.

**Site install and configuration (most users):** **[Admins & authors documentation](../admins-and-authors/README.md)** — not this folder.

**Developer entry hub:** **[developers/README.md](../developers/README.md)**

**Diagrams:** [architecture-diagrams.md](../architecture-diagrams.md) — system context, logical layers, stream path, build pipeline.

## Documents

| Document | What it covers |
|----------|----------------|
| [**spec.md**](spec.md) | **Official requirements & mechanics** — surfaces, `ui.xml`, form engine, stream, autonomous REST, **`studio-ui.json`** |
| [**stream-endpoint-design.md**](stream-endpoint-design.md) | SSE stream (`/ai/stream`) contract |
| [**chat-and-tools-runtime.md**](chat-and-tools-runtime.md) | Tools, MCP, RAG, SSE, REST fields, troubleshooting |
| [**intent-recipe-routing.md**](intent-recipe-routing.md) | Pre-tools recipe routing; admin UI: [configuration-guide §9.0](../using-and-extending/configuration-guide.md#cg-9-0) |
| [**package-architecture.md**](package-architecture.md) | Groovy `spi` / `engine` / `contrib` / `studio` layout |
| [**groovy-documentation-standard.md**](groovy-documentation-standard.md) | Required `/** … */` on Groovy under `authoring/scripts/` |
| [**maintainer-review-checklist.md**](maintainer-review-checklist.md) | Review anti-patterns (React/TS, Groovy, SSE) |
| [**reference-spring-ai-completions-with-tools.md**](reference-spring-ai-completions-with-tools.md) | Archived Spring AI reference |

## Debug logging

| What | How |
|------|-----|
| Plugin orchestration / payload previews | Logger **DEBUG** on `plugins.org.craftercms.aiassistant.*` |

**JVM flags:** [studio-aiassistant-platform-settings.md](../using-and-extending/studio-aiassistant-platform-settings.md)

## Admin configuration (cross-links)

| Need | Document |
|------|----------|
| Install | [installation.md](../using-and-extending/installation.md) |
| Configure site | [configuration-guide.md](../using-and-extending/configuration-guide.md) |
| LLM keys | [llm-configuration.md](../using-and-extending/llm-configuration.md) |
