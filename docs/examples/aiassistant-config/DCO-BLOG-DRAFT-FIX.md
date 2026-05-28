# DCO blog draft → create post (config-only fix)

Your debug log showed **Turn 2** prior assistant context was essentially:

```text
Author voice: **…**
## draft

✏️ Draft · voice: **…**
```

There was **no** `*Draft blog:*` block and no article body. **`createFromChatDraft`** prefetch (bundled **`new_content_item`**) cannot invent the article from that — **WriteContent** falls back to sibling XML / model guess.

That is a **Turn 1 recipe / author-visible shape** problem, not something you fix by tweaking **`new_content_item`** alone.

## What to deploy on site **dco**

Studio path (under the site sandbox), relative to `config/studio/`:

| File | Example in this repo |
|------|----------------------|
| `scripts/aiassistant/config/intent-recipes.json` | [`sites/dco/scripts/aiassistant/config/intent-recipes.json`](sites/dco/scripts/aiassistant/config/intent-recipes.json) |
| `scripts/aiassistant/config/tools.json` | Merge [`sites/dco/scripts/aiassistant/config/tools.json`](sites/dco/scripts/aiassistant/config/tools.json) into your existing `tools.json` |

**Deploy steps**

1. Copy or merge **`recipe_1779227205002`** from the site example (or single-recipe file [`intent-recipes-dco-propose-blog-recipe.json`](intent-recipes-dco-propose-blog-recipe.json)) into the site’s `intent-recipes.json`. Site recipes **override** bundled defaults by matching **`id`**.
2. Ensure **`tools.json`** → **`intentRecipeRouting`** includes:
   - `"confirmationLlmRefineEnabled": true`
   - `"confirmationLlmRefineMaxOutTokens": 8192` (or higher) for long **draft** passthrough fallback
   - `"customRecipesPath": "/scripts/aiassistant/config/intent-recipes.json"` if not already set
3. Commit/push the site sandbox and reload Studio (or use **Project Tools → AI Assistant → Recipes → Save**).

Bundled plugin default **`draft_content_from_source`** (in `authoring-intent-recipes-default.json`) gives the same **## Draft body** / `*Draft blog:*` shape for sites without a custom DCO Slack recipe.

## Key recipe changes (why they matter)

| Change | Why |
|--------|-----|
| `toolsLoopAllowlist`: **only** `FetchHttpUrl` | Stops **ResearchSiteContent** loops that bias toward existing site posts. |
| Action requires **## Draft body** + `*Draft blog:*` | Turn 1 author-visible shape; Turn 2 server prefetch reads the same markers via **`prefetchSupplementConfig`** (site config, not hardcoded in plugin Java). |
| **`prefetchSupplementConfig`** on **`new_content_item`** deterministic match | Plugin copies sibling XML and injects draft prose using **your** section headings / inline markers (e.g. `Draft body`, `*Draft blog:*`). |
| `draft` **not** in **llmRefine** `outputKeys`; **passthroughFromSource** | Long article copied from chat, not a hollow **## draft** header. |
| **passthroughFallbackHints** for `draft` | When the tools loop only emits a short status, confirmation still builds a full **draft**. |

## How to verify

**Turn 1** — chat (or Turn 2 debug **wire**) must include:

```text
## Draft body

Author voice: **…**
*Draft title:* …
*Outline:*
- (each topic the author named)
*Draft blog:*

(full article paragraphs…)
```

**Turn 2** — debug should show:

- `priorTurnsBlockLen` **well over** ~400
- `toolsLoopCreateFromChatDraftDraftExtractReady: true` (when deployed plugin supports prefetch)
- Post path/slug from ***Draft title:***, not generic `draft-post-…` paths

**Turn 2 phrase** (bundled **`createFromPriorDraftFollowup`** — deictic + CMS persist, not blog/post-specific):

