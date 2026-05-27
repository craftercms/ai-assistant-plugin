# Project authoring context (DCO)

Appended to every chat turn for this site. Stable facts for **`/component/post`** and **create post from prior chat draft**.

## Path and copy source

- **Type:** `/component/post`
- **New file:** `/site/components/post/{year}/{month}/{slug}.xml`
- **`file-name`:** `{slug}.xml` (must end with `.xml`)
- **Slug:** kebab-case of the **exact** `*Draft title:*` string (mechanical transform only — no rewording)
- **Turn 1:** `*Draft blog:*` under **## Draft body** (site recipe `recipe_1779227205002`)
- **Turn 2:** Title and full article from **[Prior conversation]** only — not the open preview item, not text from any other repository file

## Verbatim copy (Turn 2 — non-negotiable)

You are **persisting** the prior chat draft, not **editing** it.

**From `*Draft title:*` (character-for-character after the marker, trim outer whitespace only):**

- `internal-name`, `headline_s`, `pageTitle_s` — **exact title text**
- Inline `<value>` / rich_text `internal-name` — `{exact title} - Content`
- **Slug / `file-name`:** kebab-case of that **same** title only (no synonym titles)

**From `*Draft blog:*` (every paragraph, full length):**

- Inline **`content_html`** — **all** paragraphs from the draft, **verbatim**
- Split into `&lt;p&gt;…&lt;/p&gt;` per draft paragraph; **XML-escape only** (`<`, `>`, `&`)
- **Forbidden:** summarize, shorten, “improve”, rephrase, add/remove sentences, merge paragraphs, invent intros/outros, or stop after a few paragraphs

**SEO fields:**

- `pageDescription_s` — **required:** HTML meta description. Use the **first sentence** of the draft article body (plain text, max **250** characters). Do not invent a different marketing angle.
- `blurb_t` — **required:** listing blurb (max **150** chars). Use the **first sentence** of `*Draft blog:*` (plain text, same source as `pageDescription_s` but trim to 150). If the draft included an explicit blurb line, use that text instead.
- **Forbidden:** unrelated teasers or descriptions that are not grounded in the draft opening

## Turn 2 — WriteContent checklist (one shot)

Before **WriteContent**, every item below must be true. **Write verification** rejects incomplete XML; fix and retry only when the server lists errors.

| Check | Rule |
|-------|------|
| Path = `file-name` | **Same basename everywhere:** if **contentPath** is `…/{slug}.xml`, root `<file-name>` must be `{slug}.xml` (e.g. both `…-v2.xml` or both without `-v2` — never mix). |
| Path exists | **ContentExists** **false** = new item at that path (slug in path and `<file-name>` match). **true** = overwrite that item — use that path and the same slug in `<file-name>`. For a **second** item, pick a new slug (e.g. `-v2`) and use it in **both** path and `<file-name>`. |
| Title fields | `internal-name`, `headline_s`, `pageTitle_s` = verbatim `*Draft title:*` |
| Body | Full `*Draft blog:*` in inline `content_html` (`&lt;p&gt;…&lt;/p&gt;`, escaped) |
| `pageDescription_s` | First sentence of draft body (≤250 chars) |
| `blurb_t` | First sentence of draft body (≤150 chars) or draft blurb if present |
| `authorBio_o` | One item; `<key>` = `<include>` = real path from prefetch **`authorBioPaths`** / **`authorBios`** (ends `.xml`) |
| Author voice | Match **`Author voice:`** in prior draft to bio display name (e.g. **Sara** → **Sarah Miller** → `/site/components/bio/29217b50-23b3-92e8-a30a-fe0778dbc6f5.xml`) |
| Topics / tags | Keys from **`topics.xml`** / **`tags.xml`** only; **strong** thematic fit (~90%+). No `aws`, `open-source`, `webdev` unless the draft is mainly about those. |
| UUIDs | Two distinct UUIDs (post + inline rich_text) |
| Dates | Post + inline `createdDate*` / `lastModifiedDate*` at end of each component |

If **[Prior conversation]** has no complete `*Draft blog:*` or `*Draft title:*`, **do not WriteContent** — ask the author to redo Turn 1.

## IDs (two objects — never reuse one UUID)

Generate **two** real UUIDs (v4, e.g. `c9f4a7d6-f8d7-4be5-a1d8-e1a4a90bfb5e`) — **not** placeholders like `uuid-a` / `uuid-b`:

| Object | `objectId` | `objectGroupId` |
|--------|------------|-----------------|
| Post (root) | UUID **A** | first 4 hex chars of **A** (e.g. `c9f4`) |
| Inline `rich_text` inside `content_o` | UUID **B** | first 4 hex chars of **B** |

**Forbidden:** post `objectId` === inline `objectId`, shared `objectGroupId`, or literal strings `uuid-a` / `uuid-b`.

Inline only: `<key>`, `<component id="…">`, rich_text `objectId`, and rich_text `file-name` all use **UUID B** (with `file-name` = `{uuid-b}.xml`).

## Root `<component>` — element order

1. `content-type` → `/component/post`
2. `display-template` → empty
3. `no-template-required` → `true`
4. `merge-strategy` → `inherit-levels`
5. `objectGroupId`, `objectId` (**UUID A**)
6. `file-name` → `{slug}.xml`
7. `folder-name` → empty
8. **`content_o`** (body — below)
9. **`authorBio_o`**
10. **`categories_o`**, **`tags_o`**
11. **`mainImage_s`** — omit or empty (recipe: placeholder on write)
12. `blurb_t`, `internal-name`, `pageTitle_s`, `pageDescription_s`
13. `headline_s` with `tokenized="true"`
14. Post-level `createdDate`, `createdDate_dt`, `lastModifiedDate`, `lastModifiedDate_dt` (ISO-8601, required)

