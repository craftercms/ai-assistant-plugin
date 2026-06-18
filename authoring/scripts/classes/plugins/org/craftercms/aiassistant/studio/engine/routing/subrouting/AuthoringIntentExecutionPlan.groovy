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
    if (requiresExternalLookup(authorVisible) && mentionsRepoUpdateForPageCopy(authorRequest)) {
      return researchCopyWorkflowRows(anchorPath)
    }
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
      'Your **## Plan** must use **📋** lines that read like a human editor would work — one line per row below. '
    )
    sb.append(
      'Use **plain language outcomes** on **📋** lines (no wire tool names on those lines). '
    )
    sb.append('Run **`tool_calls`** in the same message as **## Plan**, in the order below.\n\n')
    int i = 1
    for (Map row : rows) {
      sb.append(i++).append('. **').append(row.authorStep).append('**\n')
      sb.append('   - Outcome: ').append(row.outcome).append('\n')
      if (row.toolChain) {
        sb.append('   - Tools: `').append(row.toolChain).append('`\n')
      }
      if (row.note) {
        sb.append('   - Note: ').append(row.note).append('\n')
      }
      sb.append('\n')
    }
    sb.append('**Editorial workflow (research → copy):**\n')
    sb.append(
      '- Steps marked **(reasoning)** are short prose in **## Plan** or the next assistant message — compare fetched facts with what you know; note what is current.\n'
    )
    sb.append(
      '- Before **WriteContent**, state the **page idea** (theme + key points), then map each point to the correct **field role** in **[Studio — content field plan]**.\n'
    )
    sb.append('**Tool-chain rules (any turn with external lookup):**\n')
    sb.append(
      '- **WebSearch** / **SerpApiWebSearch** return candidates (title, url, snippet) — **not** verified article body.\n'
    )
    sb.append(
      '- Before **WriteContent**, **update_content**, or **GenerateImage** uses live facts: **FetchHttpUrl** on one chosen URL, read the body, **synthesize** a page concept, then write **distinct** copy per field.\n'
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
      sb.append(i++).append('. 📋 ').append(row.authorStep).append('\n')
      if (row.note) {
        sb.append('   - ').append(row.note).append('\n')
      }
      sb.append('\n')
    }
    return sb.toString()
  }

  /**
   * Human-style editorial workflow for topical updates: search → read sources → synthesize → plan page → write fields.
   */
  private static List<Map> researchCopyWorkflowRows(String anchorPath) {
    String anchorRef = (anchorPath ?: '').trim() ? "`${anchorPath.trim()}`" : 'the anchored page'
    return [
      [
        authorStep: 'Search for current news and sources on the topic',
        outcome   : 'A short list of relevant articles (titles, URLs, snippets) to investigate',
        toolChain : 'WebSearch or SerpApiWebSearch',
        kind      : 'external_lookup',
        note      : 'Prefer recent, on-topic results — not generic marketing pages.'
      ],
      [
        authorStep: 'Visit promising URLs and gather ideas from the source material',
        outcome   : 'Retrieved plain-text excerpt from at least one source page',
        toolChain : 'FetchHttpUrl',
        kind      : 'external_lookup',
        note      : 'Read the page body; snippets alone are not enough for page copy.'
      ],
      [
        authorStep: 'Compare what you fetched with what you know — is it current?',
        outcome   : 'Brief synthesis: which facts to trust, what might be outdated, what surprised you',
        toolChain : '(reasoning — prose in ## Plan; no tool)',
        kind      : 'synthesize',
        note      : 'For "latest" topics, prefer fetched dates and quotes over training memory alone.'
      ],
      [
        authorStep: 'Formulate the overall idea for the page',
        outcome   : 'Page theme, angle, and key points — mapped to headline vs body vs supporting fields',
        toolChain : "GetContent (${anchorRef}) + content field plan",
        kind      : 'plan_copy',
        note      : 'Decide what each field should say **before** editing XML — headlines ≠ paragraphs ≠ alt text.'
      ],
      [
        authorStep: 'Write the appropriate copy into each page field',
        outcome   : 'Repository updated with specific, field-appropriate copy grounded in research',
        toolChain : "GetContent (${anchorRef}) → update_content or WriteContent → GetPreviewHtml",
        kind      : 'repo_update',
        note      : 'Use **[Studio — content field plan]** roles; verify in preview.'
      ],
      [
        authorStep: 'Generate a page image that matches your synthesized angle',
        outcome   : 'Image imported to `/static-assets/` and set on **image-asset** fields from the content field plan',
        toolChain : 'GenerateImage → WriteContent (repositoryPath on image-asset fields)',
        kind      : 'image_generate',
        note      : 'Prompt from your synthesis — never invent `/static-assets/…` paths or paste external image URLs.'
      ]
    ]
  }

  /**
   * Research-backed page copy refresh (search/fetch + repository write), e.g. topical homepage updates.
   */
  static boolean researchPageCopyUpdate(String authorVisible) {
    if (!(authorVisible ?: '').trim()) {
      return false
    }
    if (!requiresExternalLookup(authorVisible)) {
      return false
    }
    String authorRequest = AuthoringIntentCard.extractCleanAuthorRequest(authorVisible)
    return mentionsRepoUpdateForPageCopy(authorRequest)
  }

  private static boolean mentionsRepoUpdateForPageCopy(String authorRequest) {
    String lower = (authorRequest ?: '').toLowerCase()
    if (!lower.trim()) {
      return false
    }
    if (mentionsRepoUpdate(lower)) {
      return true
    }
    return (lower.contains('update') || lower.contains('rewrite') || lower.contains('refresh') ||
      lower.contains('about')) &&
      (lower.contains('homepage') || lower.contains('home page') || lower.contains('page') ||
        lower.contains('content') || lower.contains('copy') || lower.contains('hero'))
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
      lower.contains('latest news') || lower.contains('top news') ||
      lower.contains('recent developments') || lower.contains('latest developments') ||
      (lower.contains('latest') && (lower.contains('development') || lower.contains('about') ||
        lower.contains('update') || lower.contains('current'))) ||
      (lower.contains('recent') && (lower.contains('development') || lower.contains('about') ||
        lower.contains('update'))) ||
      (mentionsRepoUpdate(lower) &&
        (lower.contains('latest') || lower.contains('recent') || lower.contains('current') ||
          lower.contains('development') || lower.contains('up to date') || lower.contains('up-to-date')))
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
