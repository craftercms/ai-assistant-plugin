[Studio — DCO: create **/component/post** from prior chat draft — fast path]

**Prefetch (done):** `postForm`, `richTextForm`, `taxonomyTopics`, `taxonomyTags`, **`authorBioPaths`** (and `authorBios` if any), sibling XML, **suggestedNewItemPath**, **ContentExists** on that path.

**Mandatory before WriteContent (see Project authoring context checklist):** verbatim `*Draft title:*` / `*Draft blog:*`; **`pageDescription_s`** + **`blurb_t`** from first draft sentence; **`authorBio_o`** = real `.xml` path from prefetch matching **Author voice:** (never `unknown_bio.xml`); taxonomy keys with strong fit only (no `aws`/`open-source` on a Google I/O AI article unless the draft says so).

**Path rule:** `contentPath` and `<file-name>` must use the **same** slug (same basename). A suffix like `-v2` is fine when **both** use it (e.g. path `…/title-v2.xml` and `<file-name>title-v2.xml</file-name>`). **Forbidden:** path `…/title.xml` with `<file-name>title-v2.xml</file-name>` (or the reverse). If suggested path **ContentExists** is **true** and you want a **new** item, choose a new slug where **ContentExists** is **false** and use it in path and `<file-name>`.

**Tools:** **ContentExists** → **WriteContent** once only. **No** GetContentTypeFormDefinition, ResearchSiteContent, or ListStudioContentTypes in the tools loop (prefetch already ran them).

