---
name: building-intent-recipes
description: >-
  Authors and extends Crafter Studio AI Assistant intent recipes in
  intent-recipes.json — match rules, tools-loop policy, Action ## sections,
  Confirmation llmRefine JSON, passthrough/fallback, threaded SlackPostMessage.
  Use when the user asks to build, edit, or debug intent recipes, custom workflows,
  recipe routing, confirmation refine, or multi-post Slack confirmation flows.
---

# Building intent recipes (Crafter Studio AI Assistant)

Use this skill when creating or changing **site** intent recipes (`intent-recipes.json`) or explaining recipe capabilities to admins/integrators.

**Not in this repo:** Site catalog lives at `config/studio/scripts/aiassistant/intent-recipes.json` on each Crafter site. Edit via **Project Tools → AI Assistant → Recipes** or that file in the site sandbox.

**Related (read when changing runtime behavior):**

- Admin overview: `docs/using-and-extending/configuration-guide.md` §9.0
- Pipeline: `docs/internals/intent-recipe-routing.md`
- Bundled defaults: `authoring/scripts/classes/plugins/org/craftercms/aiassistant/recipes/authoring-intent-recipes-default.json`
- Full PM/Slack example JSON: [examples.md](examples.md) in this skill folder

**Repo plugin skill:** `.cursor/skills/ai-assistant-studio-plugin/SKILL.md` — form panel, `yarn package`, no unauthorized UI.

---

## What you are building

An **intent recipe** specializes **one chat turn** when the author’s **current** message matches. Studio then:

1. Optional **prefetch** (`context` / `action` `engineSteps`) — read-only tools, `as` bindings
2. **Recipe prelude** — phase hints + `matchedUserPrelude`
3. **Tools loop** — allowlist, `toolsLoopForceTool`, exclusions, fetch caps
4. **Confirmation** (optional) — JVM `llmRefine` + tools (e.g. **SlackPostMessage**) after the model stops calling tools

Recipes do **not** replace `agents.json` or global `tools.json`; they layer on top for a matched intent.

---

## Catalog shape

```json
{
  "version": 1,
  "recipes": [ { "id": "...", "phases": { ... } } ],
  "recipeOrder": [ "bundled_id", "your_custom_id" ]
}
```

| Field | Use |
|-------|-----|
| `id`, `title`, `description`, `chatEmoji` | Identity + UI |
| `matchHints` / `dontMatchHints` | Deterministic routing phrases |
| `deterministicMatch` | `priority`, `routerReason`, `authorFromMatchHints`, `respectDontMatchHints` |
| `matchedUserPrelude` | Numbered Studio instructions when matched |
| `phases.context` / `action` / `confirmation` | String[] hints **or** `{ "hints": [], "engineSteps": [] }` |
| `toolsLoopForceTool` | Round 0 must call this wire (e.g. `SerpApiWebSearch`) |
| `toolsLoopAllowlist` / `toolsLoopExcludeTools` | Loop tool policy |
| `toolsLoopMaxFetchHttpUrlCalls` / `toolsLoopFetchHttpUrlWireMaxChars` | Fetch caps |
| `toolsLoopAuthorUrlExclusive` | When the author pastes http(s) URL(s): **FetchHttpUrl** only those links (no Serp/WebSearch, no other fetches); when no URL, normal `toolsLoopForceTool` applies |
| `toolsLoopPrefetchSupplement` | JVM prefetch id on a **deterministicMatch** entry (e.g. `createFromChatDraft` — see `AuthoringIntentRecipeEngine`); not hardcoded in orchestration |
| `when` leaf `authorProvidedHttpUrl` | Author-visible text includes at least one `http(s)` URL (pairs with `toolsLoopAuthorUrlExclusive`) |
| `toolsLoopRequireSuccessfulTools` | Wire names that must succeed before the tools loop may finish (e.g. `["WriteContent"]`); prose-only “fake” tool lists are rejected |
| `toolsLoopDisable` | Chat-only (no `tool_calls`) |
| `toolsLoopWriteVerification` | Extension id (e.g. `createFromChatDraft`) — handled by `ToolsLoopWriteVerification` |
| `writeVerification` | **Site declares all field ids** — plugin does not assume content-type shape |

**`writeVerification` (site `intent-recipes.json` only — never hardcode field ids in plugin Groovy):**

