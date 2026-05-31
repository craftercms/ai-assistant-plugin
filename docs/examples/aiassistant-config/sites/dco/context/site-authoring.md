# DCO project authoring context

Stable site facts for **blog drafting** and **`/component/post`** persistence. Read the **phase** section first — most turns are **Turn 1** (prose only).

---

## Which phase is this turn?

| Signal | Phase | What to do |
|--------|-------|------------|
| Author pasted a **URL** and asked to **draft / summarize / write** an article (with named topics) | **Turn 1** | Fetch + **chat prose only** — see below. **No repository writes.** |
| Author asks to **save / make a post / create content** and **[Prior conversation]** has complete `*Draft title:*` **and** `*Draft blog:*` (full paragraphs) | **Turn 2** | Persist to `/component/post` — see below. Recipe: `new_content_item_from_chat_draft`. |
| Author asks to save but prior chat has **no** `*Draft blog:*` body | **Blocked** | Do **not** WriteContent. Ask them to redo Turn 1 (URL draft) first. |
| Unsure | **Turn 1 default** | Do **not** call WriteContent, ListStudioContentTypes, GetContentTypeFormDefinition, GeneratePlaceholderImage, or ResearchSiteContent. |

**Turn 1 recipe (when router matches):** `recipe_1779227205002` — allowlist is **FetchHttpUrl** only; server runs Slack after confirmation.

---

## Turn 1 — URL → chat draft (prose only)

**Goal:** Produce a reviewable article in **assistant markdown**, not a CMS item.

**Tools (Turn 1):**

- **Allowed:** **FetchHttpUrl** once on the author’s URL (when the recipe or plan says so).
- **Forbidden:** **WriteContent**, form-definition tools, placeholder image, site research/search, Slack in tool_calls.

**Required output** — include a **## Draft body** section with:

```text
Author voice: **{pick one voice below}**
*Draft title:* {working title}
*Outline:*
- {one bullet per topic the author named — mandatory coverage}
*Draft blog:*

{full article — at least 6–8 paragraphs, grounded in the fetch}
```

Also include short **## root** (pitch) and **## craftercmsAlignment** (bullets). Put the **long article only** under `*Draft blog:*`, not in root.

**Rules:**

- Cover **every** topic the author listed; no generic filler unrelated to those topics.
- Do **not** end with only a tools status or an empty draft header.
- Do **not** invent repository paths or XML on Turn 1 — Turn 2 handles persistence.

### Author voice (Turn 1)

Pick one DCO blog author persona and spell it in `Author voice: **Name**` (bold). Example mapping used on this site: voice **Sara** → bio **Sarah Miller** at `/site/components/bio/29217b50-23b3-92e8-a30a-fe0778dbc6f5.xml` (Turn 2 resolves via prefetch — do not put bio paths in Turn 1 prose).

---

## Turn 2 — Persist prior chat draft → `/component/post`

**Goal:** Copy the **prior assistant draft verbatim** into a new post item. You are **persisting**, not **editing**.

**Path:**

- **Type:** `/component/post`
- **New file:** `/site/components/post/{year}/{month}/{slug}.xml`
- **`file-name`:** `{slug}.xml` (must end with `.xml`)
- **Slug:** kebab-case of the **exact** `*Draft title:*` text (mechanical transform only)

**Copy source:** **[Prior conversation]** markers only — not the open preview item, not other repo files.

### Verbatim copy (non-negotiable)

| From | Into XML |
|------|----------|
| `*Draft title:*` (trim outer whitespace only) | `internal-name`, `headline_s`, `pageTitle_s`; inline rich_text `internal-name` = `{title} - Content` |
| `*Draft blog:*` (every paragraph, full length) | Inline **`content_html`** — one `&lt;p&gt;…&lt;/p&gt;` per paragraph; **XML-escape** only (`<`, `>`, `&`); **no** CDATA |
| First sentence of draft body | `pageDescription_s` (≤250 chars), `blurb_t` (≤150 chars) |

**Forbidden on Turn 2:** summarize, shorten, rephrase, merge paragraphs, or stop after a few paragraphs.

### Turn 2 tool flow

1. Use recipe **prefetch** (`postForm`, `richTextForm`, `taxonomyTopics`, `taxonomyTags`, `authorBios`, `suggestedNewItemPath`) — do not re-fetch form defs unless prefetch is missing.
2. **ContentExists** on chosen path (expect **false** for new item).
3. **WriteContent** once with full **contentXml**. If validation fails, fix listed errors and retry — do not guess missing fields.

### Pre-write checklist

| Check | Rule |
|-------|------|
| Title fields | `internal-name`, `headline_s`, `pageTitle_s` = verbatim `*Draft title:*` |
| Body | Full `*Draft blog:*` in inline `content_html` under `content_o` |
| Path = file-name | Same `{slug}.xml` in **contentPath** and root `<file-name>` |
| UUIDs | **Two** distinct v4 UUIDs — post (A) and inline rich_text (B); never reuse one ID |
| `authorBio_o` | One item; `<key>` = `<include>` = real path from prefetch **`authorBios`** (UUID `.xml` filename) matching **Author voice:** |
| `categories_o` / `tags_o` | Keys from prefetch **`taxonomyTopics`** / **`taxonomyTags`** only; strong thematic fit; `<value_smv>` labels (not `value_s`); omit if nothing fits |
| `mainImage_s` | Empty or omit — placeholder handled on write |
| Dates | ISO-8601 `createdDate*` / `lastModifiedDate*` on post and inline component |

---

## `/component/post` XML essentials (Turn 2)

Body lives in **`content_o`** as **one inline** `/component/rich_text` — not bare `content_html` under `content_o`.

```xml
<content_o item-list="true">
  <item datasource="richTextSections" inline="true">
    <key>{UUID-B}</key>
    <value>{title} - Content</value>
    <component id="{UUID-B}">
      <!-- /component/rich_text: objectId B, content_html with escaped &lt;p&gt;… -->
    </component>
  </item>
</content_o>
```

**Critical shape rules:**

- `datasource` and `inline` are **attributes on `<item>`**, not child elements.
- `<component id="…">` is **inside** `<item>`, not a sibling.
- Post root order: `content-type`, `display-template`, `no-template-required`, `merge-strategy`, `objectGroupId`, `objectId`, `file-name`, `folder-name`, `content_o`, `authorBio_o`, `categories_o`, `tags_o`, `mainImage_s`, `blurb_t`, `internal-name`, `pageTitle_s`, `pageDescription_s`, `headline_s`, dates.

**Forbidden:** `<content_o><rich_text>…`, CDATA for `content_html`, one UUID for both objects, invented bio paths (`unknown_bio.xml`, `sara.xml`), taxonomy keys not in prefetch, weak tag padding (`aws`, `open-source` on unrelated articles).

---

## Intent hints (routing)

| Author says | Expected path |
|-------------|----------------|
| “Draft / summarize from this URL” + topics | Turn 1 — `recipe_1779227205002` or plan with FetchHttpUrl + markdown draft |
| “Make a post from this draft” / “save this” (with prior `*Draft blog:*`) | Turn 2 — `new_content_item_from_chat_draft` |

When Turn 1 markers are missing, **never** jump to Turn 2 XML work — redo the chat draft first.