- “Save **this**” / “Create **this** as content” (with Studio anchor open)
- “Make **it** into a page” / “Create a post from **this** draft”
- “Put **what you wrote** in the repo”

## Post body XML (DCO — why “where is my body?”)

On **dco**, `/component/post` stores the article inside **`content_o`** as an **inline** `/component/rich_text` with **`content_html`** — not a top-level `<rich_text><content_html>` under `content_o`.

Deploy **[`sites/dco/context/site-authoring.md`](sites/dco/context/site-authoring.md)** to:

`config/studio/scripts/aiassistant/context/site-authoring.md`

and the site override **`new_content_item_from_chat_draft`** in [`sites/dco/scripts/aiassistant/config/intent-recipes.json`](sites/dco/scripts/aiassistant/config/intent-recipes.json).

**Wrong (will not render body in Studio):**

```xml
<content_o>
  <rich_text>
    <content_html><![CDATA[...]]></content_html>
  </rich_text>
</content_o>
```

**Right (generate per [`sites/dco/context/site-authoring.md`](sites/dco/context/site-authoring.md)):**

- **Two UUIDs** (post ≠ inline rich_text)
- `<item datasource="richTextSections" inline="true">` — attributes, not `<datasource>` child elements
- `<component id="…">` **inside** `<item>`, not sibling of `<item>`
- `value_smv` on categories/tags; keys from **GetContent** on `/site/taxonomy/topics.xml` and `/site/taxonomy/tags.xml`; bio `<key>`/`<include>` = real `/site/components/bio/{uuid}.xml`

```xml
<content_o item-list="true">
  <item datasource="richTextSections" inline="true">
    <key>{uuid-b}</key>
    <value>Title - Content</value>
    <component id="{uuid-b}">
      <content-type>/component/rich_text</content-type>
      …
      <content_html>&lt;p&gt;…&lt;/p&gt;</content_html>
    </component>
  </item>
</content_o>
```

After deploying **site-authoring.md** and the DCO recipe override, re-run Turn 2 — the model must **generate** this shape from the spec, not **GetContent** another post.

## If Turn 1 still has no *Draft blog:*

1. Confirm the site file was saved and **Recipes** UI shows **`recipe_1779227205002`** with **FetchHttpUrl**-only allowlist.
2. Bump **`confirmationLlmRefineMaxOutTokens`** in **`tools.json`**.
3. Inspect Turn 1 **Recipe confirmation** in debug: **draft** passthrough should be non-empty.

## Turn 2 prefetch bug (fixed in plugin)

