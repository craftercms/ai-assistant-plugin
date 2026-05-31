package plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting

import plugins.org.craftercms.aiassistant.studio.engine.context.AuthoringPreviewContext

/**
 * Author turn goal: what the author wants accomplished on <strong>this chat turn only</strong>,
 * resolved at intent routing and carried through the tools loop.
 *
 * <p>{@link plugins.org.craftercms.aiassistant.studio.engine.routing.Router#matchPass} stores
 * {@code turnGoal} and {@code successCriteria} from the router LLM (or fallbacks). {@link #wireIntoRouteResult}
 * prepends a {@code [Studio — turn goal …]} block to {@code userTextForToolsLoop} and copies fields to
 * {@code intentRecipeRoutingTelemetry} and the session bundle. {@link #appendToSystemWireMessage} and
 * {@link #formatMidLoopReminder} keep the executor aligned during multi-step tool rounds.</p>
 */
final class AuthoringTurnGoal {

  /** Utility class; not for instantiation. */
  private AuthoringTurnGoal() {}

  /**
   * Resolves the turn goal and success criteria from the router decision, with fallbacks when the LLM omitted them.
   *
   * @param decision parsed router JSON ({@link AuthoringIntentRecipeRouter#parseRouterJson})
   * @param authorVisible current-turn author text ({@code Current request:} slice)
   * @param anchorPath anchored repository path when preview context supplies one
   * @param recipeId matched or proposed recipe id (may be null)
   * @param routingMode router mode ({@code chat_only} | {@code recipe} | {@code tool} | {@code plan})
   * @return map with {@code turnGoal} and {@code successCriteria} (strings, may be empty)
   */
  static Map resolveFromRouterDecision(
    Map decision,
    String authorVisible,
    String anchorPath,
    String recipeId,
    String routingMode
  ) {
    String goal = decision?.turnGoal?.toString()?.trim() ?: ''
    String criteria = decision?.successCriteria?.toString()?.trim() ?: ''
    if (!goal) {
      goal = deriveFallbackGoal(authorVisible, anchorPath, recipeId, routingMode, decision?.reason?.toString())
    }
    if (!criteria) {
      criteria = deriveFallbackSuccessCriteria(recipeId, routingMode, anchorPath, authorVisible)
    }
    return [turnGoal: goal ?: '', successCriteria: criteria ?: '']
  }

  /**
   * Prepends the formatted turn-goal block to {@code userTextForToolsLoop} and stores goal fields on the session bundle.
   *
   * @param result route outcome map mutated in place ({@code authorTurnGoal}, {@code userTextForToolsLoop}, telemetry)
   * @param toolsLoopSessionBundle session bundle for the tools loop ({@code authorTurnGoalBlock}, etc.)
   * @param turnGoal plain-language goal for this turn
   * @param successCriteria optional verification phrase (may be empty)
   * @param anchorPath optional anchored {@code /site/.../*.xml} path for the block header
   */
  static void wireIntoRouteResult(
    Map result,
    Map toolsLoopSessionBundle,
    String turnGoal,
    String successCriteria,
    String anchorPath
  ) {
    String block = formatExecutionBlock(turnGoal, successCriteria, anchorPath)
    if (!block?.trim()) {
      return
    }
    if (toolsLoopSessionBundle instanceof Map) {
      toolsLoopSessionBundle.authorTurnGoal = turnGoal?.trim() ?: ''
      toolsLoopSessionBundle.authorTurnSuccessCriteria = successCriteria?.trim() ?: ''
      toolsLoopSessionBundle.authorTurnGoalBlock = block
    }
    if (result instanceof Map) {
      result.authorTurnGoal = turnGoal?.trim() ?: ''
      result.authorTurnSuccessCriteria = successCriteria?.trim() ?: ''
      String ut = (result.userTextForToolsLoop ?: '').toString()
      if (!ut.startsWith('[Studio — turn goal')) {
        result.userTextForToolsLoop = block + ut
      }
      if (result.intentRecipeRoutingTelemetry instanceof Map) {
        result.intentRecipeRoutingTelemetry.turnGoal = turnGoal?.trim() ?: ''
        result.intentRecipeRoutingTelemetry.successCriteria = successCriteria?.trim() ?: ''
      }
    }
  }

  /**
   * Appends the turn-goal execution reminder to the system wire message when present on the session bundle.
   *
   * @param wireMessages tools-loop chat messages (mutates first {@code role: system} entry)
   * @param toolsLoopSessionBundle bundle carrying {@code authorTurnGoalBlock} or {@code authorTurnGoal}
   */
  static void appendToSystemWireMessage(List<Map> wireMessages, Map toolsLoopSessionBundle) {
    if (!(wireMessages instanceof List) || !(toolsLoopSessionBundle instanceof Map)) {
      return
    }
    String goal = toolsLoopSessionBundle.authorTurnGoal?.toString()?.trim()
    if (!goal) {
      return
    }
    String appendix = '\n\n' + ToolPromptsTurnGoalExecution.systemGoalPolicyOnly()
    for (Map m : wireMessages) {
      if (m instanceof Map && 'system'.equals(m.get('role')?.toString())) {
        Object content = m.get('content')
        if (content instanceof CharSequence) {
          String existing = content.toString()
          if (!existing.contains('turn goal')) {
            m.put('content', existing + appendix)
          }
        }
        return
      }
    }
  }

