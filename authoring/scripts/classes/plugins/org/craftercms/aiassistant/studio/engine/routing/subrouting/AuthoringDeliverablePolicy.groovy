package plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting

import java.util.regex.Pattern
import plugins.org.craftercms.aiassistant.studio.engine.context.AuthoringPreviewContext

/**
 * Post-router invariant guards: deliverable lifecycle and turn relation drive mode/recipe binding,
 * not surface keywords in the author message.
 */
final class AuthoringDeliverablePolicy {

  private static final Set CHAT_DELIVERABLES = ['chat_prose', 'chat_answer'] as Set
  private static final Set REPO_CREATE_DELIVERABLES = ['repo_create'] as Set

  /** Long-form creative briefs that should stay in chat unless the author asks to persist. */
  private static final Pattern CHAT_PROSE_AUTHOR_INTENT = Pattern.compile(
    '(?is)\\b(?:draft|write|compose|author|create|produce|prepare|develop)\\b.{0,64}\\b(?:blog|article|copy|prose|post|essay|brief|whitepaper|white\\s+paper)\\b|' +
      '\\b(?:blog|article|whitepaper|white\\s+paper)\\b.{0,64}\\b(?:copy|post|draft|piece)\\b|' +
      '\\bdraft\\s+a\\s+(?:substantive\\s+)?(?:blog|article)\\b'
  )

  /** Explicit repository write / publish language — overrides chat-prose inference. */
  private static final Pattern REPO_WRITE_AUTHOR_INTENT = Pattern.compile(
    '(?is)\\b(?:writecontent|write\\s+content|save\\s+(?:this|it)\\s+(?:to|on)|update\\s+(?:this|the)\\s+page|change\\s+(?:this|the)\\s+page|' +
      'put\\s+(?:this|it)\\s+(?:on|into)\\s+the\\s+(?:page|site|cms)|publish\\s+(?:this|the|now)|persist\\s+to\\s+(?:the\\s+)?(?:repo|cms|site))\\b|' +
      '\\b(?:redo|rewrite|refresh)\\s+(?:this|the)\\s+(?:page|homepage|home\\s+page)\\b'
  )

  private static final Pattern BROKEN_PREVIEW_ROUTER_REASON = Pattern.compile(
    '(?is)preview\\s*/\\s*render\\s+failure|broken\\s+preview|repair\\s+anchored\\s+page'
  )

  /** Author approves prior chat prose and asks to persist it as a new repository item. */
  private static final Pattern PERSIST_PRIOR_CHAT_DRAFT = Pattern.compile(
    '(?is)\\b(?:this\\s+looks\\s+good|looks\\s+great|that\\s+works|(?:please\\s+)?(?:save|create|make|publish)\\s+(?:this|it|a\\s+(?:new\\s+)?(?:technical\\s+)?(?:blog\\s+)?(?:post|article|page)))\\b|' +
      '\\bcreate\\s+(?:a\\s+)?(?:new\\s+)?(?:technical\\s+)?(?:blog\\s+)?post\\b|' +
      '\\b(?:save|publish)\\s+(?:this|it)\\s+(?:as\\s+)?(?:a\\s+)?(?:blog\\s+)?(?:post|article)\\b'
  )

  /** Correction that the assistant targeted the wrong page/item — keep repo_create intent. */
  private static final Pattern CREATE_TARGET_CORRECTION = Pattern.compile(
    '(?is)\\b(?:why\\s+(?:are|were)\\s+you\\s+(?:updating|editing|changing|writing|modifying)|' +
      'you\\s+(?:updated|changed|edited|wrote\\s+to)\\s+(?:the\\s+)?(?:wrong|home|homepage)|' +
      '(?:not|don\'?t)\\s+(?:update|edit|change|modify|overwrite)\\s+(?:the\\s+)?(?:home|homepage|index|this\\s+page)|' +
      'i\\s+(?:said|told\\s+you|asked\\s+(?:you\\s+)?to)\\s+(?:create|make|add)\\s+(?:a\\s+)?(?:new\\s+)?(?:technical\\s+)?(?:blog\\s+)?post|' +
      'create\\s+(?:a\\s+)?new\\s+(?:technical\\s+)?(?:blog\\s+)?post)\\b'
  )

