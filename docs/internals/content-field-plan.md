# Content field plan (dynamic content model)

Companion to **[`intent-recipe-routing.md`](intent-recipe-routing.md)** and **[`chat-and-tools-runtime.md`](chat-and-tools-runtime.md)**.

## Core rule: the content model is totally dynamic

**Never hardcode field ids** (`title_t`, `hero_title_html`, `sections_o`, etc.) in engine, tool, or orchestration logic.

Each Crafter site defines its own content types in **`/config/studio/content-types/…/form-definition.xml`**. Field ids, sections, and relationships differ per blueprint. The AI Assistant must behave like **engine code**:

| Do | Don't |
|----|--------|
| Load the form definition for the anchored `contentTypeId` / `contentPath` | Assume Editorial, blog, or any named blueprint |
| Build a **content field plan** from form XML (`title`, `description`, `help`, `type`, section) | Branch on `if (fieldId == 'title_t')` |
| Gate writes using **`writePolicy`** roles from the plan | Encode hero/home/features layout in Groovy |
| List **node-selector** fields from the form as component references | Hardcode `sections_o` / `header_o` field names |
| Scan index/XML with **naming conventions** (`*_t`, `*_html`) when no form is loaded | Require `title_t` in OpenSearch hits |

**Code map**

| Concern | Class |
|---------|--------|
| Copy field plan + write policies | `FormDefinitionCopyFieldPlan.groovy` |
| WriteContent validation vs form | `FormDefinitionWriteContentValidator.groovy` |
| OpenSearch title/snippet without fixed fields | `CmsIndexedContentFields.groovy` |
| Slug/title text from arbitrary item XML | `ContentXmlTextScan.groovy` |
| Tools-loop artifacts (sample values) | `ToolsLoopTurnArtifacts.groovy` (uses session plan field ids) |
| Research → fetch → write gates | `AuthoringResearchGrounding.groovy` |

---

## When the plan is injected

| Stage | What happens |
|-------|----------------|
| **Plan defer** | `AuthoringIntentRecipeCatalog.formatPlanDeferOrchestrationContextBlock` calls `wireIntoSession` before the plan-stage probe. |
| **Turn goal** | `AuthoringTurnGoal.wireIntoRouteResult` prepends `[Studio — content field plan (from form definition)]` when anchor path or `contentTypeId` is known. |
| **Tools loop** | `FormDefinitionCopyFieldPlan.formatPreWriteReminder` after search/fetch when a write is imminent. |
| **GetContentTypeFormDefinition** | Response includes `copyFieldPlanMarkdown` and `copyFieldPlan.copyFieldIds` when the form parses. |

**Resolution:** `contentTypeId` from servlet bindings, session bundle, or `<content-type>` on anchored `GetContent`.

---

## Write policies (from form metadata, not field ids)

Policies are inferred from each field's **form-definition metadata** (`title`, `description`, `help`, section title, field type) — never from field id patterns.

| `writePolicy` | Meaning |
|---------------|---------|
| `original-headline` | Main visitor-facing headline for this item (form text says headline / H1 / page title) |
| `supporting-copy` | Body, deck, intro, summary — facts from research, distinct from headline |
| `section-label` | Short block label (form text says section/grid label, not article headline) |
| `image-path` | `image-picker` — repository path from **GenerateImage** or existing upload |
| `navigation` | Nav/menu label |
| `seo-metadata` | SEO / meta description fields |
| `rich-copy` / `short-copy` / `author-copy` | Fallback when metadata is sparse |

Improve accuracy by documenting fields in form definitions (`<description>`, `<help>`) — empty forms only get type hints and label text.

**Node-selector** fields are listed as **component references** (field id + label from form). Full-page copy may require **GetContent** on each referenced path.

---

## Planner and executor rules

- Populate **every** copy field in the plan with **distinct** content matched to **Purpose** / `writePolicy`.
- Research pages are **facts only** — do not paste fetched page titles into `original-headline` roles (`FormDefinitionCopyFieldPlan.gateWriteContent`).
- External lookup turns: **SerpApiWebSearch** → **FetchHttpUrl** (substantive body) → **WriteContent** (`AuthoringResearchGrounding`). The tools loop must not force **WriteContent** while fetch is still required.
- **Optional** `image-path` fields: invented paths do **not** block copy writes — baseline reconcile keeps the existing image until **GenerateImage** runs.
- **Research page refresh** (live lookup + copy update + image-asset fields on the form): **GenerateImage** is required after copy write; the server auto-applies imported paths to image-asset fields on the anchored item.
- **original-headline** gate blocks task-paraphrase headlines ("Latest updates on…", "Insights and Implications") and verbatim fetched source titles.

---

## Index and search conventions

OpenSearch stores **dynamic** field names per content type. `CmsIndexedContentFields` uses:

- `internal-name`, `navLabel`, `*_t`, `*_html`, `seoDescription_t` for search boosts and display labels
- Key-pattern scans on hit `_source` — not a fixed `title_t` / `body_html` list

This is an **index-layer** convention (Crafter naming suffixes), not a single content model.

---

## Tests

| Check | Command |
|-------|---------|
| Offline writePolicy parity | `node scripts/test/functional/copy-field-plan-offline.mjs` |
| Execution plan chains | `node scripts/test/functional/intent-execution-plan-offline.mjs` |

Fixture: `scripts/test/fixtures/page-home-form-definition.xml` — illustrative only; engine code must not assume that shape in production paths.

---

## Maintainer checklist (new code)

1. Does this branch on a specific field id? → Use the session **copy field plan** or form definition parse.
2. Does this mention blueprint field names in user-facing errors? → Say "fields from the content field plan".
3. Does this read XML or OpenSearch hits? → Use `ContentXmlTextScan` / `CmsIndexedContentFields`.
4. Adding a write gate? → Key off `writePolicy`, not field name.

See also **[`maintainer-review-checklist.md`](maintainer-review-checklist.md)**.
