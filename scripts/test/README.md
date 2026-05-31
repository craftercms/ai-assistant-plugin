# `scripts/test/` — **testing harness only**

> **This folder is not part of the Studio plugin install.**  
> It exists so integration checks are **obviously separate** from `authoring/` (runtime Groovy) and `sources/` (UI bundle). See **`NOT_SHIPPED_WITH_PLUGIN`** in this directory and **`scripts/README.md`** one level up.

What lives here:

- **`run-all.sh`** — **Single entrypoint:** `bash -n` + offline checks, **`yarn package`**, live **`functional/rest-contracts.sh`**, **chat scenarios** (step 4), optional **tool/recipe matrix** (step 5), optional **concurrent sessions** (step 6). Skip live Studio with **`RUN_ALL_SKIP_STUDIO=1`**. See **Multi-LLM smoke** below for **`CHAT_LLM=claude`** / **`xAI`**.
- **`functional/rest-contracts.sh`** — **Plugin REST contracts:** JWT preflight, validates **`content-types/list`** and **`scripts/index`** payloads (unwraps Studio’s **`{ "result": { … } }`** envelope like `unwrapPluginScriptBody` in `sources/src/aiAssistant*Api.ts`), plus **`ai/stream`** invalid JSON → HTTP 400. Uses **`node`** for JSON when available (else **`python3`** / **`jq`**). **`REST_CONTRACTS_SELFTEST=1`** runs unwrap + field checks only (no Studio); **`run-all.sh`** runs that after `bash -n` in step 1. Default site **`aiat-2`** (`INTEGRATION_SITE_ID`). Helpers in **`integration/include/`** (stream probe URL-encodes `siteId` with **node** then **python3**).
- **`integration/e2e-site-lifecycle.sh`** — Optional disposable site: create → `install-plugin.sh` → **`functional/rest-contracts.sh`** → delete.
- **`integration/create-site.json.example`** — Template for marketplace create-site body (`create-site.json` is gitignored).
- **`scenarios/chat-scenarios.example.json`** — Default scripted turns for **`run-all.sh`** step 4 / **`run-chat-scenarios.mjs`**. **`agentId`** defaults to empty (match a chat row in **`config/studio/ai-assistant/agents.json`**); override with **`CHAT_AGENT_ID`**. Copy to **`chat-scenarios.json`** to customize paths per site (gitignored).

**No services are installed into Studio from this tree** — only `curl`/bash against your existing authoring URL. If we add optional local test stacks (Docker, mock LLM, etc.), they will live under `scripts/test/` with a dedicated README and still **not** ship in the plugin artifact unless explicitly called out in product docs.

See **`docs/using-and-extending/studio-plugins-guide.md`** for JWT + scripted Studio API examples.

## Run everything you have today

**Preferred — one command** (from repo root):

```bash
./scripts/test/run-all.sh
```

Runs: **`bash -n`**, offline parity (**`tool-id-parity`**, recipe catalog, scenario drift), **`REST_CONTRACTS_SELFTEST=1`**, **`yarn package`**, live **`rest-contracts.sh`**, then **by default** **`run-chat-scenarios.mjs`** (four **`ai/stream`** turns unless overridden). **Steps 5–6** are optional (matrix / concurrent). **ESLint:** `RUN_ALL_WITH_LINT=1`. **Skip live Studio + chat:** `RUN_ALL_SKIP_STUDIO=1`. **Skip only chat:** `RUN_ALL_SKIP_CHAT_SCENARIOS=1`. **Skip matrix:** `RUN_ALL_SKIP_TOOL_RECIPE_MATRIX=1` (default when **`CHAT_LLM=claude`** without **`CHAT_CLAUDE_FULL_MATRIX=1`**).

- **CI / headless / no Studio:** `RUN_ALL_SKIP_STUDIO=1 ./scripts/test/run-all.sh`
- **Optional disposable site test** (not part of `run-all.sh`): `./scripts/test/integration/e2e-site-lifecycle.sh` — requires `scripts/test/integration/create-site.json`.

There is **no** `yarn test` / Vitest suite in `sources/package.json`. If you prefer not to use `run-all.sh`, run the same steps by hand: `bash -n` on the shell scripts under `scripts/` (see what **`run-all.sh`** invokes), then `( cd sources && yarn package )`, then `./scripts/test/functional/rest-contracts.sh` (add `yarn lint` first if you want ESLint).

## Chat scenario harness (functionality + basic performance)