  /** Primary deliverable each bundled recipe is allowed to serve. */
  private static final Map RECIPE_PRIMARY_DELIVERABLE = [
    web_research                    : 'external_research',
    site_content_research           : 'external_research',
    llm_research                    : 'chat_prose',
    open_page_inquiry               : 'repo_read',
    modify_page_content             : 'repo_write',
    restore_fields_from_version     : 'repo_write',
    revert_content_version          : 'repo_write',
    template_display_change         : 'repo_write',
    stylesheet_change               : 'repo_write',
    build_page_feature              : 'multi_step',
    publish_site                    : 'repo_write',
    publish_item                    : 'repo_write',
    translate_content_item          : 'repo_create',
    new_content_item                : 'repo_create',
    new_content_item_from_chat_draft: 'repo_create',
    generate_image                  : 'multi_step'
  ]

  private AuthoringDeliverablePolicy() {}

  /**
   * Applies deliverable-first invariants and merges session objective across turns.
   *
   * @param decision parsed router JSON (mutated copy returned)
   * @param authorVisible current-turn author text
   * @param wirePrompt full wire prompt (prior conversation + anchor)
   * @param priorSessionObjective sticky objective from the prior turn session bundle
   * @return normalized decision with {@code deliverable}, {@code turnRelation}, {@code sessionObjective}, etc.
   */
  static Map apply(
    Map decision,
    String authorVisible,
    String wirePrompt,
    String priorSessionObjective
  ) {
    if (!(decision instanceof Map)) {
      return decision ?: [:]
    }
    Map out = new LinkedHashMap(decision)

    out.deliverable = normalizeDeliverable(out.deliverable?.toString())
    out.turnRelation = normalizeTurnRelation(out.turnRelation?.toString())
    out.authorUnderstanding = normalizeText(out.authorUnderstanding)
    out.sessionObjective = normalizeText(out.sessionObjective) ?: normalizeText(priorSessionObjective)

    if (!out.deliverable) {
      out.deliverable = inferDeliverableFromDecision(out)
    }

    if (authorVisibleLooksLikeChatProseBrief(authorVisible)) {
      out.deliverable = 'chat_prose'
      out.turnRelation = out.turnRelation ?: 'new_request'
      forceChatOnly(out)
    }

    if (looksLikeShortCorrectionAgainstCreateRecipe(out, authorVisible, priorSessionObjective, wirePrompt)) {
      out.turnRelation = out.turnRelation ?: 'correction'
      if (looksLikeCreateTargetCorrection(authorVisible, wirePrompt)) {
        out.deliverable = 'repo_create'
        out.mode = 'recipe'
        if (!out.recipeId?.toString()?.trim()) {
          out.recipeId = 'new_content_item_from_chat_draft'
        }
      } else {
        out.deliverable = 'chat_prose'
        if (!out.sessionObjective?.trim() && priorSessionObjective?.trim()) {
          out.sessionObjective = priorSessionObjective.trim()
        }
      }
    }

    reconcileTurnGoalWithUnderstanding(out, authorVisible)

    if ('correction'.equals(out.turnRelation)) {
      applyCorrectionTurnPolicy(out, authorVisible, wirePrompt, priorSessionObjective)
    } else if ('approval_to_persist'.equals(out.turnRelation)) {
      if (!REPO_CREATE_DELIVERABLES.contains(out.deliverable?.toString())) {
        out.deliverable = 'repo_create'
      }
    } else if ('new_request'.equals(out.turnRelation) || 'refinement'.equals(out.turnRelation) || 'follow_up'.equals(out.turnRelation)) {
      refreshSessionObjective(out, priorSessionObjective)
    }

    if (CHAT_DELIVERABLES.contains(out.deliverable?.toString())) {
      forceChatOnly(out)
    }

    enforceRecipeDeliverableCompatibility(out)

    if (!out.sessionObjective?.trim()) {
      out.sessionObjective = out.authorUnderstanding?.trim() ?: out.turnGoal?.trim() ?: priorSessionObjective?.trim() ?: ''
    }

    return out
  }