| Key | Purpose |
|-----|---------|
| `repairRootObjectIds` / `requireValidRootObjectIds` | UUID v4 + objectGroupId on root |
| `inlineComponent.collectionFieldId` | Node-selector holding inline `<component>` (e.g. DCO `content_o`) |
| `repairInlineObjectIds` / `requireDistinctInlineObjectIds` | Second UUID for inline item |
| `dateFieldIds` + `requireRootDates` / `repairRootDates` | Which date elements must exist on root |
| `bodyTextFieldId` + `minBodyTextChars` | Min plain-text length on inline (or root) RTE field |
| `requiredRootFields` | Root elements that must be non-empty before write |
| `deriveRootFieldsFromBody` | `{ "fieldId", "bodyTextFieldId", "maxLength", "strategy": "firstSentence" }` — server fill when empty |
| `nodeSelectorFields` | `{ "fieldId", "minItems", "requireExistingPath", "forbiddenPathSubstrings" }` |

**Topics / tags:** Prefetch taxonomy XML in `engineSteps` (e.g. `GetContent` → `taxonomyTopics` / `taxonomyTags`). The LLM picks keys that match the draft; **do not** use `writeVerification` to enforce counts, allowed keys, or thematic overlap — empty taxonomy fields are OK.

Field ids in `writeVerification` are **site-declared**; plugin code must not default to a specific content type’s element names.

**Routing:** `config/studio/scripts/aiassistant/config/tools.json` → `intentRecipeRouting` (`enabled`, `customRecipesPath`, `confirmationLlmRefineEnabled`).

---

## Phase mechanics

### Context / Action

- **Hints only:** strings in prelude (supports `{{studio.today}}`, `{{studio.today-7D}}`, `{{current.asName.field}}`).
- **`engineSteps`:** prefetch tools, e.g. `{ "tool": "GetContent", "args": { ... }, "as": "bindingName" }`.

### Action markdown contract (multi-post Slack pattern)

Require explicit **`##` headings** the Confirmation step will consume:

- **`## Work notes`** — process, overlap, rejected angles (short)
- **`## Draft body`** — long deliverable copied **verbatim** to one Slack post

Final Action message: **no `## Plan`**, no fenced orchestration JSON, **no `tool_calls`** when Confirmation owns Slack.

### Confirmation `engineSteps`

| Row | Purpose |
|-----|---------|
| `{ "tool": "WireName", "args": { ... }, "as": "optional" }` | `$binding.key`, `$stepN.field`, `$slackRoot.ts` for threads |
| `{ "llmRefine": "profileId", "as": "slackOutbound", ... }` | Post-action LLM; bind JSON for `$slackOutbound.*` |

**Exclude `SlackPostMessage` from the tools loop**; post only in Confirmation to avoid duplicates.

---

## Capability menu (combine as needed)

### 1. Deterministic match

```json
"deterministicMatch": {
  "priority": 90,
  "routerReason": "deterministic_your_recipe",
  "authorFromMatchHints": true,
  "respectDontMatchHints": true
}
```

Use `dontMatchHints` for adjacent intents (“publish site”, “create a page”, “three options”).

### 2. Tools-loop choreography

| Pattern | Fields |
|---------|--------|
| Research-first | `toolsLoopForceTool`: `SerpApiWebSearch` + allowlist fetch |
| Site overlap | `ResearchSiteContent` in allowlist + `pathPrefix` in hints |
| No writes | Exclude `WriteContent`, `publish_content`, etc. |
| Slack after chat | Exclude `SlackPostMessage` from loop; Confirmation steps post |

Secrets: `secrets.json` (`serpapi_api_key`, `slack_bot_token`). Defaults: `tools.json` → `builtInToolSettings`.

### 3. ConsultCrafterQ

