package plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting

import plugins.org.craftercms.aiassistant.studio.engine.context.AuthoringPreviewContext

/**
 * Author-visible intent contract for a chat turn: goal, steps, success bar, and guardrails.
 * When the router emits meta summaries ("involves multiple steps…"), {@link #resolveAuthorIntent}
 * prefers the author's {@code Current request:} text instead.
 */
final class AuthoringIntentCard {

  /** Utility class; not for instantiation. */
  private AuthoringIntentCard() {}

  /**
   * Resolves display + execution turn goal and success criteria, preferring concrete author text
   * over weak router LLM summaries.
   *
   * @return map with {@code turnGoal}, {@code successCriteria}, {@code authorRequest}, {@code steps}
   */
  static Map resolveAuthorIntent(
    String routerTurnGoal,
    String routerSuccessCriteria,
    String authorVisible,
    String anchorPath,
    String routerReason = ''
  ) {
    String authorRequest = extractCleanAuthorRequest(authorVisible)
    List<String> steps = deriveSteps(authorRequest, '')
    String goal = (routerTurnGoal ?: '').trim()
    String criteria = (routerSuccessCriteria ?: '').trim()
    String reason = (routerReason ?: '').trim()

    if (isWeakTurnGoal(goal)) {
      goal = ''
    }
    if (isWeakSuccessCriteria(criteria)) {
      criteria = ''
    }
    if (!goal && authorRequest) {
      goal = condenseAuthorGoal(authorRequest, anchorPath)
    }
    if (!goal && reason && !isWeakTurnGoal(reason)) {
      goal = reason
    }
    if (!criteria && authorRequest) {
      criteria = deriveSuccessCriteriaFromAuthor(authorRequest, anchorPath, steps)
    }
    if (!criteria) {
      criteria = 'Each step in the author request was completed with verifiable repository or tool outcomes.'
    }
    return [
      turnGoal         : goal ?: '',
      successCriteria  : criteria ?: '',
      authorRequest    : authorRequest ?: '',
      steps            : steps ?: []
    ]
  }

  /**
   * Markdown shown in chat before tools run (SSE {@code intent-recipe-routing} text + telemetry field).
   */
  static String formatCardMarkdown(
    String turnGoal,
    String successCriteria,
    String anchorPath,
    String authorVisible,
    String recipeId = ''
  ) {
    Map resolved = resolveAuthorIntent(turnGoal, successCriteria, authorVisible, anchorPath, '')
    String authorRequest = resolved.authorRequest?.toString()?.trim() ?: ''
    List<String> steps = resolved.steps instanceof List ? (List<String>) resolved.steps : []
    String criteria = resolved.successCriteria?.toString()?.trim() ?: ''
    if (!authorRequest && !(turnGoal ?: '').trim()) {
      return ''
    }

    StringBuilder sb = new StringBuilder()
    sb.append('## Intent\n\n')

    if (!steps.isEmpty()) {
      sb.append('**Your request:**\n')
      int i = 1
      for (String step : steps) {
        sb.append(i++).append('. ').append(step).append('\n')
      }
      sb.append('\n')
    } else if (authorRequest) {
      sb.append('**Your request:** ').append(normalizeInline(authorRequest)).append('\n\n')
    } else {
      String g = (resolved.turnGoal ?: turnGoal ?: '').trim()
      if (g) {
        sb.append('**Goal:** ').append(g).append('\n\n')
      }
    }

    String anchor = (anchorPath ?: '').trim()
    String rid = (recipeId ?: '').trim()
    if (anchor && !'generate_image'.equals(rid)) {
      sb.append('**On page:** `').append(anchor).append('`\n\n')
    }

    if ('generate_image'.equals(rid)) {
      criteria = 'You see the generated image in the Studio chat strip.'
    }

    if (criteria) {
      sb.append('**Success looks like:**\n')
      for (String bar : splitSuccessBars(criteria)) {
        sb.append('- ').append(bar).append('\n')
      }
      sb.append('\n')
    }

    String goalForGuards = (resolved.turnGoal ?: turnGoal ?: authorRequest ?: '').trim()
    List<String> willNot = deriveWillNot(goalForGuards, criteria, rid)
    if (!willNot.isEmpty()) {
      sb.append('**I will not:**\n')
      for (String line : willNot) {
        sb.append('- ').append(line).append('\n')
      }
      sb.append('\n')
    }
    sb.append('_Proceeding with tools…_\n')
    return sb.toString()
  }