  /**
   * Re-applies chat-deliverable and recipe compatibility invariants after server-side routing corrections.
   */
  static Map finalizeAfterCorrections(Map decision, String authorVisible = null) {
    if (!(decision instanceof Map)) {
      return decision ?: [:]
    }
    Map out = new LinkedHashMap(decision)
    if (!out.deliverable) {
      out.deliverable = inferDeliverableFromDecision(out)
    }
    if (authorVisibleLooksLikeChatProseBrief(authorVisible)) {
      out.deliverable = 'chat_prose'
      forceChatOnly(out)
      clearRepoRepairRouterReason(out)
    }
    if (CHAT_DELIVERABLES.contains(out.deliverable?.toString())) {
      forceChatOnly(out)
      clearRepoRepairRouterReason(out)
    }
    enforceRecipeDeliverableCompatibility(out)
    return out
  }

  /**
   * When deliverable is chat-only, server-side repo routing corrections must not run.
   */
  static boolean shouldSuppressRepoRoutingCorrections(String authorVisible, Map decision) {
    if (decision instanceof Map && isChatDeliverable(decision.deliverable?.toString())) {
      return true
    }
    return authorVisibleLooksLikeChatProseBrief(authorVisible)
  }

  /**
   * True when the author turn is a long creative-writing brief without explicit persist-to-CMS language.
   */
  static boolean authorVisibleLooksLikeChatProseBrief(String authorVisible) {
    String v = AuthoringPreviewContext.stripStudioInjectedPromptBlocks((authorVisible ?: '').toString())?.trim()
    if (!v || v.length() < 350) {
      return false
    }
    if (REPO_WRITE_AUTHOR_INTENT.matcher(v).find()) {
      return false
    }
    return CHAT_PROSE_AUTHOR_INTENT.matcher(v).find()
  }

  /**
   * Forces {@code mode=recipe} for approval-to-persist turns so prefetch overlays (banned anchor path,
   * suggested new item path, form validation plan) apply — plan defer skips those guards.
   */
  static Map bindApprovalToPersistRecipeMode(
    Map decision,
    String authorVisible,
    String wirePrompt,
    String priorSessionObjective
  ) {
    if (!(decision instanceof Map)) {
      return decision ?: [:]
    }
    Map out = new LinkedHashMap(decision)
    boolean persistIntent = 'approval_to_persist'.equals(out.turnRelation?.toString()?.trim()) ||
      authorVisibleLooksLikePersistPriorChatDraft(authorVisible, wirePrompt)
    if (!persistIntent) {
      return out
    }
    String rid = out.recipeId?.toString()?.trim()
    if (!rid) {
      rid = 'new_content_item_from_chat_draft'
      out.recipeId = rid
    }
    if ('new_content_item_from_chat_draft'.equals(rid) || 'new_content_item'.equals(rid)) {
      out.mode = 'recipe'
      out.deliverable = 'repo_create'
      if (!out.turnRelation?.toString()?.trim()) {
        out.turnRelation = 'approval_to_persist'
      }
      if (!(out.confidence instanceof Number) || ((Number) out.confidence).doubleValue() < 0.55d) {
        out.confidence = 0.9d
      }
      if (!out.sessionObjective?.trim() && priorSessionObjective?.trim()) {
        out.sessionObjective = priorSessionObjective.trim()
      }
    }
    return out
  }

