package plugins.org.craftercms.aiassistant.studio.engine.turn

import groovy.json.JsonSlurper
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentExecutionPlan

/**
 * Tracks whether external lookup steps were **read** (FetchHttpUrl) before repository writes in the tools loop.
 * Prompt-agnostic — no domain-specific fact checks.
 */
final class AuthoringResearchGrounding {

  private AuthoringResearchGrounding() {}

  static void initFromAuthorVisible(Map toolsLoopSessionBundle, String authorVisible, String anchorPath) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return
    }
    boolean required = AuthoringIntentExecutionPlan.requiresExternalLookup(authorVisible)
    toolsLoopSessionBundle.toolsLoopExternalLookupRequired = required ? Boolean.TRUE : Boolean.FALSE
    toolsLoopSessionBundle.toolsLoopSearchOkThisTurn = Boolean.FALSE
    toolsLoopSessionBundle.toolsLoopFetchOkThisTurn = Boolean.FALSE
    toolsLoopSessionBundle.toolsLoopUsableExternalFact = Boolean.FALSE
    toolsLoopSessionBundle.toolsLoopLastSalientFact = ''
  }

  static void recordTool(Map toolsLoopSessionBundle, String wireName, String toolOutJson, JsonSlurper slurper = null) {
    if (!(toolsLoopSessionBundle instanceof Map) || !wireName?.trim() || !toolOutJson?.trim()) {
      return
    }
    JsonSlurper parser = slurper != null ? slurper : new JsonSlurper()
    Object parsed
    try {
      parsed = parser.parseText(toolOutJson.toString())
    } catch (Throwable ignored) {
      return
    }
    if (!(parsed instanceof Map)) {
      return
    }
    Map m = (Map) parsed
    String name = wireName.trim()
    if ('WebSearch'.equals(name) || 'SerpApiWebSearch'.equals(name)) {
      if (Boolean.TRUE.equals(m.get('ok')) && (m.get('results') instanceof List) && !((List) m.get('results')).isEmpty()) {
        toolsLoopSessionBundle.toolsLoopSearchOkThisTurn = Boolean.TRUE
      }
    }
    if ('FetchHttpUrl'.equals(name)) {
      if (Boolean.TRUE.equals(m.get('ok'))) {
        def bod = m.get('body')
        if (bod != null && bod.toString().trim()) {
          toolsLoopSessionBundle.toolsLoopFetchOkThisTurn = Boolean.TRUE
        }
      }
    }
  }

  /**
   * User-role nudge after a tool round when search ran but target URL body was not fetched before writes.
   */
  static String formatPostRoundNudge(
    Map toolsLoopSessionBundle,
    boolean writeAttemptedThisRound,
    boolean fetchSucceededThisRound
  ) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return ''
    }
    if (!Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopExternalLookupRequired)) {
      return ''
    }
    boolean fetchOk = Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopFetchOkThisTurn) || fetchSucceededThisRound
    boolean usableFact = Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopUsableExternalFact)
    boolean searchOk = Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopSearchOkThisTurn)

    if (fetchOk && !usableFact) {
      return '''[Studio — shallow fetch: no verified headline]
**FetchHttpUrl** ran but only a site index or generic page title was found — not a specific headline or article fact.

**Do this next:**
1. From search results (or the fetched page links), pick one **specific article URL** — not a site root.
2. **FetchHttpUrl** on that URL and read the response body for the actual headline text.
3. Retry **update_content** / **WriteContent** using that **specific headline** in `title_t` / `hero_title_html` and **supporting context** in `hero_text_html` / body fields (see **[Studio — content field plan]**).
'''
    }
    if (fetchOk || usableFact) {
      return ''
    }
    if (!searchOk) {
      if (writeAttemptedThisRound) {
        return '''[Studio — external lookup failed before write]
**WebSearch** did not return usable results and no verified fact was fetched before a repository write.

**Do this next:**
1. Retry **WebSearch** or **SerpApiWebSearch** with a narrower query, or **FetchHttpUrl** on a known news URL the author trusts.
2. **FetchHttpUrl** on a **specific article** URL and extract the headline from the body.
3. Only then **GetContent** → **update_content** / **WriteContent** with that headline in page fields.
'''
      }
      return ''
    }
    if (writeAttemptedThisRound) {
      return '''[Studio — research grounding required before write]
You used **WebSearch** but did **not** call **FetchHttpUrl** on a result URL before a repository write.

**Do this next:**
1. From search results, pick one **specific** URL (not a site root or section index).
2. **FetchHttpUrl** on that URL and read the response body.
3. Retry **GetContent** → **WriteContent** / **update_content** using facts from the **fetched body** — not the search **title** or **snippet** alone.
'''
    }
    return '''[Studio — research grounding: fetch before you write]
**WebSearch** returned candidate links only. Before **WriteContent**, **update_content**, or **GenerateImage** that depends on live facts:

1. Choose one **specific** URL from the results (avoid site roots and shallow index pages).
2. Call **FetchHttpUrl** on that URL.
3. Use the **fetched page body** in later tool arguments.
'''
  }
}
