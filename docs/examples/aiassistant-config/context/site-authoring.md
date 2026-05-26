# Project authoring context

This markdown is appended to every AI Assistant chat turn for this site when non-empty. It is **not** the author's request — use it for stable site facts: content-type paths, folder conventions, naming rules, and workflows.

## Content conventions (example — replace for your project)

- **Blog posts** use content type `/component/post`. Store items under `/site/components/post/{year}/{month}/{title-as-filename}.xml` (kebab-case filename from the post title).
- **Body copy** for a post lives in the post component's rich-text field; pages reference posts via node selectors — do not invent a different root element or field ids when creating posts from chat drafts.
- When the author asks to **create a post from a prior chat draft**, use the draft prose from the conversation (sections such as `## Draft body`), not the currently open preview page, unless they name another target. Clone XML structure from an existing sibling post via `GetContent` before `WriteContent`.
- On **create**, populate every **required** field from **GetContentTypeFormDefinition** (SEO, taxonomy, etc.). **Do not** call **GenerateImage** unless the author asked for generated art. For **required** image-picker fields, **omit or leave empty** in **WriteContent** — the server applies Studio’s XB-style **`data:image/png;base64,…`** placeholder automatically (same as Experience Builder); do not invent `/static-assets/…` paths.
- When a recipe sets **`toolsLoopAuthorUrlExclusive`**: if the author pastes http(s) URL(s), fetch **only** those URLs (no open-web search, no other fetches). The bundled recipe **`draft_content_from_source`** matches turns like “draft a blog … from this: https://…”; override that recipe id in site `intent-recipes.json` to add Slack/confirmation steps.
