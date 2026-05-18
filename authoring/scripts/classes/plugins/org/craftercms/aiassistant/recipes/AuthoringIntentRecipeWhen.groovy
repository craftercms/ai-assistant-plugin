package plugins.org.craftercms.aiassistant.recipes

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext

import java.util.Locale
import java.util.regex.Pattern

/**
 * Generic evaluator for {@code deterministicMatch.when} / shorthand flags on intent recipes (config only).
 * Predicate names describe author/anchor shape — not recipe ids.
 */
final class AuthoringIntentRecipeWhen {

  private AuthoringIntentRecipeWhen() {}

  static boolean evaluateMatchEntry(Map entry, Map recipe, Map ctx) {
    if (!(entry instanceof Map) || !(ctx instanceof Map)) {
      return false
    }
    Object whenSpec = buildWhenSpec(entry, recipe)
    if (whenSpec == null) {
      return false
    }
    if (!evaluate(whenSpec, recipe, ctx)) {
      return false
    }
    if (Boolean.TRUE.equals(entry.respectDontMatchHints)) {
      String author = AuthoringIntentRecipeCatalog.deterministicRoutingPrompt(ctx)
      if (AuthoringIntentRecipeCatalog.recipeExcludedByDontMatchHints(recipe, author)) {
        return false
      }
    }
    return true
  }

  private static Object buildWhenSpec(Map entry, Map recipe) {
    List<Object> allOf = []
    Object explicit = entry.get('when')
    if (explicit != null) {
      allOf.add(explicit)
    }
    if (Boolean.TRUE.equals(entry.requiresAnchoredSiteXml)) {
      allOf.add('anchoredSiteXml')
    }
    if (Boolean.TRUE.equals(entry.requiresNoAnchoredSiteXml)) {
      allOf.add([not: 'anchoredSiteXml'])
    }
    if (Boolean.TRUE.equals(entry.authorFromMatchHints)) {
      List<String> hints = AuthoringIntentRecipeCatalog.matchHintsList(recipe)
      if (!hints.isEmpty()) {
        allOf.add([authorContainsAny: hints])
      }
    }
    List<String> any = AuthoringIntentRecipeCatalog.hintStringList(entry.get('authorContainsAny'))
    if (!any.isEmpty()) {
      allOf.add([authorContainsAny: any])
    }
    List<String> none = AuthoringIntentRecipeCatalog.hintStringList(entry.get('authorContainsNone'))
    if (!none.isEmpty()) {
      allOf.add([authorContainsNone: none])
    }
    Object regex = entry.get('authorMatchesRegex')
    if (regex != null) {
      allOf.add([authorMatchesRegex: regex])
    }
    if (allOf.isEmpty()) {
      return null
    }
    if (allOf.size() == 1) {
      return allOf[0]
    }
    return [allOf: allOf]
  }

  static boolean evaluate(Object whenSpec, Map recipe, Map ctx) {
    if (whenSpec == null) {
      return false
    }
    if (whenSpec instanceof String) {
      return evaluateLeaf(whenSpec.toString().trim(), ctx)
    }
    if (whenSpec instanceof List) {
      return evaluateAll(whenSpec, recipe, ctx)
    }
    if (!(whenSpec instanceof Map)) {
      return false
    }
    Map w = (Map) whenSpec
    if (w.containsKey('allOf')) {
      return evaluateAll(w.allOf, recipe, ctx)
    }
    if (w.containsKey('anyOf')) {
      return evaluateAny(w.anyOf, recipe, ctx)
    }
    if (w.containsKey('not')) {
      return !evaluate(w.not, recipe, ctx)
    }
    if (w.containsKey('authorContainsAny')) {
      return authorContainsAny(w.authorContainsAny, ctx)
    }
    if (w.containsKey('authorContainsNone')) {
      return authorContainsNone(w.authorContainsNone, ctx)
    }
    if (w.containsKey('authorMatchesRegex')) {
      return authorMatchesRegex(w.authorMatchesRegex, ctx)
    }
    return false
  }

  private static boolean evaluateAll(Object raw, Map recipe, Map ctx) {
    if (!(raw instanceof List) || ((List) raw).isEmpty()) {
      return false
    }
    for (Object part : (List) raw) {
      if (!evaluate(part, recipe, ctx)) {
        return false
      }
    }
    return true
  }