  /**
   * True when router/LLM text summarizes process instead of the author's concrete outcome.
   */
  static boolean isWeakTurnGoal(String text) {
    String t = (text ?: '').trim().toLowerCase()
    if (!t) {
      return true
    }
    if (t.length() < 18) {
      return true
    }
    if (t.contains('involves multiple step')) {
      return true
    }
    if (t.contains('the task involves')) {
      return true
    }
    if (t.contains('retrieving current news') && !t.contains('headline')) {
      return true
    }
    if (t.contains('retriev') && t.contains('updat') && t.contains('generat') &&
      !t.contains('headline') && !t.contains('hero') && !t.contains('this page')) {
      return true
    }
    if (t.contains('routing mode:')) {
      return true
    }
    if (t.startsWith('execute intent recipe:')) {
      return true
    }
    if (t.contains('complete the author request')) {
      return true
    }
    return false
  }

  /** True when criteria are non-verifiable boilerplate. */
  static boolean isWeakSuccessCriteria(String text) {
    String t = (text ?: '').trim().toLowerCase()
    if (!t) {
      return true
    }
    if (t.contains('fully addressed with appropriate tools')) {
      return true
    }
    if (t.contains('no unrelated edits')) {
      return true
    }
    if (t.contains('author request is fully')) {
      return true
    }
    if (t == 'each step in the author request was completed with verifiable tool outcomes.') {
      return true
    }
    return false
  }

  static boolean looksMultiStepGoal(String turnGoal, String authorVisible = '') {
    String authorRequest = extractCleanAuthorRequest(authorVisible)
    if (deriveSteps(authorRequest, '').size() > 1) {
      return true
    }
    String combined = ((turnGoal ?: '') + ' ' + authorRequest).trim().toLowerCase()
    if (!combined) {
      return false
    }
    if (combined.contains(' then ') || combined.contains(';') || combined.contains('\n')) {
      return true
    }
    return combined.split(/\band\b/).length > 2
  }

