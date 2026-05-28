# AI Assistant — Admins & Authors

**Start here** if you install the plugin on a Crafter site and want authors to use it **without** changing plugin source code.

Most sites only need: **install** → **Project Tools → AI Assistant** → **`ui.xml` / form wiring** → **API keys** → authors open chat on preview or content forms.

---

## Quick start (admins)

| Step | Document |
|------|----------|
| 1. Install the plugin on your site | [Installation](../using-and-extending/installation.md) |
| 2. Configure agents, secrets, UI flags, recipes | [Configuration guide](../using-and-extending/configuration-guide.md) (**§1–§7** basic, **§9** advanced) |
| 3. See Project Tools screens | [Screenshots](../using-and-extending/configuration-guide.md#cg-screenshots) |
| 4. Wire LLM keys and providers | [LLM configuration](../using-and-extending/llm-configuration.md) |
| 5. Run the pre-support checklist | [Configuration guide §7](../using-and-extending/configuration-guide.md#cg-7) |

**Visual overview:** [Architecture diagrams — admin setup](../architecture-diagrams.md#administrator-setup-workflow) · [Author surfaces](../architecture-diagrams.md#author-experience-user)

---

## Authors (using the assistant)

You do **not** need this whole doc set. After an admin enables the plugin:

| Where you work | What you get |
|----------------|--------------|
| **Experience Builder / preview** | Toolbar or ICE panel chat ([Helper widget](../using-and-extending/helper-widget.md)) |
| **Content type forms** | **Studio AI assistant** accordion beside fields ([form control](../using-and-extending/configuration-guide.md#cg-5)) |
| **Autonomous** (if enabled, experimental) | Sidebar status and scheduled agents ([overview](../using-and-extending/autonomous-assistants-widget.md)) |

Ask your admin if chat is missing—they control `ui.xml`, **Agents**, and **Secrets** in **Project Tools → AI Assistant**.

---

## Admin guides (site configuration)

| Topic | Document |
|-------|----------|
| **Main reference** — `ui.xml`, `agents.json`, Project Tools, secrets, forms, recipes, MCP, tools | [Configuration guide](../using-and-extending/configuration-guide.md) |
| Install (Studio UI, CLI, `install-plugin.sh`) | [Installation](../using-and-extending/installation.md) |
| Preview toolbar / Tools Panel Helper | [Helper widget](../using-and-extending/helper-widget.md) |
| Autonomous sidebar widget | [Autonomous assistants widget](../using-and-extending/autonomous-assistants-widget.md) |
| `<llm>` providers, env, merge rules | [LLM configuration](../using-and-extending/llm-configuration.md) |
| Script-backed LLM (`script:{id}`) | [Script LLM — BYO backend](../using-and-extending/script-llm-bring-your-own-backend.md) |
| Image generation backends | [Image generation](../using-and-extending/image-generation.md) |
| Custom intent recipes (Project Tools **Recipes** tab) | [Building intent recipes](../using-and-extending/building-intent-recipes.md) |
| Site Groovy tools & `imagegen/` (optional) | [Scripted tools & imagegen](../using-and-extending/scripted-tools-and-imagegen.md) |
| JVM tuning on the Studio host (`-D` flags) | [Studio AI assistant JVM parameters](../using-and-extending/studio-aiassistant-jvm-parameters.md) |
| Product obligations (review) | [Product requirements](../using-and-extending/product-requirements.md) |

**Examples** (copy into your site sandbox): [`docs/examples/`](../examples/)

---

## Topic finder

| I need to… | Open |
|------------|------|
| Install from Marketplace or repo | [installation.md](../using-and-extending/installation.md) |
| Turn on toolbar / sidebar / form AI | [configuration-guide §1](../using-and-extending/configuration-guide.md#cg-1) · [§1e studio-ui.json](../using-and-extending/configuration-guide.md#cg-1e) |
| Add or edit chat agents | [configuration-guide §3](../using-and-extending/configuration-guide.md#cg-3) |
| Store API keys safely | [configuration-guide §4](../using-and-extending/configuration-guide.md#cg-4) |
| Add AI Assistant field to content types | [configuration-guide §5](../using-and-extending/configuration-guide.md#cg-5) |
| Tune recipes, tools, MCP | [configuration-guide §9](../using-and-extending/configuration-guide.md#cg-adv) |
| Fix “component not found” | [helper-widget.md](../using-and-extending/helper-widget.md) |
| Configure SerpApi web search | [configuration-guide §9.2](../using-and-extending/configuration-guide.md#cg-9-2) |

---

## Not for this audience

Building or patching the **plugin bundle**, debugging Groovy orchestration, or changing Rollup output → **[Developers documentation](../developers/README.md)**.