**`run-all.sh`** runs this **by default** as **step 4** (unless **`RUN_ALL_SKIP_STUDIO=1`** or **`RUN_ALL_SKIP_CHAT_SCENARIOS=1`**). The harness calls Studio **`ai/stream`**; override the provider with **`CHAT_LLM`** / **`CHAT_LLM_MODEL`** (see **`lib/chat-llm-env.mjs`**).

1. Optional: copy **`scenarios/chat-scenarios.example.json`** → **`scenarios/chat-scenarios.json`** and edit paths for your site.
2. **`CHAT_AGENT_ID`** is optional: the runner resolves **`agentId`** from **`agents.json`**, else the default UUID.
3. Standalone (Node 18+):

```bash
export CRAFTER_STUDIO_URL=http://localhost:8080
export CRAFTER_STUDIO_TOKEN='…'
export CHAT_SITE_ID=your-site
# Optional multi-LLM overrides:
# export CHAT_LLM=claude
# export CHAT_LLM_MODEL=claude-sonnet-4-20250514
# export CHAT_LLM=xAI
# export CHAT_LLM_MODEL=grok-4.3
node scripts/test/functional/run-chat-scenarios.mjs scripts/test/scenarios/chat-scenarios.example.json
```

**Default example file** — four turns: **hello**, **field-edit**, **translate-page**, **generate-image**.

### Multi-LLM smoke (fast, low cost)

| Profile | Command / scenario | Notes |
|---------|-------------------|--------|
| **Claude smoke** | `CHAT_LLM=claude ./scripts/test/run-all.sh` | Auto-selects **`chat-scenarios-claude-smoke.json`** (3 small turns); **skips** step-5 matrix (Tier-1 TPM). |
| **Claude smoke only** | `CHAT_LLM=claude CHAT_INTER_TURN_DELAY_MS=0 node …/chat-scenarios-claude-smoke.json` | ~20s; unset **`CHAT_SCENARIO_GROUP`** if set in shell. |
| **xAI smoke** | `CHAT_LLM=xAI CHAT_LLM_MODEL=grok-4.3 node …/chat-scenarios-claude-smoke.json` | Same 3-turn file; tools-loop stack. |
| **Tool smoke (Claude)** | `CHAT_SCENARIO_GROUP=builtin-tools node …/tools-claude-smoke.json` | 5 read-only tools; **`CHAT_INTER_TURN_DELAY_MS=0`**. |
| **Full Claude matrix** | `CHAT_LLM=claude CHAT_CLAUDE_FULL_MATRIX=1 CHAT_LLM_MODEL=claude-opus-4-20250514 RUN_ALL_CONTINUE_ON_FAIL=1 ./scripts/test/run-all.sh` | Long + costly; optional **`RUN_ALL_CONCURRENT_SESSIONS=1`**. |

**Claude pacing:** when **`CHAT_LLM=claude`**, default **45s** inter-turn delay and **2** rate-limit retries unless **`CHAT_INTER_TURN_DELAY_MS`** / **`CHAT_RATE_LIMIT_RETRIES`** override.

**Pitfall:** a stale shell **`CHAT_SCENARIO_GROUP=builtin-tools`** skips **`sandbox-smoke`** turns — **`unset CHAT_SCENARIO_GROUP`** before smoke runs.

To skip chat when using **`run-all.sh`**: **`RUN_ALL_SKIP_CHAT_SCENARIOS=1 ./scripts/test/run-all.sh`**.

## Tool + intent-recipe matrix (every CORE tool and bundled recipe)

**Offline (always in `run-all.sh` step 1 when `node` is available):**

- `tool-id-parity.mjs` — `StudioAiToolRegistry.CORE_TOOLS` wire names vs `STUDIO_AI_BUILTIN_TOOL_IDS` in TypeScript
- `recipe-catalog-offline.mjs` — bundled `authoring-intent-recipes-default.json` structure
- `router-json-offline.mjs` — intent router JSON extract/parse parity (`turnGoal`, prose-before-JSON)
- `generate-tool-recipe-scenarios.mjs --check` — fixture coverage + committed JSON drift guard

**Scenario files (generated; do not hand-edit):**

- `scenarios/intent-recipes-all.json` — 13 recipes (+ prior-turn for `new_content_item_from_chat_draft`)
- `scenarios/tools-all.json` — 31 built-in tools (one turn each with `enabledBuiltInTools`)

Curated prompts live in `fixtures/tool-recipe-matrix.mjs`. Regenerate after adding a tool or recipe:

```bash
node scripts/test/functional/generate-tool-recipe-scenarios.mjs
```

**Live matrix** (long; requires JWT, LLM keys, **`intentRecipeRouting.enabled`** on the site):

Integration optional **recipes** and **tools** run by default. **`partialOnMissingConfig`** turns report 🟡 partial when keys/routing/permissions block the turn — exit 0.

```bash
export CRAFTER_STUDIO_URL=http://localhost:8080
export CHAT_SITE_ID=your-site
# Optional: CHAT_PREVIEW_TOKEN, CHAT_MATRIX_ALLOW_WRITES=1, CHAT_MATRIX_ALLOW_PUBLISH=1
./scripts/test/functional/run-tool-recipe-matrix.sh
```

**Via `run-all.sh`:** step **5** runs the **full matrix** when not skipped — all **13 recipes** and **31 tools** with `CHAT_MATRIX_FULL=1`. **`run-tool-recipe-matrix.sh`** uses **`set -e`**: if the recipe pass exits non-zero, the **31-tool** pass does not run (use **`RUN_ALL_CONTINUE_ON_FAIL=1`** on **`run-all.sh`** to continue to step 6 anyway). Skip matrix: **`RUN_ALL_SKIP_TOOL_RECIPE_MATRIX=1`**.

**Per-turn SSE assertions** in scenario JSON (`turn.expect`): `recipeId`, `recipeOutcome`, `forbidRecipeId`, `deferToPlanLoop`, `toolsAny`, `toolsAll`, `forbidTools`, `maxToolStarts`, `maxToolStartCounts` (e.g. `{ "GenerateImage": 1 }`), `generateImagePromptSeen`, `turnGoalPresent`, `turnGoalContains`. Implemented in `lib/sse-telemetry.mjs` and enforced by `run-chat-scenarios.mjs`.

**Intent router JSON (offline):** `functional/router-json-offline.mjs` verifies `AuthoringIntentRecipeRouter.extractJsonPayload` / `parseRouterJson` parity (prose-before-JSON, fenced blocks, `turnGoal` / `successCriteria` fields). Runs in `run-all.sh` step 1.

**GenerateImage once per turn:** server skips duplicate `GenerateImage` tool calls in the same chat turn; live check: `node scripts/test/functional/run-chat-scenarios.mjs scripts/test/scenarios/chat-scenarios-generate-image-once.json`.

**Anchored “generate image for this page”:** must **plan-defer** (GetContent before GenerateImage), not whole-turn `generate_image` recipe; live check: `node scripts/test/functional/run-chat-scenarios.mjs scripts/test/scenarios/chat-scenarios-generate-image-page-routing.json`. Expect helpers: `forbidRecipeId`, `deferToPlanLoop`. The LLM must read page XML and craft the **GenerateImage** prompt — no server-side prompt rewrite.

## Concurrent users / sessions

Two complementary checks for cross-talk between authors or parallel chat streams:

**Offline (always in `run-all.sh` step 1):**

```bash
node scripts/test/functional/concurrent-ice-panel-storage.mjs
```

Verifies ICE panel `localStorage` keys are **per-username** so patching one author’s stored widget does not mutate another’s.

**Live (requires Studio + JWT + LLM):**

```bash
export CHAT_SITE_ID=your-site
node scripts/test/functional/run-concurrent-chat-sessions.mjs
```

Runs **two parallel** `ai/stream` requests with distinct `chatId` values and unique session markers. Fails if assistant text from session A appears in session B (or vice versa). Includes:

- **Echo pair** — `omitTools: true`, each session must echo its own marker only.
- **Tools pair** — parallel `GetContent` with overlapping worker threads (ThreadLocal / diag-session isolation).

Optional second user JWT: `CRAFTER_STUDIO_TOKEN_B` or gitignored `scripts/.studio-token-b` (`export CRAFTER_STUDIO_TOKEN_B='…'`). When omitted, both sessions use the same token but different `chatId`s (still catches stream cross-leak).

Env: `CONCURRENT_SESSIONS=echo|tools|both` (default `both`).

**Via `run-all.sh`:** `RUN_ALL_CONCURRENT_SESSIONS=1 ./scripts/test/run-all.sh`

## Reading test output

Harness output uses a consistent shape across **`run-chat-scenarios.mjs`**, **`run-tool-recipe-matrix.sh`**, and **`run-all.sh`**.

### Per-turn progress (`run-chat-scenarios.mjs`)

