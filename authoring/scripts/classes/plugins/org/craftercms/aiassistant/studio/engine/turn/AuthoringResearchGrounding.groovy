package plugins.org.craftercms.aiassistant.studio.engine.turn

import groovy.json.JsonSlurper
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.FormDefinitionCopyFieldPlan
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentExecutionPlan

/**
 * Tracks whether external lookup steps were **read** (FetchHttpUrl) before repository writes in the tools loop.
 * Prompt-agnostic — no domain-specific fact checks.
 */
final class AuthoringResearchGrounding {

  private static final int MIN_SUBSTANTIVE_RETRIEVED_CHARS = 250

  private AuthoringResearchGrounding() {}

  static boolean hasSubstantiveRetrievedSource(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return false
    }
    String excerpt = (toolsLoopSessionBundle.toolsLoopRetrievedSourceExcerpt ?: '').toString().trim()
    return excerpt.length() >= MIN_SUBSTANTIVE_RETRIEVED_CHARS
  }

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
    toolsLoopSessionBundle.remove('toolsLoopRetrievedSourceExcerpt')
    toolsLoopSessionBundle.remove('toolsLoopRetrievedSourceUrl')
    toolsLoopSessionBundle.remove('toolsLoopRetrievedSourcePageTitle')
    toolsLoopSessionBundle.remove('toolsLoopSynthesisNudgeEmitted')
    toolsLoopSessionBundle.remove('toolsLoopHeroImageNudgeEmitted')
    toolsLoopSessionBundle.toolsLoopGenerateImageOkThisTurn = Boolean.FALSE
    toolsLoopSessionBundle.toolsLoopWriteContentOkThisTurn = Boolean.FALSE
    toolsLoopSessionBundle.remove('toolsLoopResearchPageRefreshExpectsHeroImage')
    toolsLoopSessionBundle.remove('toolsLoopWriteContentRepoPaths')
    toolsLoopSessionBundle.remove('toolsLoopPendingWriteContentRecoveryNudge')
  }

  /**
   * When a research-backed page copy refresh targets a content type with image-asset fields, expect
   * {@code GenerateImage} + CMS persistence after copy writes (execution plan image step).
   */
  static void refreshResearchHeroImageExpectation(Map toolsLoopSessionBundle, String authorVisible) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return
    }
    if (!Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopExternalLookupRequired)) {
      return
    }
    if (!AuthoringIntentExecutionPlan.researchPageCopyUpdate(authorVisible ?: '')) {
      return
    }
    List<String> imageFieldIds = FormDefinitionCopyFieldPlan.imageAssetFieldIdsFromBundle(toolsLoopSessionBundle)
    if (imageFieldIds.isEmpty()) {
      return
    }
    toolsLoopSessionBundle.toolsLoopResearchPageRefreshExpectsHeroImage = Boolean.TRUE
    toolsLoopSessionBundle.toolsLoopCopyPlanImageFieldIds = new ArrayList<>(imageFieldIds)
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
        List results = (List) m.get('results')
        for (def row : results) {
          if (!(row instanceof Map)) {
            continue
          }
          String url = (row.get('url') ?: row.get('link') ?: '').toString().trim()
          if (url) {
            toolsLoopSessionBundle.toolsLoopTopSearchUrl = url
            break
          }
        }
      }
    }
    if ('FetchHttpUrl'.equals(name)) {
      if (Boolean.TRUE.equals(m.get('ok'))) {
        def bod = m.get('body')
        String body = bod != null ? bod.toString() : ''
        if (body.trim()) {
          toolsLoopSessionBundle.toolsLoopFetchOkThisTurn = Boolean.TRUE
          String excerpt = AuthoringFetchedPageFacts.plainTextExcerpt(body, 8_000)
          if (excerpt.length() >= MIN_SUBSTANTIVE_RETRIEVED_CHARS) {
            toolsLoopSessionBundle.toolsLoopRetrievedSourceExcerpt = excerpt
            toolsLoopSessionBundle.toolsLoopUsableExternalFact = Boolean.TRUE
          }
        }
      }
    }
    if ('GenerateImage'.equals(name)) {
      if (Boolean.TRUE.equals(m.get('ok'))) {
        toolsLoopSessionBundle.toolsLoopGenerateImageOkThisTurn = Boolean.TRUE
      }
    }
    if ('WriteContent'.equals(name)) {
      if (Boolean.TRUE.equals(m.get('ok')) ||
        'written'.equalsIgnoreCase(m.get('result')?.toString()?.trim())) {
        toolsLoopSessionBundle.toolsLoopWriteContentOkThisTurn = Boolean.TRUE
      }
    }
  }

  /**
   * Blocks WriteContent when this turn requires external lookup but no substantive fetched body is on the session yet.
   */
  static Map gateWriteContent(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map) ||
      !Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopExternalLookupRequired)) {
      return [proceed: Boolean.TRUE]
    }
    if (hasSubstantiveRetrievedSource(toolsLoopSessionBundle)) {
      return [proceed: Boolean.TRUE]
    }
    boolean searchOk = Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopSearchOkThisTurn)
    boolean fetchOk = Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopFetchOkThisTurn)
    String message
    String nextStep
    if (!searchOk) {
      message =
        'WriteContent **blocked** — this turn needs live facts. Run **WebSearch** / **SerpApiWebSearch**, then **FetchHttpUrl** on a result URL and read the body before writing.'
      nextStep = 'WebSearch → FetchHttpUrl (read body) → GetContent → WriteContent with copy grounded in retrieved source text.'
    } else if (!fetchOk) {
      message =
        'WriteContent **blocked** — search ran but no URL body was fetched. **FetchHttpUrl** on a specific result URL and use the retrieved text in copy fields.'
      nextStep = 'FetchHttpUrl on a search result URL → WriteContent using retrieved source excerpt + content field plan.'
    } else {
      message =
        'WriteContent **blocked** — **FetchHttpUrl** returned too little text. Pick a deeper article URL from search results and fetch again.'
      nextStep = 'FetchHttpUrl on a specific article URL → WriteContent grounded in the new retrieved source excerpt.'
    }
    return [
      proceed : Boolean.FALSE,
      toolOut : groovy.json.JsonOutput.toJson([
        ok      : false,
        skipped : true,
        message : message,
        nextStep: nextStep
      ])
    ]
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
    if (hasSubstantiveRetrievedSource(toolsLoopSessionBundle)) {
      return ''
    }
    boolean fetchOk = Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopFetchOkThisTurn) || fetchSucceededThisRound
    boolean searchOk = Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopSearchOkThisTurn)

    if (fetchOk) {
      return '''[Studio — fetch returned too little text]
**FetchHttpUrl** ran but the plain-text body was too short to ground copy.

**Do this next:**
1. From search results, pick a **specific article** URL (not a bare redirect).
2. **FetchHttpUrl** again and read the response body.
3. **WriteContent** using facts from the **retrieved source excerpt** and the **[Studio — content field plan]**.
'''
    }
    if (!searchOk) {
      if (writeAttemptedThisRound) {
        return '''[Studio — external lookup failed before write]
**WebSearch** did not return usable results and no retrieved source text is available.

**Do this next:**
1. Retry **WebSearch** or **SerpApiWebSearch**, then **FetchHttpUrl** on a result URL.
2. Use the **retrieved source excerpt** in **WriteContent** copy fields per the content field plan.
'''
      }
      return ''
    }
    if (writeAttemptedThisRound) {
      String topUrl = (toolsLoopSessionBundle.toolsLoopTopSearchUrl ?: '').toString().trim()
      String urlLine = topUrl ?
        "\n**Suggested URL from search:** `${topUrl}` — call **FetchHttpUrl** on this URL first.\n" :
        ''
      return """[Studio — research grounding required before write]
You used **WebSearch** but did **not** call **FetchHttpUrl** (or the body was not read) before a repository write.
${urlLine}
**Do this next:**
1. **FetchHttpUrl** on a specific URL from search results.
2. Ground **WriteContent** in the **retrieved source excerpt** — not search snippets alone.
"""
    }
    return '''[Studio — research grounding: fetch before you write]
**WebSearch** returned links only. Before **WriteContent**:

1. **FetchHttpUrl** on one specific URL from the results.
2. Use the **retrieved plain-text excerpt** when populating copy fields.
'''
  }

  /**
   * After FetchHttpUrl succeeds: nudge synthesis + page concept before WriteContent (research+copy turns).
   */
  static String formatSynthesisBeforeWriteNudge(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return ''
    }
    if (!Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopExternalLookupRequired)) {
      return ''
    }
    if (!hasSubstantiveRetrievedSource(toolsLoopSessionBundle)) {
      return ''
    }
    if (Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopSynthesisNudgeEmitted)) {
      return ''
    }
    toolsLoopSessionBundle.toolsLoopSynthesisNudgeEmitted = Boolean.TRUE
    return '''[Studio — synthesize before you write]
You have **retrieved source text** this turn. Before **WriteContent**:

1. In brief prose: what the sources say, what is **current**, and what you will **not** claim without evidence.
2. State the **overall page idea** — theme, tone, and 2–4 concrete points for **this** site\'s page.
3. Write **newsroom-quality** headlines for **original-headline** fields — a concrete angle (who/what changed), **not** the author's assignment ("Latest updates on…") or filler ("Insights and Implications").
4. Map **facts** (names, dates, events) into supporting/deck **Purpose** fields — do not paste source intro paragraphs verbatim.
5. **GetContent** → **WriteContent** with distinct copy per **Purpose** column (you may omit optional image fields on the first write).
6. **GenerateImage** with a prompt matching your page angle — the server imports to `/static-assets/` and applies to image-asset fields on the anchored item.
'''
  }

  /**
   * After copy write on a research page refresh: require hero image generation when the plan includes image-asset fields.
   */
  static String formatHeroImageAfterCopyNudge(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return ''
    }
    if (!Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopResearchPageRefreshExpectsHeroImage)) {
      return ''
    }
    if (!Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopWriteContentOkThisTurn)) {
      return ''
    }
    if (Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopGenerateImageOkThisTurn)) {
      return ''
    }
    if (Boolean.TRUE.equals(toolsLoopSessionBundle.toolsLoopHeroImageNudgeEmitted)) {
      return ''
    }
    toolsLoopSessionBundle.toolsLoopHeroImageNudgeEmitted = Boolean.TRUE
    List<String> imageIds = FormDefinitionCopyFieldPlan.imageAssetFieldIdsFromBundle(toolsLoopSessionBundle)
    String fieldsLine = imageIds.isEmpty() ? '' :
      "\n**Image-asset fields from the content field plan:** `" + imageIds.join('`, `') + '`.\n'
    return """[Studio — hero image required for this page refresh]
Copy was saved, but this research-backed page refresh still needs a **new hero image** that matches your synthesized angle.
${fieldsLine}
**Do this next:**
1. **GenerateImage** — prompt from your page theme and key facts (not a generic stock label).
2. The server imports the bitmap to `/static-assets/` and applies it to the anchored page — verify in preview.
"""
  }
}
