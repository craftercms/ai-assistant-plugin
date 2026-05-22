# Intent recipe example — Propose initiative brief (Slack)

**Domain:** Project management (portfolio initiative brief), not content-management/blog.

**Flow:** Open-web research → site overlap check → long **## Draft body** in chat → Confirmation **llmRefine** JSON (short fields) + passthrough **draft** → Slack root + four thread replies.

**Prerequisites:** `SerpApiWebSearch`, `FetchHttpUrl`, `ResearchSiteContent`, `SlackPostMessage` enabled; `serpapi_api_key`, `slack_bot_token` in site `secrets.json`; Slack default channel in `tools.json`.

Adapt `pathPrefix`, voice names, and `matchHints` to the site. Commit `intent-recipes.json` to the site sandbox git repo.

```json
{
  "version": 1,
  "recipes": [
    {
      "id": "recipe_propose_initiative_slack",
      "title": "Propose initiative brief (Slack)",
      "chatEmoji": "📋",
      "description": "Research the open web and propose **one** timely initiative for the portfolio (delivery, governance, or tooling) — not a full project charter. Pick **Alex**, **Jordan**, or **Taylor** as the best-fit voice. Notify stakeholders on Slack. No repository writes.",
      "matchHints": [
        "propose initiative",
        "initiative brief slack",
        "draft initiative idea",
        "portfolio idea slack",
        "weekly initiative proposal"
      ],
      "dontMatchHints": [
        "save to repository",
        "create a page",
        "publish site",
        "three full charters",
        "compare three initiatives"
      ],
      "deterministicMatch": {
        "priority": 95,
        "routerReason": "deterministic_propose_initiative_slack",
        "authorFromMatchHints": true,
        "respectDontMatchHints": true
      },
      "toolsLoopForceTool": "SerpApiWebSearch",
      "toolsLoopAllowlist": [
        "SerpApiWebSearch",
        "FetchHttpUrl",
        "ResearchSiteContent"
      ],
      "toolsLoopExcludeTools": [
        "WriteContent",
        "update_content",
        "publish_content",
        "SlackPostMessage",
        "WebSearch"
      ],
      "toolsLoopFetchHttpUrlWireMaxChars": 8000,
      "toolsLoopMaxFetchHttpUrlCalls": 2,
      "matchedUserPrelude": "[Studio — Propose initiative brief (Slack)]\n1. **SerpApiWebSearch** (at most **two** attempts if the first returns no results) then up to **two** **FetchHttpUrl** calls (SerpAPI URLs only). Queries: short and concrete (3–7 terms); **do not** use the word **recent** or month/year — site **tbs** is already past week.\n2. **ResearchSiteContent** on existing initiative items (`pathPrefix` `/site/website/initiatives/`) before finishing your reply.\n3. Pick **one** voice — **Alex**, **Jordan**, or **Taylor** — and reflect it in root/pitch/draft tone.\n4. End with **## Work notes** (brief) and required **## Draft body** (full brief for its own Slack post — see Action). Confirmation copies **Draft body** verbatim; other Slack fields come from **llmRefine** JSON. **Do not** call **SlackPostMessage** in **tool_calls**.\n5. Pitch must be **current** ({{studio.today}}) with a **dated** why-now — not a recycled generic “what is agile” primer.\n6. **Third rail:** No vendor/product **launch** as the initiative spine.\n7. No repository save unless the author explicitly asks.",
      "phases": {
        "context": [
          "Today: **{{studio.today}}**. The initiative must feel **worth discussing this week** — not evergreen advice that could have run last year.",
          "Recency: external signals since **{{studio.today-7D}}** only for *Why now*. Reject undated explainers and generic methodology pages without a dated peg.",
          "**Reject stale pitches:** “Benefits of agile,” “introduction to OKRs,” “future of project management” without a **named news peg**. If research only supports primers, **search again** with sharper queries (AI in planning, portfolio governance, dependency risk, capacity modeling, etc.).",
          "**Bar:** Strong **opinion** + **operational detail** (roles, cadence, metrics, handoffs) — not vague transformation language.",
          "**Sources bar:** Only URLs you actually fetched. Each source MUST be `[title](https://…)` with a real **https** URL.",
          "**Third rail:** No vendor/product launch as the initiative spine.",
          "**Existing initiatives:** Search under **`/site/website/initiatives/`** (adjust **`pathPrefix`** to your site’s initiative storage) before finalizing.",
          "**Voice roster (pick exactly one):**",
          "**Alex** — Delivery and execution: ceremonies, dependencies, throughput, team topology, practical rollout.",
          "**Jordan** — Executive lens: outcomes, risk, investment tradeoffs, decision-ready summaries.",
          "**Taylor** — News peg: what changed this week, who announced what, why it matters for portfolio delivery now."
        ],
        "action": [
          "**Open web (SerpApi):** At most **two** SerpApiWebSearch calls per turn. Each query: **3–7 terms**, one angle. If **no results**, change keywords — do not repeat the same string.",
          "Disqualify launch PR and generic primer hits; need at least one **concrete, dated** hook from the last 7 days before finishing.",
          "**Site overlap (required):** After web research, call **ResearchSiteContent** with **siteId**, focused **query**, **pathPrefix** `/site/website/initiatives/`, **maxSearchHits** 12, **maxFetchItems** 4. Differentiate or replace the pitch vs top hits; note overlap in **## Work notes** (paths/titles only).",
          "After research tools finish, your **final** assistant message **must** include **## Work notes** then **## Draft body** (in that order). **No ## Plan**, no fenced JSON orchestration blocks, **no tool_calls**.",
          "**Work notes:** queries run, ideas rejected, overlap handling — no URLs required here.",
          "**Draft body (required):** Start `📋 Draft · voice: **Alex|**Jordan|**Taylor**` (one name, bold). Then `*Initiative title:*`, `*Outline:*` (specific operational bullets), then `*Draft brief:*` with **8–12 paragraphs** (~900–1400 words). Studio posts this section **verbatim** on its own Slack thread reply.",
          "**Pitch fields** (hook, audience, angle, why now) are formatted in confirmation **llmRefine** — keep them out of **Draft body** except what belongs in the brief prose.",
          "Do **not** ask follow-up questions."
        ],
        "confirmation": {
          "hints": [
            "Studio copies **## Draft body** from chat into **draft** (passthrough). **llmRefine** JSON fills root, workflowAlignment, pitch, sources. Five **SlackPostMessage** steps use **$slackOutbound.***. **Do not** call in **tool_calls**."
          ],
          "engineSteps": [
            {
              "llmRefine": "initiativeSlackOutbound",
              "as": "slackOutbound",
              "outputFormat": "json",
              "outputKeys": [
                "root",
                "workflowAlignment",
                "draft",
                "pitch",
                "sources"
              ],
              "passthroughFromSource": {
                "draft": ["Draft body", "Draft brief", "Pitch draft"]
              },
              "passthroughFallbackMaxOutTokens": {
                "draft": 8192
              },
              "passthroughFallbackHints": {
                "draft": [
                  "**draft** (own Slack thread reply): Start `📋 Draft · voice: **Alex|**Jordan|**Taylor**` (one name, bold). Then `*Initiative title:*`, `*Outline:*` (specific bullets, not a generic syllabus). Then `*Draft brief:*` followed by **8–12 full paragraphs** — complete prose, not a summary.",
                  "Use only facts and thesis supported by SOURCE (research + work notes). Do not invent URLs."
                ]
              },
              "userPreamble": "From the assistant turn below, produce **four** Slack mrkdwn message bodies: **root**, **workflowAlignment**, **pitch**, **sources** only. (**draft** is handled separately.) Today is **{{studio.today}}**.\n\n",
              "hints": [
                "**root** (channel root): Line 1 `📋 New initiative: <title>`. Line 2 `✍️ Voice: **Alex|**Jordan|**Taylor**` + short tone tag. Then `📋 Summary:` one paragraph with **why this week**. No long draft paragraphs in root.",
                "**workflowAlignment** (thread, editor-only): Start `🔝 Workflow alignment`. Then `*✅ Fits our delivery model:*` 2–4 bullets and `*⚠️ Gaps / neutral:*` 1–3 bullets (honest).",
                "**pitch** (thread): One label per line — ✍️ Voice, 🪝 Hook, 🎯 Audience, 📐 Angle (new value vs site overlap if any), ⏱️ Why now (dated peg).",
                "**sources** (thread): `🔗 Sources` then 1–3 lines each `[title](https://…)` from research — https required.",
                "Reject stale generic primers; reject launch-announcement framing.",
                "Use a few professional Unicode emojis per message (3–6 max)."
              ]
            },
            {
              "tool": "SlackPostMessage",
              "as": "slackRoot",
              "args": {
                "text": "$slackOutbound.root",
                "iconEmoji": ":clipboard:"
              }
            },
            {
              "tool": "SlackPostMessage",
              "args": {
                "threadTs": "$slackRoot.ts",
                "text": "$slackOutbound.workflowAlignment",
                "iconEmoji": ":gear:"
              }
            },
            {
              "tool": "SlackPostMessage",
              "args": {
                "threadTs": "$slackRoot.ts",
                "text": "$slackOutbound.draft",
                "iconEmoji": ":memo:"
              }
            },
            {
              "tool": "SlackPostMessage",
              "args": {
                "threadTs": "$slackRoot.ts",
                "text": "$slackOutbound.pitch",
                "iconEmoji": ":dart:"
              }
            },
            {
              "tool": "SlackPostMessage",
              "args": {
                "threadTs": "$slackRoot.ts",
                "text": "$slackOutbound.sources",
                "iconEmoji": ":link:"
              }
            }
          ]
        }
      }
    }
  ],
  "recipeOrder": [
    "web_research",
    "site_content_research",
    "llm_research",
    "open_page_inquiry",
    "modify_page_content",
    "revert_content_version",
    "generate_image",
    "template_display_change",
    "publish_site",
    "publish_item",
    "new_content_item",
    "translate_content_item",
    "recipe_propose_initiative_slack"
  ]
}
```