  /**
   * Short reinjection after tool rounds so multi-step loops stay aligned with the turn goal.
   *
   * @param toolsLoopSessionBundle session bundle with {@code authorTurnGoal}
   * @return user-role reminder text, or empty when no goal is set
   */
  static String formatMidLoopReminder(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return ''
    }
    String goal = toolsLoopSessionBundle.authorTurnGoal?.toString()?.trim()
    if (!goal) {
      return ''
    }
    return '[aiassistant: turn goal reminder]\n**Still working toward:** ' + goal + '\n'
  }

  /**
   * Formats the turn-goal block prepended to tools-loop user text and echoed in system prompts.
   *
   * @param turnGoal required goal sentence (returns empty when blank)
   * @param successCriteria optional done-when phrase
   * @param anchorPath optional repository path shown as anchored item
   * @return markdown block starting with {@code [Studio — turn goal …]}, or empty
   */
  static String formatExecutionBlock(String turnGoal, String successCriteria, String anchorPath) {
    String g = (turnGoal ?: '').trim()
    if (!g) {
      return ''
    }
    StringBuilder sb = new StringBuilder()
    sb.append('[Studio — turn goal (carry through every step this turn)]\n')
    sb.append('**Goal:** ').append(g).append('\n')
    String c = (successCriteria ?: '').trim()
    if (c) {
      sb.append('**Done when:** ').append(c).append('\n')
    }
    String anchor = (anchorPath ?: '').trim()
    if (anchor) {
      sb.append('**Anchored item:** `').append(anchor).append('`\n')
    }
    sb.append(
      'Re-read this goal before each tool call. Prior chat turns are context only — do not drift to unrelated work.\n\n'
    )
    return sb.toString()
  }

  /**
   * Derives a turn goal when the router LLM did not supply {@code turnGoal}.
   *
   * @param authorVisible current-turn author text
   * @param anchorPath anchored repository path
   * @param recipeId recipe id when known
   * @param routingMode router mode
   * @param routerReason router {@code reason} field (used unless it starts with {@code parse error})
   * @return fallback goal text, or empty
   */
  private static String deriveFallbackGoal(
    String authorVisible,
    String anchorPath,
    String recipeId,
    String routingMode,
    String routerReason
  ) {
    String author = (authorVisible ?: '').trim()
    String reason = (routerReason ?: '').trim()
    if (reason && !reason.toLowerCase().startsWith('parse error')) {
      return reason
    }
    if (author) {
      if (AuthoringPreviewContext.authorGenerateImageRequiresPageContextFirst('', author) &&
        (anchorPath ?: '').trim()) {
        return 'Generate a fun illustration for the open page based on its content, using GenerateImage, ' +
          'then attach the image to the page if the content model has an image field.'
      }
      return 'Fulfill the author request: ' + author
    }
    String rid = (recipeId ?: '').trim()
    if (rid) {
      return 'Execute intent recipe: ' + rid
    }
    String mode = (routingMode ?: '').trim()
    if (mode) {
      return 'Complete the author request (routing mode: ' + mode + ').'
    }
    return ''
  }

  /**
   * Derives success criteria when the router LLM did not supply {@code successCriteria}.
   *
   * @param recipeId recipe id when known
   * @param routingMode router mode
   * @param anchorPath anchored repository path
   * @param authorVisible current-turn author text
   * @return fallback criteria text, or empty
   */
  private static String deriveFallbackSuccessCriteria(
    String recipeId,
    String routingMode,
    String anchorPath,
    String authorVisible
  ) {
    String rid = (recipeId ?: '').trim()
    if ('open_page_inquiry'.equals(rid)) {
      return 'Author receives an accurate read-only summary; no repository writes.'
    }
    if ('generate_image'.equals(rid)) {
      return 'GenerateImage succeeded and the author sees the generated bitmap.'
    }
    if ('new_content_item'.equals(rid)) {
      return 'WriteContent created the new item with valid XML; preview shows expected content.'
    }
    if ('modify_page_content'.equals(rid) && (anchorPath ?: '').trim()) {
      return 'WriteContent updated `' + anchorPath.trim() + '`; preview reflects the requested changes.'
    }
    if (AuthoringPreviewContext.authorGenerateImageRequiresPageContextFirst('', authorVisible ?: '') &&
      (anchorPath ?: '').trim()) {
      return 'GenerateImage ran with a prompt derived from page content; image attached to page when applicable.'
    }
    if ('plan'.equals((routingMode ?: '').trim())) {
      return 'The author request is fully addressed with appropriate tools; no unrelated edits.'
    }
    return ''
  }
}

/**
 * Turn-goal system-prompt appendix without importing {@link plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts}
 * (avoids circular dependency).
 */
final class ToolPromptsTurnGoalExecution {

  /** Utility class; not for instantiation. */
  private ToolPromptsTurnGoalExecution() {}

  /**
   * Appends execution policy text after the formatted turn-goal block for the tools-loop system message.
   *
   * @param goalBlock formatted block from {@link AuthoringTurnGoal#formatExecutionBlock}
   * @return goal block plus execution policy paragraph
   */
  static String systemAppendix(String goalBlock) {
    return goalBlock.trim() + '\n\n' + executionPolicyParagraph()
  }

  /**
   * System-role reminder that references the user-message turn goal without repeating author text.
   */
  static String systemGoalPolicyOnly() {
    return executionPolicyParagraph() +
      ' The **[Studio — turn goal …]** block in the user message states the objective for this turn only.'
  }

  private static String executionPolicyParagraph() {
    return '**Execution policy:** Every tool call and assistant reply on this turn must advance the turn goal. ' +
      'If a step does not serve the goal, skip it. Prior chat turns are context only — do not drift to unrelated work.'
  }
}