Plugin built-in **`ConsultCrafterQ`**: public **`agentId`** + **`prompt`**, optional **`draft`**. The plugin mints **`X-CrafterQ-Chat-User`** via **`GET /v1/agents/{agentId}/chat_config`** (same flow as [embed.js](https://chat.crafterq.ai/embed.js)).

**Bindings on the step `as` name** (e.g. **`devContentOpsConsult`**):

| Key | Use |
|-----|-----|
| **`answer`** | Raw CrafterQ reply (for **`userPreamble`** in a follow-up **`llmRefine`**) |
| **`feedbackMarkdown`** | **`## CrafterQ feedback`** section (also shown in Studio chat after confirmation) |
| **`feedbackSlack`** | Mrkdwn thread body — post with **`SlackPostMessage`** (`text`: **`$yourAs.feedbackSlack`**, **`threadTs`**: **`$slackRoot.ts`**) |

Later **`llmRefine`** may embed **`$yourAs.answer`** in **`userPreamble`** when revising outbound JSON.

### 4. JSON refine + passthrough (long draft + short Slack fields)

```json
{
  "llmRefine": "yourProfileId",
  "as": "slackOutbound",
  "outputFormat": "json",
  "outputKeys": ["root", "workflowAlignment", "draft", "pitch", "sources"],
  "passthroughFromSource": {
    "draft": ["Draft body", "Draft brief", "Pitch draft"]
  },
  "passthroughFallbackMaxOutTokens": { "draft": 8192 },
  "passthroughFallbackHints": { "draft": ["...full draft instructions..."] },
  "userPreamble": "Produce root, workflowAlignment, pitch, sources only. Today {{studio.today}}.\n\n",
  "hints": ["root: ...", "pitch: ..."]
}
```

- Keys in **`passthroughFromSource`** are **excluded** from the main JSON completion (avoids token starvation).
- **`passthroughFallbackHints`** runs draft-only completion if Action omitted the section.
- Thread replies: `"threadTs": "$slackRoot.ts"` after root `{ "tool": "SlackPostMessage", "as": "slackRoot", ... }`.
- Each Slack step needs explicit **`text`** (e.g. `"$slackOutbound.pitch"`).

### 5. Chat-only

`toolsLoopDisable: true` — hints only, no tools.

### 6. Markdown refine (single block)

`outputFormat: "markdown"` + optional `markdownSection: "Slack message"`.

---

## Design patterns (production multi-post Slack)

| Pattern | Why |
|---------|-----|
| Work notes vs draft body | Process vs deliverable; draft passthrough at full length |
| Cap Serp + fetch | Focused queries; prevent runaway rounds |
| Site search before finalize | Differentiate vs existing items under a configurable `pathPrefix` |
| Voice roster (pick one) | Consistent tone across root/draft/pitch |
| Internal alignment JSON key | Editor-only thread (e.g. `workflowAlignment`) |
| Third rails in Context | Bans stated once (e.g. no vendor launch as spine) |
| Confirmation owns Slack | No model `SlackPostMessage` in `tool_calls` |

Domain example in [examples.md](examples.md): **project management** initiative brief → root + four thread replies (not content-management/blog).

---

## Agent workflow when asked to add a recipe

1. **Clarify intent** — match phrases, tools needed, outbound channel (Slack vs chat-only), write vs read-only.
2. **Read site catalog** if path is available; else scaffold from [examples.md](examples.md).
3. **Set routing** — `matchHints`, `dontMatchHints`, `deterministicMatch.priority` vs bundled recipes.
4. **Tools loop** — force tool, allowlist, exclude writes + outbound tools deferred to Confirmation.
5. **Action** — require the same `##` titles as `passthroughFromSource` keys.
6. **Confirmation** — `outputKeys` align with `$slackOutbound.*`; one root + N thread `SlackPostMessage` rows with explicit `text`.
7. **Append `recipeOrder`** with custom `id`.
8. **No project-specific hardcoding in plugin Groovy** — paths/voices/field ids belong in **site** JSON only (see `.cursor/rules/no-project-specific-content.mdc`).
9. If plugin refine/passthrough behavior changes, update `docs/internals/intent-recipe-routing.md` and `spec.md`.

---

## Go-live checklist

- [ ] `matchHints` / `dontMatchHints` match real author phrasing
- [ ] Loop excludes tools Confirmation runs (especially `SlackPostMessage`)
- [ ] Action requires `##` sections used by passthrough/refine
- [ ] `outputKeys` ↔ `$slackOutbound.*` on every Slack step
- [ ] `passthroughFromSource` headings match Action section titles exactly
- [ ] `passthroughFallbackHints` if draft must not be shortened when missing
- [ ] Secrets + `builtInToolSettings` for search/Slack
- [ ] `intentRecipeRouting.enabled` and site file committed to sandbox git

---

## Troubleshooting

| Symptom | Check |
|---------|--------|
| Never matches | `intentRecipeRouting.enabled`, hints, competing recipes, `dontMatchHints` |
| No Slack | Confirmation `engineSteps`, refine not disabled globally |
| Empty draft post | Missing `## Draft body` in Action; `passthroughFallbackHints`; plugin passthrough support |
| Duplicate Slack | Model calling `SlackPostMessage` in loop — strengthen prelude + exclusions |
| Truncated pitch/sources | Too many keys in one JSON — passthrough long `draft` |

Telemetry: SSE `intent-recipe-routing`, session TIMELINE debug log.
