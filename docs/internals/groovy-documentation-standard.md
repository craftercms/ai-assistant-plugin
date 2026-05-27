# Groovy documentation standard (AI Assistant plugin)

Every **`.groovy`** file under **`authoring/scripts/`** (classes and REST controllers) must document:

1. **Each top-level type** (`class`, `interface`, `trait`, `enum`) with a block comment (`/** … */`) immediately above the declaration.
2. **Each method** (including `static`, `private`, and package-private) with a block comment immediately above the declaration.

## What to write

- **One or two sentences** stating purpose and non-obvious behavior.
- **`@param` / `@return`** when names or units are not obvious.
- **`{@link OtherType#method}`** when the method is part of a larger pipeline.
- **Site-agnostic** wording — no customer site ids, demo copy, or hardcoded field names (see `.cursor/rules/no-project-specific-content.mdc` when present locally).

## What not to do

- Restate the method name only (“Returns the site id”).
- Paste implementation line-by-line.
- Use block comments for **inline** logic inside method bodies (only declarations).

## Examples

```groovy
/**
 * Resolves the Studio UI session site from the servlet request and plugin URL params.
 * This is not the POST-body working CMS site used for repository tools.
 */
static String resolveStudioSessionSiteId(Object request, Map params = null) { … }
```

```groovy
/** Truncates prior-turn memory text for intent-refine prompts; appends {@code …} when over limit. */
private static String clipPriorTurnMemoryText(String text, int maxChars) { … }
```

## Studio UI

- **Integrations → Tools** — edit **`tools.json`** (built-in tools, user tools, **Plugin RAG**, **Agent skills RAG**). See [configuration-guide §9.2.1](../using-and-extending/configuration-guide.md#cg-9-2-1).
- **Agents** — per-agent skills URLs; same **`tools.json`** also under **Site orchestration** on an agent.

## Audit

From the repo root:

```bash
./scripts/check-groovy-documentation.sh
```

Exit code **1** lists types or methods missing a `/**` block on the line above the declaration (heuristic; review false positives for closures).

To backfill missing blocks (then polish obvious boilerplate):

```bash
python3 scripts/add-groovy-documentation.py
python3 scripts/polish-groovy-documentation.py
./scripts/check-groovy-documentation.sh
```

## Related docs

- [configuration-guide.md §9.2.1](../using-and-extending/configuration-guide.md#cg-9-2-1) — Plugin RAG vs agent skills RAG
- [maintainer-review-checklist.md](maintainer-review-checklist.md) — review expectations
- [chat-and-tools-runtime.md](chat-and-tools-runtime.md) — runtime behavior

When you add or change Groovy in this plugin, **document in the same change** — do not defer comment-only follow-ups across the whole tree.