## Body: `content_o` (required shape)

`content_o` is a node-selector collection with **one** inline rich text block.

```xml
<content_o item-list="true">
  <item datasource="richTextSections" inline="true">
    <key>{UUID-B}</key>
    <value>{*Draft title:*} - Content</value>
    <component id="{UUID-B}">
      … /component/rich_text fields …
    </component>
  </item>
</content_o>
```

**Required**

- `item-list="true"` on `content_o`
- **`datasource` and `inline` are attributes on `<item>`** — not child elements `<datasource>` / `<inline>`
- **`<component id="…">` is a child of `<item>`**, before `</item>` — not a sibling of `<item>` under `content_o`

**Inside `<component>`** (`/component/rich_text`):

| Element | Value |
|--------|--------|
| `content-type` | `/component/rich_text` |
| `display-template` | `/templates/web/components/rich_text.ftl` |
| `no-template-required` | empty |
| `merge-strategy` | `inherit-levels` |
| `objectGroupId` | 4-char from **UUID B** |
| `objectId` | **UUID B** |
| `internal-name` | `{title} - Content` |
| `file-name` | `{UUID-B}.xml` |
| `folder-name` | empty |
| **`content_html`** | Full `*Draft blog:*`: `&lt;p&gt;…&lt;/p&gt;` per paragraph — **escape** HTML in XML; **no** `<![CDATA[…]]>` |
| dates | `createdDate`, `createdDate_dt`, `lastModifiedDate`, `lastModifiedDate_dt` on inline component |

## `authorBio_o`

`item-list="true"`. One referenced bio (not inline):

```xml
<authorBio_o item-list="true">
  <item>
    <key>/site/components/bio/{uuid}.xml</key>
    <value>{bio-slug-or-internal-name}</value>
    <include>/site/components/bio/{uuid}.xml</include>
    <disableFlattening>false</disableFlattening>
  </item>
</authorBio_o>
```

- `<key>` and `<include>` must be the **same full repository path** ending in **`.xml`**
- Path must exist — use prefetch **`authorBios`** (paths under `/site/components/bio/`); **never** placeholders like `{some-bio-uuid}.xml`, `{author-bio-name}`, or invented slugs such as **`unknown_bio.xml`**
- Match **`Author voice:`** in the prior draft to a prefetch row’s **`displayName`** (Studio **internal-name** from search/catalog — e.g. voice **Sara** → **Sarah Miller**). Copy that row’s full `.xml` path into `<key>` and `<include>` (identical values).
- Bio files use **UUID** file names (`/site/components/bio/{uuid}.xml`) — **never** invent readable slugs like `sara.xml`.
- **Forbidden:** `unknown_bio.xml`, `{uuid}` placeholders, or any path not listed in prefetch **`nodeSelectorCandidates`**

## Taxonomy: `categories_o` (Topics), `tags_o`

**Mandatory before WriteContent** — read the site taxonomy files (do not invent keys):

| Field | Form label | Taxonomy file | Tool |
|-------|------------|---------------|------|
| `categories_o` | Topics | `/site/taxonomy/topics.xml` | **GetContent** (preferred) or **ResearchSiteContent** |
| `tags_o` | Tags | `/site/taxonomy/tags.xml` | **GetContent** (preferred) or **ResearchSiteContent** |

Each taxonomy file is a `/taxonomy` item with `<items item-list="true">`. Every allowed entry has `<key>…</key>` and `<value>…</value>` (display label).

**On `/component/post` items**, each selected topic/tag is:

```xml
<item>
  <key>ai-ml</key>
  <value_smv>AI/ML</value_smv>
</item>
```

Rules:

- **`<key>`** must **exactly** match a `<key>` from the correct taxonomy file (`topics.xml` for `categories_o`, `tags.xml` for `tags_o`).
- **`<value_smv>`** must **exactly** match that entry’s `<value>` in the same taxonomy file — **not** `value_s`.
- **`categories_o`:** form **minSize** is **1** — pick topic key(s) from **`topics.xml`** only when the topic is a **strong** match to the draft title + body (roughly **90%+** thematic fit). Example: a Google I/O AI article → `ai-ml`; **not** `webdev` or `aws` unless the draft is mainly about those.
- **`tags_o`:** pick **2–5** tags from **`tags.xml`** with the same **strong-fit** rule. **Do not** pad with weak tags just to meet a minimum — fewer correct tags beats unrelated keys (`open-source`, `aws`, etc. when the article is about AI agents).
- **Forbidden:** using a topic key (e.g. `DevContentOps`) in **`tags_o`**, or any key that does not appear in the file you read.
- Draft **`*Outline:*`** bullets are hints only — **keys** still come from the taxonomy XML.

## Forbidden (invalid on this site)

- `<content_o><rich_text>…` or bare `content_html` under `content_o`
- `<item>` with child `<datasource>` / `<inline>` instead of attributes
- `<component>` as sibling of `<item>` (outside `</item>`)
- One UUID for both post and inline rich_text
- `value_s` in categories/tags
- Taxonomy `<key>` not listed in `topics.xml` / `tags.xml` after **GetContent**
- Bio path without `.xml`, invented paths (`unknown_bio.xml`), or without a matching **`authorBios`** prefetch hit
- Weak taxonomy tags to “fill the list” (`aws`, `open-source`, `webdev` on an AI/Google I/O article when the draft is not about those topics)
- CDATA for `content_html` when escaped `&lt;p&gt;` is required
- Missing post-level date fields at end of root `<component>`
- `file-name` without `.xml`

Image-picker: intent recipe **`new_content_item_from_chat_draft`**.
