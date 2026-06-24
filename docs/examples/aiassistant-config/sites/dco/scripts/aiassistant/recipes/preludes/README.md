# DCO recipe preludes

Long **matched user prelude** text for intent recipes lives here as markdown so `intent-recipes.json` stays readable in git.

| File | Recipe id |
|------|-----------|
| `recipe_1779227205002-matched-user-prelude.md` | Turn 1 — draft blog from URL / Slack workflow |
| `new_content_item_from_chat_draft-matched-user-prelude.md` | Turn 2 — create `/component/post` from prior chat |

Each recipe references its file via **`matchedUserPreludePath`** in `../config/intent-recipes.json` (studio module path, no `/config/studio/` prefix).

Deploy to site sandbox:

```text
config/studio/scripts/aiassistant/recipes/preludes/*.md
config/studio/scripts/aiassistant/config/intent-recipes.json
```