  /** True when the author is asking to save/create from prior chat prose (Turn 3 style). */
  static boolean authorVisibleLooksLikePersistPriorChatDraft(String authorVisible, String wirePrompt) {
    String req = AuthoringIntentCard.extractCleanAuthorRequest(authorVisible ?: '')?.trim() ?: ''
    if (!req || req.length() > 280) {
      return false
    }
    if (!PERSIST_PRIOR_CHAT_DRAFT.matcher(req).find()) {
      return false
    }
    String prior = AuthoringPreviewContext.extractPriorConversationBody(wirePrompt ?: '')?.trim() ?: ''
    return PriorConversationDraftExtract.priorConversationHasMaterializableAssistantReply(prior, 200)
  }

  /** True when the author is correcting wrong repo target (e.g. home page vs new blog post). */
  static boolean looksLikeCreateTargetCorrection(String authorVisible, String wirePrompt) {
    String req = AuthoringIntentCard.extractCleanAuthorRequest(authorVisible ?: '')?.trim() ?: ''
    if (!req) {
      return false
    }
    return CREATE_TARGET_CORRECTION.matcher(req).find()
  }

  /** @return true when {@code deliverable} is chat-only prose or Q&A. */
  static boolean isChatDeliverable(String deliverable) {
    return CHAT_DELIVERABLES.contains((deliverable ?: '').trim())
  }

  private static void applyCorrectionTurnPolicy(
    Map out,
    String authorVisible,
    String wirePrompt,
    String priorSessionObjective
  ) {
    if (!out.sessionObjective?.trim() && priorSessionObjective?.trim()) {
      out.sessionObjective = priorSessionObjective.trim()
    }
    if (!out.sessionObjective?.trim()) {
      out.sessionObjective = inferObjectiveFromPriorConversation(wirePrompt)?.trim() ?: ''
    }

    if (out.sessionObjective?.trim() && shouldReplaceTurnGoalWithObjective(out, authorVisible)) {
      out.turnGoal = out.sessionObjective.trim()
    }

    if (!'approval_to_persist'.equals(out.turnRelation) && rejectsRepoCreateOnCorrection(out, authorVisible, wirePrompt)) {
      out.deliverable = 'chat_prose'
      forceChatOnly(out)
    }

    if (AuthoringIntentCard.isWeakSuccessCriteria(out.successCriteria?.toString()) && out.sessionObjective?.trim()) {
      out.successCriteria = chatProseSuccessCriteria(out.sessionObjective)
    }
  }

  private static boolean rejectsRepoCreateOnCorrection(Map out, String authorVisible, String wirePrompt) {
    if (!REPO_CREATE_DELIVERABLES.contains(out.deliverable?.toString())) {
      return false
    }
    if (looksLikeCreateTargetCorrection(authorVisible, wirePrompt)) {
      return false
    }
    if ('new_content_item_from_chat_draft'.equals(out.recipeId?.toString()?.trim())) {
      return false
    }
    if ('new_content_item'.equals(out.recipeId?.toString()?.trim())) {
      return true
    }
    if ('recipe'.equals(out.mode?.toString()) && REPO_CREATE_DELIVERABLES.contains(out.deliverable?.toString())) {
      return true
    }
    return false
  }

  private static void refreshSessionObjective(Map out, String priorSessionObjective) {
    String candidate = out.sessionObjective?.trim() ?:
      out.authorUnderstanding?.trim() ?:
      out.turnGoal?.trim() ?:
      priorSessionObjective?.trim() ?: ''
    if (candidate && !'correction'.equals(out.turnRelation)) {
      out.sessionObjective = candidate
    }
  }

  private static boolean looksLikeShortCorrectionAgainstCreateRecipe(
    Map out,
    String authorVisible,
    String priorSessionObjective,
    String wirePrompt
  ) {
    String objective = priorSessionObjective?.trim() ?: out.sessionObjective?.trim() ?: ''
    if (objective.length() < 40) {
      return false
    }
    String rid = out.recipeId?.toString()?.trim()
    if (!'new_content_item'.equals(rid) && !'new_content_item_from_chat_draft'.equals(rid)) {
      if (!('recipe'.equals(out.mode?.toString()) && REPO_CREATE_DELIVERABLES.contains(out.deliverable?.toString()))) {
        return false
      }
    }
    String req = AuthoringIntentCard.extractCleanAuthorRequest(authorVisible ?: '')?.trim() ?: ''
    if (req.length() < 8 || req.length() > Math.max(280, (int) (objective.length() * 0.55))) {
      return false
    }
    if (looksLikeCreateTargetCorrection(authorVisible, wirePrompt)) {
      return false
    }
    String prior = AuthoringPreviewContext.extractPriorConversationBody(wirePrompt ?: '')?.trim() ?: ''
    return prior.length() >= 80
  }

