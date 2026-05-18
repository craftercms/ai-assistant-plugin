package plugins.org.craftercms.aiassistant.recipes

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext

import java.util.Locale

/**
 * Named deterministic routing signals declared on intent recipes ({@code deterministicMatch.signal}).
 * Orchestration passes {@code routerVisible} and optional closures for signals that need tool-loop helpers.
 * <p>Signal string values must match {@code deterministicMatch.signal} in
 * {@code authoring-intent-recipes-default.json} (and site overrides).</p>
 */
final class AuthoringIntentRecipeSignals {

  private AuthoringIntentRecipeSignals() {}

  static final String IMAGE_ONLY_GENERATE = 'image_only_generate'
  static final String WEB_RESEARCH = 'web_research'
  static final String SITE_CONTENT_RESEARCH = 'site_content_research'
  static final String LLM_RESEARCH = 'llm_research'
  static final String CREATIVE_LLM_ONLY = 'creative_llm_only'
  static final String CHAT_ARTIFACT_FOLLOWUP = 'chat_artifact_followup'
  static final String REVERT_CONTENT_VERSION = 'revert_content_version'
  static final String TRANSLATE_INTENT = 'translate_intent'
  static final String CONCRETE_FIELD_EDIT = 'concrete_field_edit'
  static final String EXTERNAL_CONTENT_FIELD_EDIT = 'external_content_field_edit'
  static final String OPEN_PAGE_INQUIRY = 'open_page_inquiry'
  static final String PUBLISH_SITE_BULK = 'publish_site_bulk'

  /** Current-turn author text for pattern signals (not full wire prompt with prior conversation). */
  private static String signalPrompt(Map ctx) {
    String visible = (ctx.routerVisible ?: '').toString().trim()
    if (visible) {
      return visible
    }
    String cand = (ctx.cand ?: '').toString()
    String current = AuthoringPreviewContext.extractAuthorCurrentRequestVisible(cand)
    if (current?.trim()) {
      return current.trim()
    }
    return AuthoringPreviewContext.stripStudioInjectedPromptBlocks(cand) ?: ''
  }

  private static String anchorCarrierPrompt(Map ctx) {
    String cand = (ctx.cand ?: '').toString().trim()
    if (cand) {
      return cand
    }
    return (ctx.routerVisible ?: '').toString()
  }

  static boolean evaluate(String signal, Map ctx) {
    String sig = (signal ?: '').toString().trim()
    if (!sig || !(ctx instanceof Map)) {
      return false
    }
    String cand = (ctx.cand ?: '').toString()
    String routerVisible = (ctx.routerVisible ?: '').toString()
    String prompt = signalPrompt(ctx)
    switch (sig) {
      case IMAGE_ONLY_GENERATE:
        return AuthoringPreviewContext.authorVisibleSuggestsIntentRecipeGenerateImage(prompt)
      case WEB_RESEARCH:
        return AuthoringPreviewContext.authorVisibleSuggestsWebResearch(prompt)
      case SITE_CONTENT_RESEARCH:
        return AuthoringPreviewContext.authorVisibleSuggestsSiteContentResearch(prompt)
      case LLM_RESEARCH:
        return AuthoringPreviewContext.authorVisibleSuggestsLlmResearch(prompt)
      case CREATIVE_LLM_ONLY:
        return AuthoringPreviewContext.authorCurrentRequestLooksLikeCreativeLlmOnly(
          (ctx.cand ?: prompt ?: '').toString()
        )
      case CHAT_ARTIFACT_FOLLOWUP:
        return AuthoringPreviewContext.authorConversationPivotedToChatOnlyArtifact(
          (ctx.cand ?: prompt ?: '').toString()
        )
      case REVERT_CONTENT_VERSION:
        if (!AuthoringPreviewContext.authorVisibleSuggestsRevertIntent(routerVisible)) {
          return false
        }
        String anchor = AuthoringPreviewContext.extractAnchoredRepositoryPath(cand)
        if (!anchor?.trim()) {
          anchor = AuthoringPreviewContext.extractAnchoredRepositoryPath(routerVisible)
        }
        return anchor &&
          anchor.toLowerCase(Locale.ROOT).startsWith('/site/') &&
          anchor.toLowerCase(Locale.ROOT).endsWith('.xml')
      case TRANSLATE_INTENT:
        Closure tr = ctx.evaluateTranslateIntent as Closure
        return tr != null ? Boolean.TRUE.equals(tr.call()) : false
      case CONCRETE_FIELD_EDIT:
        Closure fe = ctx.evaluateConcreteFieldEdit as Closure
        return fe != null ? Boolean.TRUE.equals(fe.call()) : false
      case EXTERNAL_CONTENT_FIELD_EDIT:
        Closure ex = ctx.evaluateExternalContentFieldEdit as Closure
        return ex != null ? Boolean.TRUE.equals(ex.call()) : false
      case OPEN_PAGE_INQUIRY:
        return AuthoringPreviewContext.authorVisibleSuggestsOpenPageInquiryForAuthorText(
          anchorCarrierPrompt(ctx),
          prompt
        )
      case PUBLISH_SITE_BULK:
        return AuthoringPreviewContext.authorVisibleSuggestsPublishSiteBulk(prompt)
      default:
        return false
    }
  }
}
