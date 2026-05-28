# Site configuration guides (admins & authors)

**Preferred entry:** **[Admins & authors documentation](../admins-and-authors/README.md)** — install, Project Tools, `ui.xml`, agents, secrets, and author surfaces.

This folder holds the **detailed guides** linked from that hub. You do not need the [developers documentation](../developers/README.md) unless you are building or patching the plugin.

**Diagrams:** [Architecture & diagrams](../architecture-diagrams.md). **Product requirements:** [product-requirements.md](product-requirements.md). **Engineering contracts:** [spec.md](../internals/spec.md) and [studio-plugins-guide.md](studio-plugins-guide.md).

## Guides

| Document | What it covers |
|----------|----------------|
| [installation.md](installation.md) | Install from Studio UI (with screenshots), CLI, Marketplace API, **`install-plugin.sh`**, build-before-install |
| [configuration-guide.md](configuration-guide.md) | **Admins — main reference** — **Basic:** `ui.xml`, plugin id, agents, keys, form pipeline, autonomous checklist (**§1–§7**). **`§1e`:** **`studio-ui.json`**. **Advanced:** prompts, `tools.json`, MCP, user tools, script LLM (**§9**). [Screenshots](configuration-guide.md#cg-screenshots) |
| [helper-widget.md](helper-widget.md) | Helper **`ui.xml`** snippet and “component not found” checklist |
| [autonomous-assistants-widget.md](autonomous-assistants-widget.md) | Optional autonomous widget — placement and overview |
| [llm-configuration.md](llm-configuration.md) | **`<llm>`** — providers, env + `ui.xml`, merge rules |
| [image-generation.md](image-generation.md) | **Pluggable `GenerateImage`** — wire vs **`script:{id}`** |
| [studio-aiassistant-jvm-parameters.md](studio-aiassistant-jvm-parameters.md) | JVM **`-D`** tuning (timeouts, HTTP/MCP caps) |
| [scripted-tools-and-imagegen.md](scripted-tools-and-imagegen.md) | Site **`InvokeSiteUserTool`** + **`imagegen/`** (integrators) |
| [building-intent-recipes.md](building-intent-recipes.md) | Custom workflows — Cursor skill + Project Tools **Recipes** tab |
| [product-requirements.md](product-requirements.md) | Product obligations for authors, admins, integrators |

## Developers only

| Document | Audience |
|----------|----------|
| [studio-plugins-guide.md](studio-plugins-guide.md) | Rollup, descriptor, classpath, install paths — see [developers/README.md](../developers/README.md) |
