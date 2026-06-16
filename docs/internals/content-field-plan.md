# Content field plan (form-definition-driven copy)

Companion to **[`intent-recipe-routing.md`](intent-recipe-routing.md)** and **[`chat-and-tools-runtime.md`](chat-and-tools-runtime.md)**.

When the author asks to update page or component **copy** on an anchored preview item, the server builds a **content field plan** from the Studio **form definition** so the planner and tools loop populate each field with **role-appropriate** content — not the same headline pasted into every text field.

**Code:** `FormDefinitionCopyFieldPlan.groovy` (`authoring/scripts/classes/plugins/org/craftercms/aiassistant/studio/contrib/tool/builtin/cms/internal/`).

---

## When it is injected

| Stage | What happens |
|-------|----------------|
| **Plan defer** | `AuthoringIntentRecipeCatalog.formatPlanDeferOrchestrationContextBlock` calls `wireIntoSession` before the plan-stage probe so the probe user message includes the field table. |
| **Turn goal** | `AuthoringTurnGoal.wireIntoRouteResult` prepends `[Studio — content field plan (from form definition)]` to `userTextForToolsLoop` when an anchor path or `contentTypeId` is known. |
| **Tools loop** | `FormDefinitionCopyFieldPlan.formatPreWriteReminder` is injected before the next LLM round after search/fetch when a write is imminent. |
| **GetContentTypeFormDefinition** | Tool response includes `copyFieldPlanMarkdown` and `copyFieldPlan.copyFieldIds` when the form definition parses successfully. |

**Resolution:** `contentTypeId` from servlet bindings (`aiassistant.contentTypeId`), session bundle, or `<content-type>` read from anchored `contentPath` via `GetContent`.

---

## Field roles (heuristics)

Roles are inferred from field **id**, **type**, and form **title** — not site-specific conventions.

| Role | Typical fields | Write guidance |
|------|----------------|----------------|
| `page-title` | `title_t` | Core headline; no "Breaking news:" / "Latest:" prefixes |
| `hero-headline` | `hero_title_html` | Primary hero headline (rich HTML); headline only |
| `hero-deck` | `hero_text_html` | Supporting paragraph(s); **do not** repeat the headline verbatim |
| `section-title` | `*_title_t`, `features_title_t` | Short section label, not the full article headline |
| `body-copy` | `*_body_html`, `*_description_html` | Supporting paragraphs from research |
| `image-asset` | `*_image_s`, image-picker | Repository path from **GenerateImage** persistence — not `studio-ai-inline-image://` |
| `navigation-label` | `navLabel` | Short nav text |

**Node-selector** fields (e.g. `sections_o`) are listed as **component references** — full-page copy updates may require **GetContent** on referenced component paths.

---

## Planner and executor rules

The plan-stage probe (`GENERAL_LLM_AUTHORING_INTENT_REFINE_PLAN_PROBE_SYSTEM`) requires a **Content field plan** table when the turn updates anchored copy and the server plan is present.

Execution policy (`AuthoringTurnGoal`) adds:

- Populate **every** listed copy field with **distinct** content per role.
- Headline → `title_t` / `hero_title_html`; supporting context → `hero_text_html` / body fields.

Research grounding nudges reference the content field plan when shallow fetch or write-before-fetch issues occur.

---

## Generated image persistence

**GenerateImage** results are imported to `/static-assets/item/images/{yyyy}/{mm}/{dd}/` and wired into **WriteContent** / auto-apply on anchored `*_image_s` fields. See `GeneratedImageCmsPersistence.groovy`.

**FetchHttpUrl cap:** default unlimited (`0`); when `toolsLoopExternalLookupRequired` is true on the session bundle, cap is **2** per turn (recipe-level `toolsLoopMaxFetchHttpUrlCalls` still overrides).

---

## Tests

| Check | Command |
|-------|---------|
| Offline role parity | `node scripts/test/functional/copy-field-plan-offline.mjs` |
| Execution plan chains | `node scripts/test/functional/intent-execution-plan-offline.mjs` |
| Live headline + page update (optional) | `CHAT_SITE_ID=your-site node scripts/test/functional/run-chat-scenarios.mjs scripts/test/scenarios/chat-scenarios-headline-page-update.json` |

The live scenario does **not** embed `siteId` — set **`CHAT_SITE_ID`** (or `defaults.siteId` in a local gitignored copy). The site must have `/site/website/index.xml` with content type `/page/home`.