  private static void reconcileTurnGoalWithUnderstanding(Map out, String authorVisible) {
    String understanding = out.authorUnderstanding?.toString()?.trim() ?: ''
    if (!understanding) {
      return
    }
    String goal = out.turnGoal?.toString()?.trim() ?: ''
    if (!goal || AuthoringIntentCard.isWeakTurnGoal(goal) || turnGoalEchoesCurrentRequest(goal, authorVisible)) {
      out.turnGoal = understanding
    }
  }

  private static boolean shouldReplaceTurnGoalWithObjective(Map out, String authorVisible) {
    String goal = out.turnGoal?.toString()?.trim() ?: ''
    if (!goal) {
      return true
    }
    if (AuthoringIntentCard.isWeakTurnGoal(goal)) {
      return true
    }
    return turnGoalEchoesCurrentRequest(goal, authorVisible)
  }

  private static boolean turnGoalEchoesCurrentRequest(String turnGoal, String authorVisible) {
    String goal = normalizeCompare(turnGoal)
    String req = normalizeCompare(AuthoringIntentCard.extractCleanAuthorRequest(authorVisible ?: ''))
    if (!goal || !req) {
      return false
    }
    if (goal == req) {
      return true
    }
    if (req.length() >= 24 && goal.contains(req)) {
      return true
    }
    if (goal.startsWith('the user said:') || goal.startsWith('the user is asking:')) {
      return true
    }
    return false
  }

  private static String normalizeCompare(String s) {
    return (s ?: '').replaceAll(/\s+/, ' ').trim().toLowerCase(Locale.ROOT)
  }

  private static void enforceRecipeDeliverableCompatibility(Map out) {
    if (!'recipe'.equals(out.mode?.toString())) {
      return
    }
    String rid = out.recipeId?.toString()?.trim()
    if (!rid) {
      return
    }
    String recipeDeliverable = RECIPE_PRIMARY_DELIVERABLE.get(rid)
    if (!recipeDeliverable) {
      return
    }
    String chosen = out.deliverable?.toString()?.trim() ?: ''
    if (!chosen) {
      out.deliverable = recipeDeliverable
      return
    }
    if (CHAT_DELIVERABLES.contains(chosen) && REPO_CREATE_DELIVERABLES.contains(recipeDeliverable)) {
      forceChatOnly(out)
      return
    }
    if (CHAT_DELIVERABLES.contains(chosen) && 'repo_write'.equals(recipeDeliverable)) {
      forceChatOnly(out)
      return
    }
    if ('chat_prose'.equals(recipeDeliverable) && REPO_CREATE_DELIVERABLES.contains(chosen)) {
      out.deliverable = 'chat_prose'
      forceChatOnly(out)
    }
  }

  private static void forceChatOnly(Map out) {
    out.mode = 'chat_only'
    out.recipeId = null
    out.toolName = null
    if (!CHAT_DELIVERABLES.contains(out.deliverable?.toString())) {
      out.deliverable = 'chat_prose'
    }
    if (AuthoringIntentCard.isWeakSuccessCriteria(out.successCriteria?.toString())) {
      out.successCriteria = chatProseSuccessCriteria(out.sessionObjective?.toString() ?: out.turnGoal?.toString() ?: '')
    }
  }

