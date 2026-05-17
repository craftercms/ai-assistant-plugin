package plugins.org.craftercms.aiassistant.recipes

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.util.Locale

/**
 * Named deterministic routing signals declared on intent recipes ({@code deterministicMatch.signal}).
 * Orchestration passes {@code routerVisible} and optional closures for signals that need tool-loop helpers.
 */
final class AuthoringIntentRecipeSignals {

  private AuthoringIntentRecipeSignals() {}

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
    StudioToolOperations ops = (ctx.ops instanceof StudioToolOperations) ? (StudioToolOperations) ctx.ops : null
    switch (sig) {
      case 'image_only_generate':
        return AuthoringPreviewContext.authorVisibleSuggestsIntentRecipeGenerateImage(prompt)
      case 'web_research':
        return AuthoringPreviewContext.authorVisibleSuggestsWebResearch(prompt)
      case 'site_content_research':
        return AuthoringPreviewContext.authorVisibleSuggestsSiteContentResearch(prompt)
      case 'llm_research':
        return AuthoringPreviewContext.authorVisibleSuggestsLlmResearch(prompt)
      case 'revert_content_version':
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
      case 'translate_intent':
        Closure tr = ctx.evaluateTranslateIntent as Closure
        return tr != null ? Boolean.TRUE.equals(tr.call()) : false
      case 'concrete_field_edit':
        Closure fe = ctx.evaluateConcreteFieldEdit as Closure
        return fe != null ? Boolean.TRUE.equals(fe.call()) : false
      case 'external_content_field_edit':
        Closure ex = ctx.evaluateExternalContentFieldEdit as Closure
        return ex != null ? Boolean.TRUE.equals(ex.call()) : false
      case 'open_page_inquiry':
        return AuthoringPreviewContext.authorVisibleSuggestsOpenPageInquiryForAuthorText(
          anchorCarrierPrompt(ctx),
          prompt
        )
      case 'publish_site_bulk':
        return AuthoringPreviewContext.authorVisibleSuggestsPublishSiteBulk(prompt)
      default:
        return false
    }
  }
}