  static List<String> deriveSteps(String authorVisible, String turnGoal) {
    String source = extractCleanAuthorRequest(authorVisible)
    if (!source) {
      source = (turnGoal ?: '').trim()
    }
    if (!source) {
      return []
    }
    List<String> raw = []
    for (String line : source.split(/\r?\n/)) {
      String t = line.trim()
      if (t) {
        raw.add(t)
      }
    }
    if (raw.size() <= 1) {
      String[] parts = source.split(/(?i)\s+then\s+|\s*;\s*/)
      if (parts.length > 1) {
        raw = []
        for (String p : parts) {
          String t = p.trim()
          if (t) {
            raw.add(t)
          }
        }
      }
    }
    List<String> out = []
    int cap = 12
    for (String r : raw) {
      if (out.size() >= cap) {
        break
      }
      String cleaned = r.replaceFirst(/^\d+[\).\]]\s*/, '').trim()
      if (cleaned.length() > 4) {
        out.add(cleaned)
      }
    }
    return out
  }

  static String extractCleanAuthorRequest(String authorVisible) {
    String v = AuthoringPreviewContext.extractAuthorCurrentRequestVisible(authorVisible ?: '')
    if (!v?.trim()) {
      v = (authorVisible ?: '').toString()
    }
    try {
      v = AuthoringPreviewContext.stripStudioInjectedPromptBlocks(v ?: '') ?: v
    } catch (Throwable ignored) {
    }
    return (v ?: '').trim()
  }

  private static String condenseAuthorGoal(String authorRequest, String anchorPath) {
    String normalized = normalizeInline(authorRequest)
    String anchor = (anchorPath ?: '').trim()
    if (anchor && normalized.toLowerCase().contains('this page')) {
      normalized = normalized.replaceAll(/(?i)\bthis page\b/, '`' + anchor + '`')
    }
    if (normalized.length() > 420) {
      List<String> steps = deriveSteps(authorRequest, '')
      if (steps.size() > 1) {
        return 'Complete the chained request on the open page: ' +
          steps.take(3).join(' → ') +
          (steps.size() > 3 ? ' → …' : '')
      }
      return normalized.substring(0, 417) + '…'
    }
    return normalized
  }

  private static String deriveSuccessCriteriaFromAuthor(
    String authorRequest,
    String anchorPath,
    List<String> steps
  ) {
    String lower = (authorRequest ?: '').toLowerCase()
    String anchor = (anchorPath ?: '').trim() ?: 'the anchored page'
    List<String> bars = []

    if (mentionsSearch(lower)) {
      bars.add(
        'A specific search/fetch result was chosen (not a generic homepage or index title) and its facts drive later steps'
      )
    }
    if (mentionsContentUpdate(lower)) {
      bars.add('Page copy at `' + anchor + '` was updated via WriteContent and verified with GetContent or preview')
    }
    if (mentionsHeroOrImagePersist(lower)) {
      bars.add(
        'Hero/page image at `' + anchor + '` was persisted in the repository via WriteContent (not chat-only); preview shows the new image'
      )
    }
    if (bars.isEmpty() && steps instanceof List && steps.size() > 1) {
      int n = 1
      for (String step : steps) {
        bars.add('Step ' + (n++) + ' done: ' + step)
        if (bars.size() >= 6) {
          break
        }
      }
    }
    if (bars.isEmpty()) {
      return 'The author request was fully completed with verifiable tool and repository outcomes.'
    }
    return bars.join('; ')
  }

  private static List<String> splitSuccessBars(String criteria) {
    List<String> out = []
    for (String part : criteria.split(/\s*;\s*/)) {
      String t = part.trim()
      if (t) {
        out.add(t)
      }
    }
    if (out.isEmpty() && criteria?.trim()) {
      out.add(criteria.trim())
    }
    return out
  }

  private static String normalizeInline(String s) {
    return (s ?: '').replaceAll(/\s+/, ' ').trim()
  }

  private static boolean mentionsSearch(String lower) {
    return lower.contains('search') || lower.contains('lookup') || lower.contains('find ') ||
      lower.contains('headline') || lower.contains("today's")
  }

  private static boolean mentionsContentUpdate(String lower) {
    return lower.contains('update') && (lower.contains('content') || lower.contains('copy') || lower.contains('page'))
  }

  private static boolean mentionsHeroOrImagePersist(String lower) {
    return lower.contains('hero') || (lower.contains('image') && lower.contains('update'))
  }

  static List<String> deriveWillNot(String turnGoal, String successCriteria, String recipeId = '') {
    String rid = (recipeId ?: '').trim()
    if ('generate_image'.equals(rid)) {
      return [
        'Call GetContent, WriteContent, or update_content on this chat-only image turn.',
        'Call GenerateImage again in the same turn for the same subject.',
        'Claim the image was saved to the CMS unless you also ran WriteContent at the author\'s request.'
      ]
    }
    List<String> lines = []
    lines.add(
      'Claim this turn is complete before every success bar above is met (repository read-back or preview as appropriate).'
    )
    lines.add(
      'Treat search snippets, page titles, or index URLs as verified facts without selecting a specific result that matches the goal.'
    )
    String combined = ((turnGoal ?: '') + ' ' + (successCriteria ?: '')).toLowerCase()
    if (combined.contains('write') || combined.contains('update') || combined.contains('persist') ||
      combined.contains('attach') || combined.contains('hero') || combined.contains('image')) {
      lines.add(
        'Stop after a chat-only generated image when the request requires updating the page hero/image field in the CMS.'
      )
    }
    if (mentionsSearch(combined)) {
      lines.add(
        'Use external lookup results in WriteContent without stating which result was chosen and why it satisfies the request.'
      )
    }
    return lines
  }
}