Prior-turn extraction used the **first** `---` inside the assistant markdown (e.g. after **## sources**), so **`## draft`** / `*Draft blog:*` never reached prefetch. That produced generic paths like `new-item-YYYYMMDD.xml` and wrong XML. Prefetch no longer assembles **contentXml** server-side; the model must **WriteContent** XML generated from **GetContentTypeFormDefinition** + **Project authoring context**.

The plugin now ends the prior block at `---` immediately before **`Current request:`**, and bundled **`prefetchSupplementConfig`** prefers `*Draft blog:*` / `*Draft title:*` markers.

## Site config for Turn 2 (`createFromChatDraft`)

Add on the **`new_content_item`** deterministic match entry that sets `"toolsLoopPrefetchSupplement": "createFromChatDraft"` (merge into site `intent-recipes.json` or override that recipe):

```json
"prefetchSupplementConfig": {
  "priorDraftSectionHeadings": ["Draft body", "draft"],
  "priorDraftInlineMarkers": ["*Draft blog:*"],
  "priorTitleInlineMarkers": ["*Draft title:*"],
  "confirmationFollowUpSectionHeadings": ["Draft body", "draft", "Author idea", "Work notes"]
},
"confirmationFollowUpChatSections": ["Draft body", "draft", "Author idea", "Work notes"]
```

Optional in **`tools.json`** → **`intentRecipeRouting.priorDraftDetection`** (same headings) if you rely on bundled **`createFromPriorDraftFollowup`** routing.

Plugin code is site-agnostic; **blog/post/DCO** wording lives only in site recipes and examples like this file.

## Turn 2 “Good. Make a post” — why it retries and what to deploy

Your debug log showed **`recipeId`: `new_content_item_from_chat_draft`** but **`toolsLoopAllowlist`** still included **`GetContentTypeFormDefinition`**, **`ResearchSiteContent`**, **`ListStudioContentTypes`** — that is **not** the site example recipe (which allows only **`ContentExists`** + **`WriteContent`**). The Studio sandbox is running an **older** `intent-recipes.json` (or the recipe entry was not merged). Until you sync, the model will keep re-discovering forms, blow the context window, and often write XML with wrong slug / empty SEO / fake bio.

### Deploy (no plugin Groovy changes)

| Step | Action |
|------|--------|
| 1 | Copy [`sites/dco/scripts/aiassistant/config/intent-recipes.json`](sites/dco/scripts/aiassistant/config/intent-recipes.json) → site `config/studio/scripts/aiassistant/config/intent-recipes.json` (merge by **`id`**: `new_content_item_from_chat_draft`). |
| 2 | Copy [`sites/dco/context/site-authoring.md`](sites/dco/context/site-authoring.md) → `config/studio/scripts/aiassistant/context/site-authoring.md`. |
| 3 | Commit/push site sandbox; reload Studio / **AI Assistant → Recipes → Save**. |
| 4 | Delete or rename a half-written post if prefetch **ContentExists** is **true** on the suggested slug (e.g. `.../the-future-of-ai-innovations-from-google-i-o-2026.xml`) and you want a clean create. |

### After sync — intent telemetry must show

- `toolsLoopAllowlist`: **`["ContentExists","WriteContent"]`** only (two tools).
- `toolsLoopForceTool`: **`ContentExists`** (round 0 uses native `tool_choice` — not prose `functions.*` stubs).
- `toolsLoopPrefetchSupplement`: **`createFromChatDraft`**.
- Prefetch steps include **`authorBioPaths`** (`ListPagesAndComponents` under `/site/components/bio/`), taxonomy **GetContent**, post + rich_text form defs.
- `toolsLoopFastPath`: **true** in `prefetchSupplementConfig` (skips redundant discovery in the **tools loop** only — **does not** skip prefetch `engineSteps`).

### Chat agent model (Turn 2 save)

In **`config/studio/ai-assistant/agents.json`** (Project Tools → Agents), set the authoring chat agent:

```json
"llmModel": "gpt-4.1-mini"
```

**gpt-4.1-mini** follows native **`tool_calls`** more reliably than **gpt-4o-mini** for **ContentExists** + **WriteContent**, with lower latency than **gpt-4o** on long XML turns. Reload Studio after changing agents.json.

### First WriteContent must pass when

- **`pageDescription_s`** and **`blurb_t`**: first sentence of `*Draft blog:*` (recipe **`writeVerification`** can fill if the model omits them — requires deployed plugin with config-driven verification).
- **`authorBio_o`**: path from prefetch bio catalog matching **Author voice: Sara** → Sarah Miller bio (see **site-authoring.md**); never `unknown_bio.xml`.
- **`categories_o` / `tags_o`**: keys from taxonomy XML with **strong** fit only (e.g. `ai-ml` for Google I/O AI — not `aws` / `open-source` unless the draft is about those).
- **`<file-name>`** basename must match **WriteContent** `contentPath` (your Turn 2 retry failed because path was `…-2026.xml` but `<file-name>` was `…-2026-v2.xml` — same slug in both places, including when using `-v2`).

### Turn 1 prerequisite

Turn 2 cannot invent the article. Prior chat must include **`## draft`** (or **Draft body**) with **`*Draft title:*`**, **`*Draft blog:*`** (full paragraphs), and **`Author voice:`**.
