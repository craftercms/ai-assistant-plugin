# Project context examples

Per-site project context lives at:

`config/studio/scripts/aiassistant/context/site-authoring.md`

**Do not copy a sample from another site.** Content here is appended to every orchestration turn when non-empty (`SiteProjectContext`).

| Site | Example |
|------|---------|
| DCO (blog post from chat draft) | [`sites/dco/context/site-authoring.md`](../sites/dco/context/site-authoring.md) |

Plugin install (`scripts/install-plugin.sh`) does **not** seed this file. Authors add it via **Project Tools → Context and Prompts** or copy from a site-specific example that matches their content model.
