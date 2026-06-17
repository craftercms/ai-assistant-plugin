package plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting

import plugins.org.craftercms.aiassistant.studio.engine.context.AuthoringPreviewContext

/**
 * Derives a **tool-ordered execution scaffold** from the author's intent steps (not from router meta text).
 * Injected into the tools loop so **## Plan** and {@code tool_calls} include the right wired tools in order.
 */
final class AuthoringIntentExecutionPlan {

  /** Utility class; not for instantiation. */
  private AuthoringIntentExecutionPlan() {}

  /**
   * @param authorVisible current-turn author text
   * @param anchorPath optional anchored {@code /site/.../*.xml}
   * @return ordered rows: {@code authorStep}, {@code outcome}, {@code toolChain} (string), {@code kind}
   */
  static List<Map> derivePlanRows(String authorVisible, String anchorPath) {
    String authorRequest = AuthoringIntentCard.extractCleanAuthorRequest(authorVisible)
    List<String> steps = AuthoringIntentCard.deriveSteps(authorRequest, '')
    if (steps.isEmpty() && authorRequest) {
      steps = [authorRequest]
    }
    List<Map> rows = []
    String anchor = (anchorPath ?: '').trim()
    boolean priorExternalLookup = false
    int n = 0
    for (String step : steps) {
      String lower = (step ?: '').toLowerCase()
      boolean needsLookup = mentionsExternalLookup(lower)
      boolean needsWrite = mentionsRepoUpdate(lower) || mentionsImageStep(lower)
      if (needsLookup && needsWrite && !priorExternalLookup) {
        Map lookupRow = planRowForKind('external_lookup', step, anchor, false, ++n, authorVisible)
        if (lookupRow) {
          rows.add(lookupRow)
          priorExternalLookup = true
        }
      }
      String kind = classifyStepKind(step)
      Map row = planRowForKind(kind, step, anchor, priorExternalLookup, ++n, authorVisible)
      if (row) {
        rows.add(row)
        if ('external_lookup'.equals(kind)) {
          priorExternalLookup = true
        }
      }
    }
    return rows
  }

  /**
   * User-role block prepended to the tools loop: maps author steps → required tool chains.
   */
  static String formatToolsLoopBlock(String authorVisible, String anchorPath) {
    List<Map> rows = derivePlanRows(authorVisible, anchorPath)
    if (rows.isEmpty()) {
      return ''
    }
    StringBuilder sb = new StringBuilder()
    sb.append('[Studio — execution plan from intent (required tool order)]\n')
    sb.append(
      'Your **## Plan** must have one **📋** line per row below. Each line names the **outcome** and the **tools** in order. '
    )
    sb.append('Later steps must **consume outputs** from earlier steps — not search snippets alone.\n\n')
    int i = 1
    for (Map row : rows) {
      sb.append(i++).append('. **').append(row.authorStep).append('**\n')
      sb.append('   - Outcome: ').append(row.outcome).append('\n')
      sb.append('   - Tools: `').append(row.toolChain).append('`\n')
      if (row.note) {
        sb.append('   - Note: ').append(row.note).append('\n')
      }
      sb.append('\n')
    }
    sb.append('**Tool-chain rules (any turn with external lookup):**\n')
    sb.append(
      '- **WebSearch** / **SerpApiWebSearch** return candidates (title, url, snippet) — **not** verified article body.\n'
    )
    sb.append(
      '- Before **WriteContent**, **update_content**, or **GenerateImage** uses live facts: **FetchHttpUrl** on one chosen **article** URL (avoid site homepages), read the body, then use those facts.\n'
    )
    sb.append(
      '- **GetContent** on the anchored page before editing XML; persist images with **WriteContent** / **update_content**, not chat-only previews.\n\n'
    )
    return sb.toString()
  }

  /**
   * Author-visible card for chat UI ({@code step-bridge-card} SSE) — compact view of planned tool order.
   */
  static String formatAuthorBridgeCard(String authorVisible, String anchorPath) {
    List<Map> rows = derivePlanRows(authorVisible, anchorPath)
    if (rows.isEmpty()) {
      return ''
    }
    StringBuilder sb = new StringBuilder()
    sb.append('## How this request will run\n\n')
    int i = 1
    for (Map row : rows) {
      sb.append(i++).append('. ').append(row.authorStep).append('\n')
      sb.append('   - **Tools:** `').append(row.toolChain).append('`\n')
      if (row.note) {
        sb.append('   - ').append(row.note).append('\n')
      }
      sb.append('\n')
    }
    return sb.toString()
  }