While each **`ai/stream`** turn runs:

```
… hello: Warm-up: no tools, checks stream + latency.
  ✅ completed  total=3212ms  first-chunk≈240ms  events=3

  ⏳ inter-turn delay 8000ms…
… get-content-one-tool: Single built-in tool only.
  ✅ completed  total=12784ms  first-chunk≈291ms  events=11  tools=GetContent
```

| Line | Meaning |
|------|---------|
| **`… id: summary`** | Turn started (`id` from scenario JSON). |
| **`✅ completed`** | Turn passed expectations. |
| **`❌ Stream error:`** / **`expectations failed:`** | Hard fail (non-zero exit unless only optional partials). |
| **`🟡 partial`** | Optional turn blocked by missing keys/config or soft recipe match (`partialOnMissingConfig`). |
| **`⏭ skipped`** | Filtered out (`CHAT_SCENARIO_GROUP`, `CHAT_SKIP_OPTIONAL`, `skipUnless` env). |
| **`tools=GetContent,…`** | Tool wire names observed in SSE telemetry. |
| **`⏳ inter-turn delay`** | Claude pacing (`CHAT_INTER_TURN_DELAY_MS`; default 45s for `CHAT_LLM=claude`). |

### Scenario report (end of each scenario file)

```
======== Scenario report: chat/chat-scenarios-claude-smoke (chat-scenarios-claude-smoke.json) ========
✅ hello: Warm-up: no tools, checks stream + latency. (3212ms)
✅ field-edit: Form assistant without tool registry (client JSON apply path). (9020ms)
❌ open_page_inquiry: Read-only summary of anchored page. (17089ms)
       → expectations failed: expected recipe open_page_inquiry outcome=matched; saw [none]
🟡 publish_site: Publish entire site (destructive). (10278ms)
       → expectations (missing config/key): expected recipe publish_site outcome=matched; saw [none]
⏭ WebSearch: skipped (optional (CHAT_SKIP_OPTIONAL=1))

-------- 2 passed, 1 partial, 1 failed, 1 skipped (5 total) --------
Summary: passed=2 partial=1 failed=1 skipped=1
Done: 1 turn(s) failed.
```

**Exit codes:** required turn **failed** → exit **1**. Only **partial** / **skip** on optional turns → exit **0** with a note. **`RUN_ALL_CONTINUE_ON_FAIL=1`** lets **`run-all.sh`** continue after chat/matrix failures.

### `run-all.sh` consolidated report

After all steps, **`run-all.sh`** prints the same report format grouped by suite (from **`scripts/test/.run-all-report.jsonl`**, gitignored):

```
======== run-all: complete test report ========

--- step1-offline ---
✅ bash-syntax-check: bash -n on integration shell scripts (3ms)
✅ tool-id-parity: CORE_TOOLS vs UI tool id parity (50ms)

--- step4-chat-scenarios ---
✅ hello: Warm-up: no tools, checks stream + latency baseline. (3212ms)
✅ field-edit: Form assistant: asks model to propose a field change. (9020ms)

--- step5-matrix/intent-recipes ---
🟡 modify_page_content: Modify anchored page content. (62222ms)
       → expectations (missing config/key): expected recipe modify_page_content outcome=matched; saw [none]

-------- 25 passed, 8 partial, 1 failed, 0 skipped (34 total) --------
```

Override the JSONL path: **`RUN_ALL_REPORT_FILE=/tmp/my-report.jsonl ./scripts/test/run-all.sh`**. Reprint only: **`node scripts/test/lib/run-report.mjs print --file=scripts/test/.run-all-report.jsonl`**.

### Status icons (reference)

| Icon | Status | Typical cause |
|------|--------|----------------|
| ✅ | **pass** | Turn completed; expectations met. |
| 🟡 | **partial** | Optional integration gap (missing API key, recipe telemetry mismatch with `partialOnMissingConfig`). |
| ❌ | **fail** | Stream error, HTTP 4xx/5xx from LLM, or failed `expect` assertions. |
| ⏭ | **skip** | Group filter, `CHAT_SKIP_OPTIONAL`, or destructive `skipUnless` env not set. |

**Partial vs fail:** 🟡 often means “tools ran but harness could not confirm recipe routing” or “integration not configured” — not necessarily a Claude/OpenAI wiring bug. ❌ on **`Stream error: Tools-loop chat HTTP 404`** with a **Claude model id** usually meant the pre-fix OpenAI-wire bug (now routed to Anthropic **`/v1/messages`**).