  private static boolean evaluateAny(Object raw, Map recipe, Map ctx) {
    if (!(raw instanceof List) || ((List) raw).isEmpty()) {
      return false
    }
    for (Object part : (List) raw) {
      if (evaluate(part, recipe, ctx)) {
        return true
      }
    }
    return false
  }

  private static boolean evaluateLeaf(String leaf, Map ctx) {
    String id = (leaf ?: '').trim()
    if (!id) {
      return false
    }
    String wire = anchorCarrier(ctx)
    String author = AuthoringIntentRecipeCatalog.deterministicRoutingPrompt(ctx)
    String probe = AuthoringPreviewContext.intentRoutingProbe(wire, author)
    switch (id) {
      case 'anchoredSiteXml':
        return hasAnchoredSiteXml(wire, author)
      case 'translateIntent':
        Closure tr = ctx.evaluateTranslateIntent as Closure
        return tr != null ? Boolean.TRUE.equals(tr.call()) : false
      case 'concreteFieldEdit':
        Closure fe = ctx.evaluateConcreteFieldEdit as Closure
        return fe != null ? Boolean.TRUE.equals(fe.call()) : false
      case 'externalContentFieldEdit':
        Closure ex = ctx.evaluateExternalContentFieldEdit as Closure
        return ex != null ? Boolean.TRUE.equals(ex.call()) : false
      case 'chatArtifactFollowup':
        return AuthoringPreviewContext.authorConversationPivotedToChatOnlyArtifact(wire)
      case 'creativeLlmOnly':
        return AuthoringPreviewContext.authorCurrentRequestLooksLikeCreativeLlmOnly(wire)
      case 'currentTurnCmsTooling':
        return AuthoringPreviewContext.authorCurrentRequestSuggestsCmsTooling(wire)
      case 'imageOnlyGenerate':
        return AuthoringPreviewContext.authorVisibleSuggestsIntentRecipeGenerateImage(author)
      default:
        return false
    }
  }

  private static boolean hasAnchoredSiteXml(String wire, String author) {
    String anchor = AuthoringPreviewContext.extractAnchoredRepositoryPath(wire)
    if (!anchor?.trim()) {
      anchor = AuthoringPreviewContext.extractAnchoredRepositoryPath(author)
    }
    if (!anchor?.trim()) {
      return false
    }
    String low = anchor.toLowerCase(Locale.ROOT)
    return low.startsWith('/site/') && low.endsWith('.xml')
  }

  private static String anchorCarrier(Map ctx) {
    String cand = (ctx.cand ?: '').toString().trim()
    if (cand) {
      return cand
    }
    return (ctx.routerVisible ?: '').toString()
  }

  private static boolean authorContainsAny(Object raw, Map ctx) {
    List<String> phrases = AuthoringIntentRecipeCatalog.hintStringList(raw)
    if (phrases.isEmpty()) {
      return false
    }
    String author = AuthoringIntentRecipeCatalog.deterministicRoutingPrompt(ctx)
    return AuthoringIntentRecipeCatalog.authorVisibleMatchesKeywordList(author, phrases)
  }

  private static boolean authorContainsNone(Object raw, Map ctx) {
    List<String> phrases = AuthoringIntentRecipeCatalog.hintStringList(raw)
    if (phrases.isEmpty()) {
      return true
    }
    String author = AuthoringIntentRecipeCatalog.deterministicRoutingPrompt(ctx)
    return !AuthoringIntentRecipeCatalog.authorVisibleMatchesKeywordList(author, phrases)
  }

  private static boolean authorMatchesRegex(Object raw, Map ctx) {
    String author = AuthoringPreviewContext.stripStudioInjectedPromptBlocks(
      AuthoringIntentRecipeCatalog.deterministicRoutingPrompt(ctx)
    )?.trim()
    if (!author) {
      return false
    }
    List<String> patterns = []
    if (raw instanceof String) {
      String p = raw.toString().trim()
      if (p) {
        patterns.add(p)
      }
    } else if (raw instanceof List) {
      for (Object o : (List) raw) {
        String p = o?.toString()?.trim()
        if (p) {
          patterns.add(p)
        }
      }
    }
    if (patterns.isEmpty()) {
      return false
    }
    for (String p : patterns) {
      try {
        if (Pattern.compile(p).matcher(author).find()) {
          return true
        }
      } catch (Exception ignored) {
        // invalid site override pattern — skip
      }
    }
    return false
  }
}
