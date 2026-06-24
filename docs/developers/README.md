# AI Assistant — Developers

Documentation for **plugin contributors**, **maintainers**, and **integrators** who change `sources/`, `authoring/scripts/classes/`, build output, or need wire-level contracts.

**Installing and configuring a site without code changes?** Use **[Admins & authors documentation](../admins-and-authors/README.md)** instead—that is the primary path for most Crafter customers.

---

## Start here

| Role | Document |
|------|----------|
| Build, package, install paths, plugin id | [Studio plugins guide](../using-and-extending/studio-plugins-guide.md) |
| Official behavior & contracts | [spec.md](../internals/spec.md) |
| SSE `/ai/stream`, tools, MCP, REST bodies | [chat-and-tools-runtime.md](../internals/chat-and-tools-runtime.md) |
| Intent recipe routing (server) | [intent-recipe-routing.md](../internals/intent-recipe-routing.md) |
| Groovy package layout | [package-architecture.md](../internals/package-architecture.md) |
| Clone, `yarn package`, doc policy | [CONTRIBUTING.md](../../CONTRIBUTING.md) |

---

## Internals index

Full list: **[internals/README.md](../internals/README.md)**

| Document | Covers |
|----------|--------|
| [spec.md](../internals/spec.md) | Surfaces, `ui.xml`, form engine, stream, autonomous REST, `studio-ui.json` |
| [stream-endpoint-design.md](../internals/stream-endpoint-design.md) | SSE stream contract |
| [chat-and-tools-runtime.md](../internals/chat-and-tools-runtime.md) | Tool catalog, MCP, SSE, troubleshooting |
| [intent-recipe-routing.md](../internals/intent-recipe-routing.md) | Router pipeline, prelude, cross-site working site |
| [package-architecture.md](../internals/package-architecture.md) | `engine` / `spi` / `contrib` / `studio` |
| [groovy-documentation-standard.md](../internals/groovy-documentation-standard.md) | Required Groovy comments |
| [maintainer-review-checklist.md](../internals/maintainer-review-checklist.md) | Review anti-patterns |
| [reference-spring-ai-completions-with-tools.md](../internals/reference-spring-ai-completions-with-tools.md) | Archived Spring AI notes |

---

## Diagrams (implementation)

[Architecture & diagrams](../architecture-diagrams.md) — system context, logical layers, **stream request path**, build pipeline, component registration.

Admin-facing diagrams (setup flow, author surfaces) are linked from **[admins & authors](../admins-and-authors/README.md)**.

---

## Site extension without forking the plugin

Admins can add sandbox scripts; developers document the contracts:

| Extension | Guide |
|-----------|--------|
| `user-tools/` + `registry.json` | [Scripted tools & imagegen](../using-and-extending/scripted-tools-and-imagegen.md) |
| `imagegen/{id}/generate.groovy` | Same · [Image generation](../using-and-extending/image-generation.md) |
| Custom recipes | [Building intent recipes](../using-and-extending/building-intent-recipes.md) · skill `.cursor/skills/building-intent-recipes/` |

---

## Legacy doc paths

| Old path | Current |
|----------|---------|
| [docs/SPEC.md](../SPEC.md) | [internals/spec.md](../internals/spec.md) |
| [docs/LLM_CONFIGURATION.md](../LLM_CONFIGURATION.md) | [using-and-extending/llm-configuration.md](../using-and-extending/llm-configuration.md) |
| [docs/DEVELOPERS_GUIDE_CRAFTER_STUDIO_PLUGINS.md](../DEVELOPERS_GUIDE_CRAFTER_STUDIO_PLUGINS.md) | [studio-plugins-guide.md](../using-and-extending/studio-plugins-guide.md) |
