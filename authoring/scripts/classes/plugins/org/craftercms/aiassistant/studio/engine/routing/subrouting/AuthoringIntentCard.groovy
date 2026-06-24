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
    String routerReason = '',
    String routingMode = '',
    String authorUnderstanding = '',
    String sessionObjective = '',
    String turnRelation = ''
  ) {
    String authorRequest = extractCleanAuthorRequest(authorVisible)
    List<String> steps = deriveSteps(authorRequest, '')
    String goal = (routerTurnGoal ?: '').trim()
    String criteria = (routerSuccessCriteria ?: '').trim()
    String reason = (routerReason ?: '').trim()
    String understanding = (authorUnderstanding ?: '').trim()
    String objective = (sessionObjective ?: '').trim()
    String relation = (turnRelation ?: '').trim()
    boolean chatOnly = 'chat_only'.equals((routingMode ?: '').trim())
    boolean correction = 'correction'.equals(relation)

    if (understanding && (AuthoringIntentCard.isWeakTurnGoal(goal) || goalEchoesAuthorRequest(goal, authorRequest))) {
      goal = understanding
    }
    if (correction && objective && (AuthoringIntentCard.isWeakTurnGoal(goal) || goalEchoesAuthorRequest(goal, authorRequest))) {
      goal = objective
    }

    if (chatOnly) {
      if (isWeakTurnGoal(goal) && reason && !isWeakTurnGoal(reason)) {
        goal = reason
      }
      if (understanding && isWeakTurnGoal(goal)) {
        goal = understanding
      }
      if (isWeakSuccessCriteria(criteria)) {
        if (objective) {
          criteria = 'Substantive chat prose that fulfills the session objective; no CMS tools or repository writes.'
        } else {
          criteria = ''
        }
      }
      return [
        turnGoal        : goal ?: '',
        successCriteria : criteria ?: '',
        authorRequest   : authorRequest ?: '',
        steps           : steps ?: [],
        authorUnderstanding: understanding ?: '',
        sessionObjective: objective ?: ''
      ]
    }

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
      steps            : steps ?: [],
      authorUnderstanding: understanding ?: '',
      sessionObjective : objective ?: ''
    ]
  }

  private static boolean goalEchoesAuthorRequest(String goal, String authorRequest) {
    String g = (goal ?: '').replaceAll(/\s+/, ' ').trim().toLowerCase(Locale.ROOT)
    String r = (authorRequest ?: '').replaceAll(/\s+/, ' ').trim().toLowerCase(Locale.ROOT)
    if (!g || !r) {
      return false
    }
    if (g == r) {
      return true
    }
    return g.startsWith('the user said:') || g.startsWith('the user is asking:')
  }

  /**
   * Intent card narrative: router LLM first; keyword heuristics only when the router omitted a usable goal.
   */
  static String elaborateAuthorIntentNarrative(
    String authorRequest,
    String anchorPath,
    List<String> steps,
    String recipeId = '',
    String routerTurnGoal = '',
    String routerReason = '',
    String routingMode = '',
    String authorUnderstanding = ''
  ) {
    String understanding = (authorUnderstanding ?: '').trim()
    if (understanding) {
      return understanding
    }

    String mode = (routingMode ?: '').trim()
    if ('chat_only'.equals(mode)) {
      String fromRouter = intentNarrativeFromRouterLlm(routerTurnGoal, routerReason, mode)
      if (fromRouter?.trim() && !isWeakTurnGoal(fromRouter)) {
        return fromRouter.trim()
      }
      return ''
    }

    String rid = (recipeId ?: '').trim()
    if ('generate_image'.equals(rid)) {
      return 'The user wants me to generate an image for this turn and show it in the Studio chat strip. ' +
        'I will create the image from their description and reply in short prose without unnecessary CMS writes ' +
        'unless they explicitly asked to update a page field.'
    }

    String routerGoal = (routerTurnGoal ?: '').trim()
    if (!routerGoal && (routerReason ?: '').trim() && !isWeakTurnGoal(routerReason)) {
      routerGoal = routerReason.trim()
    }
    if (routerGoal && !isWeakTurnGoal(routerGoal)) {
      return intentNarrativeFromRouterLlm(routerGoal, routerReason, mode)
    }

    return elaborateAuthorIntentHeuristicFallback(authorRequest, anchorPath, steps)
  }

  /**
   * @deprecated Prefer router {@code authorUnderstanding}; kept for offline parity tests only.
   */
  static String chatOnlyIntentFromAuthorRequest(String authorRequest) {
    String req = normalizeInline(authorRequest)?.trim()
    if (!req) {
      return ''
    }
    String opinionTopic = extractOpinionQuestionTopic(req)
    if (opinionTopic) {
      return "The author wants to know what I think about ${opinionTopic}."
    }
    if (req.endsWith('?')) {
      return "The author is asking a question about: ${condenseAuthorGoal(req, '')}."
    }
    return ''
  }

  /** e.g. "what do you think of baseball?" → "baseball" */
  private static String extractOpinionQuestionTopic(String req) {
    if (!req?.trim()) {
      return ''
    }
    def m = (req.trim() =~ /(?i)^what\s+do\s+you\s+think\s+(?:of|about)\s+(.+?)\??\s*$/)
    if (m.matches()) {
      return m[0][1]?.toString()?.trim() ?: ''
    }
    m = (req.trim() =~ /(?i)^what(?:'s| is)\s+your\s+(?:take|opinion)\s+(?:on|about)\s+(.+?)\??\s*$/)
    if (m.matches()) {
      return m[0][1]?.toString()?.trim() ?: ''
    }
    return ''
  }

  /**
   * Displays the intent router LLM's {@code turnGoal} / {@code reason} — no keyword pattern matching.
   */
  private static String intentNarrativeFromRouterLlm(
    String routerTurnGoal,
    String routerReason,
    String routingMode
  ) {
    String goal = (routerTurnGoal ?: '').trim()
    String reason = (routerReason ?: '').trim()
    if (!goal && reason && !isWeakTurnGoal(reason)) {
      goal = reason
      reason = ''
    }
    if (!goal || isWeakTurnGoal(goal)) {
      return 'chat_only'.equals((routingMode ?: '').trim()) ?
        'Conversational turn — respond naturally in prose.' :
        ''
    }

    StringBuilder sb = new StringBuilder()
    if ('chat_only'.equals((routingMode ?: '').trim())) {
      sb.append(goal)
    } else {
      sb.append('This turn: ').append(goal)
    }
    if (!goal.endsWith('.')) {
      sb.append('.')
    }
    if (reason && !reason.equalsIgnoreCase(goal) && !isWeakTurnGoal(reason)) {
      sb.append(' ').append(reason)
      if (!reason.endsWith('.')) {
        sb.append('.')
      }
    }
    return sb.toString()
  }

  /** Used only when the router LLM did not supply a usable {@code turnGoal}. */
  private static String elaborateAuthorIntentHeuristicFallback(
    String authorRequest,
    String anchorPath,
    List<String> steps
  ) {
    String req = normalizeInline(authorRequest)
    if (!req) {
      return ''
    }
    String lower = req.toLowerCase(Locale.ROOT)
    String anchor = (anchorPath ?: '').trim()
    String pagePhrase = anchor ?
      "the copy and content on this page (`${anchor}`)" :
      'the copy and content on this page'

    if (steps instanceof List && steps.size() > 1) {
      StringBuilder chained = new StringBuilder(
        'The user wants me to work through a chained request on this page: '
      )
      int cap = Math.min(steps.size(), 4)
      for (int i = 0; i < cap; i++) {
        if (i > 0) {
          chained.append('; then ')
        }
        chained.append(steps.get(i))
      }
      if (steps.size() > cap) {
        chained.append('; and additional steps')
      }
      chained.append(
        '. I will complete each step with verifiable tool outcomes before moving to the next.'
      )
      return chained.toString()
    }

    boolean search = mentionsSearch(lower)
    boolean contentUpdate = mentionsContentUpdate(lower)
    boolean heroImage =
      mentionsHeroOrImagePersist(lower) ||
        (lower.contains('generate') && lower.contains('image'))

    StringBuilder narrative = new StringBuilder('The user wants me to ')
    if (search && contentUpdate && heroImage) {
      narrative.append('find current, relevant source material, update ').append(pagePhrase)
      narrative.append(
        ' with accurate copy based on what I find, generate matching visual assets, and persist those updates in the CMS.'
      )
    } else if (search && contentUpdate) {
      narrative.append('research the latest relevant information and update ').append(pagePhrase)
      String topic = extractAboutTopicPhrase(req)
      if (topic) {
        narrative.append(' with specific events and details about ').append(topic).append('.')
      } else {
        narrative.append(' with accurate, specific details from trustworthy sources.')
      }
      narrative.append(
        ' I will search for news, read source pages, compare what I find with what I know (and whether it is still current), ' +
        'formulate an overall page idea, then write distinct copy for each field—headlines, paragraphs, alt text, and the rest—before saving.'
      )
    } else if (contentUpdate) {
      narrative.append('update ').append(pagePhrase)
      String topic = extractAboutTopicPhrase(req)
      if (topic) {
        narrative.append(' with specific events and details about ').append(topic).append('.')
      } else {
        narrative.append(' to reflect what they described.')
      }
      boolean topicalNews =
        topic ||
          lower.contains('latest') ||
          lower.contains('news') ||
          lower.contains('development') ||
          lower.contains('headline')
      if (topicalNews && heroImage) {
        narrative.append(
          ' I will identify specific, interesting developments, generate copy and visual assets to highlight them, and update the page elements accordingly.'
        )
      } else if (topicalNews) {
        narrative.append(
          ' I will search for current sources, read them, synthesize a clear page angle, and write field-appropriate copy—not generic filler.'
        )
      } else if (heroImage) {
        narrative.append(
          ' I will identify what to highlight, generate copy and assets as needed, and update the page elements accordingly.'
        )
      } else {
        narrative.append(' I will draft focused copy and update the page elements accordingly.')
      }
    } else if (heroImage) {
      narrative.append('generate visual assets and update image fields on ')
      narrative.append(anchor ? "`${anchor}`" : 'this page')
      narrative.append(' to match their request.')
    } else if (search) {
      narrative.append(
        'look up specific, verifiable information related to their request and apply what I find with clear sourcing.'
      )
    } else {
      narrative.append('help with their request on ')
      narrative.append(anchor ? "`${anchor}`" : 'the open page')
      narrative.append('. I will interpret what they need, use the right CMS tools, and verify repository and preview outcomes before finishing.')
    }
    if (!narrative.toString().contains(' I will ')) {
      narrative.append(' I will use the appropriate tools and verify outcomes before finishing.')
    }
    return narrative.toString()
  }

  /**
   * Markdown shown in chat before tools run (SSE {@code intent-recipe-routing} text + telemetry field).
   * Guardrails ({@code I will not}) are emitted separately as {@code intentCardWillNot} for a collapsible UI block.
   */
  static String formatCardMarkdown(
    String turnGoal,
    String successCriteria,
    String anchorPath,
    String authorVisible,
    String recipeId = '',
    String routingMode = '',
    String routerReason = '',
    String authorUnderstanding = ''
  ) {
    Map resolved = resolveAuthorIntent(
      turnGoal,
      successCriteria,
      authorVisible,
      anchorPath,
      routerReason,
      routingMode,
      authorUnderstanding,
      '',
      ''
    )
    String authorRequest = resolved.authorRequest?.toString()?.trim() ?: ''
    List<String> steps = resolved.steps instanceof List ? (List<String>) resolved.steps : []
    String criteria = resolved.successCriteria?.toString()?.trim() ?: ''
    if (!authorRequest && !(turnGoal ?: '').trim()) {
      return ''
    }

    String resolvedGoal = (resolved.turnGoal ?: turnGoal ?: '').trim()
    boolean chatOnly = 'chat_only'.equals((routingMode ?: '').trim())
    String elaboration = elaborateAuthorIntentNarrative(
      authorRequest,
      anchorPath,
      steps,
      recipeId,
      resolvedGoal,
      routerReason,
      routingMode,
      resolved.authorUnderstanding?.toString() ?: authorUnderstanding
    )
    if (!elaboration?.trim()) {
      elaboration = resolvedGoal ?: condenseAuthorGoal(authorRequest, anchorPath)
    }

    StringBuilder sb = new StringBuilder()
    sb.append('## Intent\n\n')
    sb.append(elaboration.trim()).append('\n\n')

    String anchor = (anchorPath ?: '').trim()
    String rid = (recipeId ?: '').trim()
    if (anchor && !'generate_image'.equals(rid) && !chatOnly) {
      sb.append('**On page:** `').append(anchor).append('`\n\n')
    }

    if ('generate_image'.equals(rid)) {
      criteria = 'You see the generated image in the Studio chat strip.'
    }

    if (chatOnly && (isWeakSuccessCriteria(criteria) || !criteria?.trim())) {
      criteria = ''
    }

    if (criteria) {
      sb.append('**Success looks like:**\n')
      for (String bar : splitSuccessBars(criteria)) {
        sb.append('- ').append(bar).append('\n')
      }
      sb.append('\n')
    }

    if (chatOnly) {
      sb.append('_Replying in chat — no CMS tools this turn._\n')
    } else {
      sb.append('_Proceeding with tools…_\n')
    }
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
    if (t.contains('optional short phrase')) {
      return true
    }
    if (t.startsWith('optional ')) {
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

  /** Short execution goal from intent elaboration (first sentence, capped length). */
  static String condenseElaborationForExecutionGoal(String elaboration) {
    String e = (elaboration ?: '').trim()
    if (!e) {
      return ''
    }
    int dot = e.indexOf('. ')
    if (dot > 40 && dot < 380) {
      return e.substring(0, dot + 1)
    }
    if (e.length() > 420) {
      return e.substring(0, 417) + '…'
    }
    return e
  }

  /** Short execution goal from author text without echoing frustration verbatim. */
  static String condenseAuthorGoalForExecution(String authorRequest, String anchorPath) {
    return condenseAuthorGoal(authorRequest, anchorPath)
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
    String lower = (authorRequest ?: '').toLowerCase(Locale.ROOT)
    String anchor = (anchorPath ?: '').trim() ?: 'the anchored page'
    List<String> bars = []

    if (AuthoringPreviewContext.authorVisibleReportsBrokenPreviewRepair(authorRequest)) {
      bars.add('GetPreviewHtml returns HTTP 200 with no FreeMarker or rendering errors in the HTML body')
      bars.add(
        'GetContent read-back at `' + anchor + '` is a valid full item document (including content-type) after WriteContent'
      )
      return bars.join('; ')
    }
    if (AuthoringPreviewContext.authorVisibleIsAcknowledgmentOrPraise(authorRequest)) {
      return 'A brief, natural thank-you — no ## Plan checklist or CMS tools.'
    }
    if (mentionsFollowUpIncompleteWork(lower)) {
      bars.add('WriteContent persisted the requested changes at `' + anchor + '` with verifiable read-back')
      bars.add('GetPreviewHtml confirms HTTP 200 preview reflecting the saved copy')
      return bars.join('; ')
    }

    if (mentionsSearch(lower)) {
      bars.add(
        'Sources were searched and read; fetched facts were synthesized into a clear page idea before writing'
      )
    }
    if (mentionsContentUpdate(lower)) {
      bars.add(
        'Page copy at `' + anchor + '` uses distinct, field-appropriate text (headlines, body, alt text) grounded in research — verified via preview'
      )
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

  /** Topic phrase after "about", "regarding", "on", or "for" in the author request. */
  private static String extractAboutTopicPhrase(String authorRequest) {
    String req = normalizeInline(authorRequest)
    if (!req) {
      return ''
    }
    def m = (req =~ /(?i)\b(?:about|regarding|on|for)\s+(.+?)(?:\.|$)/)
    if (m.find()) {
      String topic = m.group(1)?.toString()?.trim() ?: ''
      if (topic.length() > 3 && topic.length() < 140) {
        return topic
      }
    }
    return ''
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

  /** Prior turn promised work; author is asking why it did not land in the repository. */
  private static boolean mentionsFollowUpIncompleteWork(String lower) {
    if (!lower) {
      return false
    }
    if (lower.contains('talked about') && (lower.contains("didn't") || lower.contains('didnt') || lower.contains('not do'))) {
      return true
    }
    if (lower.contains('what happened')) {
      boolean repositoryFollowUp =
        lower.contains('update') || lower.contains('change') || lower.contains('write') ||
          lower.contains('save') || lower.contains('page') || lower.contains('content') ||
          lower.contains('cms') || lower.contains('repository') || lower.contains('preview') ||
          lower.contains('plan')
      if (repositoryFollowUp) {
        return true
      }
    }
    if (lower.contains('said you would') || lower.contains('you said but')) {
      return true
    }
    if (lower.contains('without doing') || lower.contains('did not do')) {
      return true
    }
    if (lower.contains('plan only') || lower.contains('just a plan')) {
      return true
    }
    return false
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
    if ('restore_fields_from_version'.equals(rid)) {
      return [
        'Call revert_change — selective restore merges fields from history into HEAD via GetContent + WriteContent only.',
        'Call GenerateImage when the author wants a prior image re-inserted or says they do not want a new image.',
        'Claim success while GetPreviewHtml returns HTTP 500 or FreeMarker/rendering errors.'
      ]
    }
    if ('revert_content_version'.equals(rid)) {
      return [
        'Use revert_change when the author only wanted copy or an image field restored — use restore_fields_from_version instead.',
        'Call GenerateImage to fix a wrong hero image when a historical hero_image_s path exists in version history.'
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
    if (combined.contains('500') || combined.contains('http 500') ||
      (combined.contains('preview') && combined.contains('error')) ||
      combined.contains('broken preview') || combined.contains('rendering error')) {
      lines.add(
        'Claim the page is fixed or preview looks good while GetPreviewHtml still returns HTTP 500 or FreeMarker/rendering errors.'
      )
      lines.add(
        'Reply chat-only or with a plan when the author reported a broken preview — run GetContent, repair XML, WriteContent, and re-check preview.'
      )
    }
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