  private static void clearRepoRepairRouterReason(Map out) {
    String reason = out.reason?.toString()?.trim() ?: ''
    if (!reason || !BROKEN_PREVIEW_ROUTER_REASON.matcher(reason).find()) {
      return
    }
    String objective = out.sessionObjective?.toString()?.trim() ?:
      out.authorUnderstanding?.toString()?.trim() ?:
      out.turnGoal?.toString()?.trim() ?: ''
    out.reason = objective ?
      'Author wants substantive chat prose this turn — no repository writes unless they explicitly ask to persist.' :
      'Substantive chat prose this turn — no repository writes unless the author explicitly asks to persist.'
    if (AuthoringIntentCard.isWeakSuccessCriteria(out.successCriteria?.toString())) {
      out.successCriteria = chatProseSuccessCriteria(objective)
    }
  }

  private static String chatProseSuccessCriteria(String objective) {
    String obj = (objective ?: '').trim()
    if (!obj) {
      return 'Substantive reply in chat matching the author\'s objective, tone, and constraints; no repository changes.'
    }
    if (obj.length() > 220) {
      obj = obj.substring(0, 217) + '…'
    }
    return 'Substantive chat prose that fulfills: ' + obj + ' — no CMS tools or repository writes this turn.'
  }

  private static String inferDeliverableFromDecision(Map out) {
    String mode = out.mode?.toString()?.trim()?.toLowerCase() ?: ''
    if ('chat_only'.equals(mode)) {
      return 'chat_prose'
    }
    String rid = out.recipeId?.toString()?.trim()
    if (rid) {
      return RECIPE_PRIMARY_DELIVERABLE.get(rid) ?: 'multi_step'
    }
    if ('tool'.equals(mode)) {
      return 'external_research'
    }
    return 'multi_step'
  }

  private static String inferObjectiveFromPriorConversation(String wirePrompt) {
    String prior = AuthoringPreviewContext.extractPriorConversationBody(wirePrompt ?: '')?.trim()
    if (!prior) {
      return ''
    }
    List<Map> turns = AuthoringPreviewContext.parsePriorConversationTurns(prior)
    String best = ''
    for (Map turn : turns) {
      if (!'user'.equals(turn.role?.toString())) {
        continue
      }
      String text = turn.text?.toString()?.trim() ?: ''
      if (text.length() > best.length() && text.length() >= 80) {
        best = text
      }
    }
    if (best.length() > 600) {
      best = best.substring(0, 597) + '…'
    }
    return best
  }

  private static String normalizeDeliverable(String raw) {
    String d = (raw ?: '').trim().toLowerCase(Locale.ROOT)
    if (!d || 'null'.equals(d)) {
      return ''
    }
    switch (d) {
      case 'chat':
      case 'chat-only':
      case 'prose':
      case 'chat_prose':
        return 'chat_prose'
      case 'chat_answer':
      case 'answer':
      case 'qa':
        return 'chat_answer'
      case 'repo_read':
      case 'read':
        return 'repo_read'
      case 'repo_write':
      case 'write':
      case 'edit':
        return 'repo_write'
      case 'repo_create':
      case 'create':
        return 'repo_create'
      case 'external_research':
      case 'research':
        return 'external_research'
      case 'multi_step':
      case 'plan':
        return 'multi_step'
      default:
        return d
    }
  }

  private static String normalizeTurnRelation(String raw) {
    String t = (raw ?: '').trim().toLowerCase(Locale.ROOT)
    if (!t || 'null'.equals(t)) {
      return ''
    }
    switch (t) {
      case 'new':
      case 'new_request':
      case 'initial':
        return 'new_request'
      case 'refinement':
      case 'refine':
        return 'refinement'
      case 'correction':
      case 'pushback':
      case 'complaint':
        return 'correction'
      case 'approval_to_persist':
      case 'persist':
      case 'save':
        return 'approval_to_persist'
      case 'follow_up':
      case 'followup':
        return 'follow_up'
      default:
        return t
    }
  }

  private static String normalizeText(Object raw) {
    String t = raw?.toString()?.trim() ?: ''
    return 'null'.equalsIgnoreCase(t) ? '' : t
  }
}