  /** True when any author step needs live external data before repository writes. */
  static boolean requiresExternalLookup(String authorVisible) {
    String authorRequest = AuthoringIntentCard.extractCleanAuthorRequest(authorVisible)
    if (!authorRequest) {
      return false
    }
    if (mentionsExternalLookup(authorRequest.toLowerCase())) {
      return true
    }
    for (String step : AuthoringIntentCard.deriveSteps(authorRequest, '')) {
      if (mentionsExternalLookup((step ?: '').toLowerCase())) {
        return true
      }
      if ('external_lookup'.equals(classifyStepKind(step))) {
        return true
      }
    }
    return false
  }

  private static String classifyStepKind(String stepText) {
    String lower = (stepText ?: '').toLowerCase()
    if (mentionsImageStep(lower)) {
      return 'image_generate'
    }
    if (mentionsRepoUpdate(lower)) {
      return 'repo_update'
    }
    if (mentionsExternalLookup(lower)) {
      return 'external_lookup'
    }
    if (mentionsRepoRead(lower)) {
      return 'repo_read'
    }
    return 'general'
  }

  private static Map planRowForKind(
    String kind,
    String authorStep,
    String anchor,
    boolean priorExternalLookup,
    int stepNum,
    String authorVisible = ''
  ) {
    String anchorRef = anchor ? "`${anchor}`" : 'the anchored page'
    Map row = [
      authorStep: (authorStep ?: '').trim(),
      kind      : kind
    ]
    switch (kind) {
      case 'external_lookup':
        row.outcome = 'Live information gathered and **read from a chosen source page** (not snippet-only)'
        row.toolChain = 'WebSearch → FetchHttpUrl'
        row.note = 'Pick one result URL that looks like an **article** (not a news homepage), then fetch and read it.'
        break
      case 'repo_update':
        row.outcome = 'Page copy updated in the repository from prior research'
        row.toolChain = priorExternalLookup ?
          "GetContent (${anchorRef}) → update_content or WriteContent" :
          "GetContent (${anchorRef}) → update_content or WriteContent"
        row.note = priorExternalLookup ?
          'Use facts from the **FetchHttpUrl** body in field values — not the search result title alone.' :
          'Edit the full content XML from GetContent.'
        break
      case 'image_generate':
        if (AuthoringPreviewContext.chatOnlyGenerateImageAuthorRequest(authorVisible ?: authorStep, anchor)) {
          row.outcome = 'Generated bitmap appears in the Studio chat image strip'
          row.toolChain = 'GenerateImage'
          row.note = 'Chat-only — do not call GetContent, WriteContent, or update_content for this turn.'
        } else {
          row.outcome = 'Generated image **persisted** on the page hero/image field'
          row.toolChain = "GetContent (${anchorRef}) → GenerateImage → WriteContent or update_content"
          row.note = 'Image prompt must reflect page copy + any prior research; save image path to the content item.'
        }
        break
      case 'repo_read':
        row.outcome = 'Repository item loaded for context'
        row.toolChain = "GetContent (${anchorRef})"
        break
      default:
        row.outcome = 'Step completed with appropriate wired tools'
        row.toolChain = 'See tool catalog'
        row.note = 'Decompose into concrete tool calls that match the author step.'
    }
    return row
  }

  private static boolean mentionsExternalLookup(String lower) {
    return lower.contains('web search') || lower.contains('search the web') ||
      lower.contains('lookup online') ||
      lower.contains('search for') || lower.contains('search ') ||
      lower.contains('headline') || lower.contains("today's") || lower.contains('current events') ||
      lower.contains('latest news') || lower.contains('top news')
  }

  private static boolean mentionsRepoUpdate(String lower) {
    return (lower.contains('update') || lower.contains('edit') || lower.contains('rewrite') ||
      lower.contains('modify') || lower.contains('change')) &&
      (lower.contains('content') || lower.contains('copy') || lower.contains('page') || lower.contains('field'))
  }

  private static boolean mentionsImageStep(String lower) {
    boolean imageNoun = lower.contains('image') || lower.contains('photo') ||
      lower.contains('picture') || lower.contains('illustration')
    boolean imageAction = lower.contains('generate') || lower.contains('create') ||
      lower.contains('draw') || lower.contains('make') || lower.contains('update') ||
      lower.contains('attach')
    return (imageNoun && imageAction) ||
      lower.contains('hero image') || lower.contains('hero photo') ||
      lower.contains('hero illustration')
  }

  private static boolean mentionsRepoRead(String lower) {
    return lower.contains('getcontent') || lower.contains('read the page') ||
      lower.contains('what is on this page')
  }
}
