package plugins.org.craftercms.aiassistant.authoring

import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.List
import java.util.Locale
import java.util.regex.Pattern

/**
 * Appends Studio preview context to the user prompt so the LLM can resolve
 * phrases like "this page", "my page", or "update my content" without a path.
 */
class AuthoringPreviewContext {

  /** Publish / go-live without naming another path — pair with {@link #appendToUserPrompt} path injection. */
  private static final Pattern PUBLISH_NOW_INTENT = Pattern.compile(
    '(?i)\\b(publish(\\s+now|\\s+this|\\s+the\\s+(page|article|post|item))?|push\\s+to\\s+live|go\\s+live|deploy(\\s+now)?|release(\\s+to\\s+live)?|make\\s+it\\s+live|send\\s+to\\s+live|put\\s+it\\s+live)\\b'
  )

  /** Entire site / first publish / publish everything — not a single open item. */
  private static final Pattern PUBLISH_SITE_BULK_INTENT = Pattern.compile(
    '(?i)\\b(publish\\s+(the\\s+)?(entire|whole)\\s+site|publish\\s+everything|publish\\s+all(\\s+content)?|publish\\s+the\\s+site|first\\s+publish|initial\\s+publish|publish\\s+for\\s+the\\s+first\\s+time|never\\s+been\\s+published|bulk\\s+publish|deploy\\s+(the\\s+)?(entire|whole)\\s+site|go\\s+live\\s+(on|for)\\s+the\\s+(whole|entire)\\s+site)\\b'
  )

  /** Cross-language or “translate this” style work — paired with path injection. */
  private static final Pattern CROSS_LANGUAGE_TRANSLATE_INTENT = Pattern.compile(
    '(?i)\\b(translat|localiz|localization|to\\s+(spanish|french|german|arabic|italian|portuguese|dutch|russian|japanese|chinese|korean|hindi|vietnamese|polish|turkish|hebrew|swedish|norwegian|danish|finnish|greek)|in\\s+spanish|in\\s+french|in\\s+german|msa\\b|modern\\s+standard\\s+arabic)\\b'
  )

  /** Author means whole rendered page / site — do not apply narrow single-item fast path. */
  private static final Pattern FULL_PAGE_OR_SITE_COPY_INTENT = Pattern.compile(
    '(?i)\\b(this\\s+page|the\\s+page|full\\s+page|whole\\s+page|everything\\s+(on|in)|all\\s+(visible\\s+)?copy|what\\s+i\\s+see|entire\\s+site|sitewide|every\\s+page|whole\\s+site)\\b'
  )

  /** Same-language copy/tone/structure edits on the open item (not full-page / not translate). */
  private static final Pattern SINGLE_ITEM_EDIT_INTENT = Pattern.compile(
    '(?i)\\b(rephrase|proofread|rewrite|fix\\s+typos?|grammar|reading\\s+level|simplif(y|ying)|expand\\s+(the\\s+)?(bullets|body)|shorten|tweak|improve|polish|refine|update\\s+(the\\s+)?(title|headings?|body|copy|excerpt|seo|meta)|edit\\s+(the\\s+)?(copy|text|content|article|post)|change\\s+(the\\s+)?(wording|text|tone)|multicolor|headings?\\s+(in|with|to|like))\\b'
  )

  /** Author refers to display layer (FTL / template), not field XML only — paired with path injection. */
  private static final Pattern TEMPLATE_LAYER_MENTION = Pattern.compile(
    '(?i)\\b(template|templates|ftl|freemarker|\\.ftl|display\\s+template)\\b'
  )

  /** With {@link #TEMPLATE_LAYER_MENTION}, signals formatting / listing / dates in preview (not schema-only). */
  private static final Pattern TEMPLATE_DISPLAY_SHAPE_INTENT = Pattern.compile(
    '(?i)\\b(date|dates?|format|listing|list\\s+view|cards?|grid|markup|render(ing)?|layout|how\\s+it\\s+(shows|looks)|visible\\s+in\\s+preview)\\b'
  )

  /**
   * Author wants new repository content — generic; no assumed folder or content-type id.
   * Matches “create/add/write/draft … page/post/article/item” (including “write a blog article …” — {@code write}
   * was missing before and skipped the fast-path hint).
   */
  private static final Pattern NEW_CONTENT_INTENT = Pattern.compile(
    '(?i)\\b(create|add|make|build|write|draft)\\s+(?:(a|an|the)\\s+)?(?:(new)\\s+)?((blog|news|press)\\s+)?(page|post|article|item|entry|content\\s+item|url\\s+route|landing\\s+page|section\\s+page)\\b'
  )

  /** Client sends {@code authoringSurface: "formEngine"} for the content-type form assistant (not XB / preview). */
  static boolean isFormEngineSurface(Object raw) {
    def s = (raw ?: '').toString().trim().toLowerCase()
    return s == 'formengine' || s == 'form_engine'
  }

  /** True for JSON boolean true, or string "true" / "1" / "yes" (case-insensitive). */
  static boolean isTruthy(Object raw) {
    if (raw == null) return false
    if (raw instanceof Boolean) return ((Boolean) raw).booleanValue()
    def s = raw.toString().trim().toLowerCase()
    return s == 'true' || s == '1' || s == 'yes'
  }

  /** Request body / ui.xml {@code authoringIntentExpansion} flag. */
  static boolean parseAuthoringIntentExpansion(Object raw) {
    return isTruthy(raw)
  }

  /**
   * Request body {@code enableTools}: absent/null/empty → {@code true} (OpenAI tools on, legacy default).
   * Explicit {@code false}, {@code "false"}, {@code "0"}, {@code "no"} → {@code false}.
   */
  static boolean parseEnableTools(Object raw) {
    if (raw == null) return true
    if (raw instanceof Boolean) return ((Boolean) raw).booleanValue()
    def s = raw.toString().trim().toLowerCase()
    if (!s) return true
    if (s == 'false' || s == '0' || s == 'no') return false
    if (s == 'true' || s == '1' || s == 'yes') return true
    return true
  }

  static String normalizeRepoPath(String path) {
    def p = (path ?: '').toString().trim()
    if (!p) return ''
    return p.startsWith('/') ? p : '/' + p
  }

  /** True when both normalize to the same non-empty repository path. */
  static boolean sameRepoPath(Object pathA, Object pathB) {
    def pa = normalizeRepoPath(pathA?.toString())
    def pb = normalizeRepoPath(pathB?.toString())
    return pa && pb && pa == pb
  }

  /**
   * Strips Studio-injected blocks from the orchestration user prompt so intent checks
   * (e.g. trivial greeting) only see what the author typed, not metadata that quotes
   * phrases like {@code "this page"} as examples.
   */
  static String stripStudioInjectedPromptBlocks(String fullPrompt) {
    def s = (fullPrompt ?: '').toString()
    if (!s.trim()) {
      return ''
    }
    def out = s
    try {
      // [Prior conversation …] … ---\n\n (from AiOrchestration.buildPriorTurnsContextBlock)
      out = out.replaceAll('(?s)\\[Prior conversation[^\\]]*\\][\\s\\S]*?\\n---\\s*\\n\\n', '')
      // [Request anchor …]\nRepository path: …\nContent-type id: …\n\n
      out = out.replaceAll(
        '(?is)\\[Request anchor[^\\]]*\\][^\\n]*\\nRepository path:\\s*[^\\n]+\\n(?:Content-type id:\\s*[^\\n]+\\n)?\\s*',
        ''
      )
      // --- Studio authoring context … --- (standalone closing line `---` before preview bundle or EOF)
      def ctxIdx = out.indexOf('--- Studio authoring context')
      if (ctxIdx >= 0) {
        def lineStart = out.lastIndexOf('\n', ctxIdx)
        def blockStart = lineStart >= 0 ? lineStart : 0
        def scan = out.indexOf('\n', ctxIdx)
        while (scan >= 0) {
          def nextNl = out.indexOf('\n', scan + 1)
          def line = nextNl < 0 ? out.substring(scan + 1) : out.substring(scan + 1, nextNl)
          if ('---'.equals(line.trim())) {
            def endExclusive = nextNl < 0 ? out.length() : nextNl + 1
            out = out.substring(0, blockStart) + out.substring(endExclusive)
            break
          }
          scan = nextNl
        }
      }
      // Trailing preview bundle (appendEnginePreviewHintIfPossible)
      out = out.replaceAll('(?ms)\\n\\n--- Studio preview URL[\\s\\S]*', '')
      out = out.replaceAll('(?ms)\\n\\n--- Engine preview URL[\\s\\S]*', '')
      out = out.replaceAll('(?ms)\\n\\n--- Studio agent clock[\\s\\S]*?\\n---\\s*', '')
    } catch (Throwable ignored) {
    }
    return out.trim()
  }

  /**
   * Per-request wall clock for the agent (UTC + JVM default zone). Not author text.
   */
  static String studioAgentDateTimeContextBlock() {
    Instant instant = Instant.now()
    ZonedDateTime utc = instant.atZone(ZoneId.of('UTC'))
    ZonedDateTime local = instant.atZone(ZoneId.systemDefault())
    DateTimeFormatter utcFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withLocale(Locale.ROOT)
    DateTimeFormatter localFmt = DateTimeFormatter.ofPattern('EEEE, MMMM d, yyyy · HH:mm:ss z', Locale.ENGLISH)
    return """--- Studio agent clock (metadata; not the author's request) ---
Current time (UTC): ${utc.format(utcFmt)}
Current time (server): ${local.format(localFmt)}
Use these when the author asks about "today", "now", freshness, or dated content — do not invent a calendar date.
---"""
  }

  /** Appends {@link #studioAgentDateTimeContextBlock()} once per prompt assembly. */
  static String appendAgentDateTimeContext(String prompt) {
    def base = (prompt ?: '').toString()
    if (base.contains('--- Studio agent clock')) {
      return base
    }
    return base + '\n\n' + studioAgentDateTimeContextBlock()
  }

  private static final Pattern CMS_TASK_SIGNAL = Pattern.compile(
    '(?i)(\\b(translat|localiz|publish|deploy|go\\s+live|revert|update|edit|change|rewrite|rephrase|delete|create|write|draft|put|add|place|insert|set|generate\\s+image|generate\\s+an?\\s+image|draw|fix|content|templates?|template|css|scss|less|stylesheet|styling|branding|mockup|theme|layout|ftl|freemarker|component|sections?_o|writecontent|listpages|getcontent|static-assets|update_template|analyze_template|headline|subtitle|lyrics?|summarize|summary)\\b|https?://|\\blook\\s+like\\b|\\bsimilar\\s+to\\b|\\bmatch(es)?\\b|\\bsite\\b|\\bwebsite\\b)'
  )

  /** Preview/form anchor + author names a repository field target (e.g. “add tips to my hero text”). */
  private static final Pattern ANCHORED_FIELD_PLACEMENT = Pattern.compile(
    '(?i)(\\b(put|place|add|insert|set)\\b.+\\b(in|into|to)\\b.+\\b(hero|title|headline|subtitle|body|copy|text|field|excerpt|nav|label)\\b|' +
      '\\b(hero|title|headline|subtitle)\\b.+\\b(with|to)\\b|' +
      '\\bupdate\\b.+\\b(hero|title|headline|subtitle|body|copy|text)\\b)'
  )

  /** Studio version rollback — not colloquial “restore a classic car”, “restore health”, etc. */
  private static final Pattern REPOSITORY_VERSION_REVERT = Pattern.compile(
    '(?i)\\b(undo|revert|roll\\s*back|go\\s+back|put\\s+back|switch\\s+back)\\b|' +
      '\\brestore\\s+(?:to\\s+)?(?:the\\s+)?(?:previous|prior|earlier|original|initial)\\s+version\\b|' +
      '\\brestore\\s+(?:this|the)\\s+(?:page|item|file|content|version)\\b'
  )

  /** Oldest / first-created Studio version — not the immediate prior save. */
  private static final Pattern REVERT_TO_INITIAL_VERSION_SIGNAL = Pattern.compile(
    '(?i)\\b(initial\\s+commit|first\\s+version|original\\s+version|oldest\\s+version|earliest\\s+version|' +
      'when\\s+(?:it\\s+was\\s+)?first\\s+created|as\\s+first\\s+created|back\\s+to\\s+the\\s+beginning|' +
      'very\\s+first|first\\s+check(?:-|)in)\\b'
  )

  private static final Pattern PRIOR_TURN_CONTENT_REFERENCE = Pattern.compile(
    '(?i)\\b(these|those|generated|previous|prior|earlier)\\s+(tips?|list|text|copy|content|results?)\\b|' +
      '\\buse\\s+(these|those|the)\\s+(tips?|list|text|copy)\\b|' +
      '\\bno[,\\s]+use\\s+these\\b'
  )

  /** Ask the model to compose fiction or chat prose — not repository field work. */
  private static final Pattern CREATIVE_LLM_ONLY = Pattern.compile(
    '(?i)\\b(?:write|craft|compose|tell|share|give\\s+me|generate|create)\\b.{0,72}\\b(?:story|tale|poem|verse|limerick|joke|fiction|fable|narrative|parable)\\b|' +
      '\\b(?:short\\s+)?story\\s+about\\b'
  )

  /** Edit the last assistant chat artifact (e.g. “this story”), not the anchored Studio page. */
  private static final Pattern CHAT_ARTIFACT_REFERENCE = Pattern.compile(
    '(?i)\\b(?:this|the|your|that)\\s+story\\b|' +
      '\\b(?:shorten|lengthen|expand|condense|trim|rewrite)\\b.{0,40}\\b(?:story|tale|poem|reply|answer|version)\\b|' +
      '\\b(?:two|three|\\d+)\\s+paragraphs?\\b|' +
      '\\bmake\\s+(?:this|it|the\\s+story)\\b'
  )

  private static final Pattern ASSISTANT_PAGE_SUMMARY_MARKERS = Pattern.compile(
    '(?i)\\bcontent\\s+summary\\b|\\bhero\\s+text\\b|\\bfeatures\\s+section\\b|\\brepository\\s+path\\b'
  )

  private static final Pattern ASSISTANT_CHAT_CREATIVE_MARKERS = Pattern.compile(
    "(?i)(?:here's|sure!).{0,48}(?:story|tale|poem)\\b|once\\s+upon\\s+a\\s+time"
  )

  private static final Pattern REPOSITORY_PATH_IN_PROMPT = Pattern.compile(
    '(?im)^Repository path:\\s*(\\S+)\\s*$'
  )

  private static final Pattern CURRENT_REQUEST_SECTION = Pattern.compile(
    '(?is)Current request:\\s*\\n(.*)\\z'
  )

  private static final Pattern PRIOR_CONVERSATION_BODY = Pattern.compile(
    '(?is)\\[Prior conversation[^\\]]*\\]\\s*\\n(.*?)\\n---\\s*\\n'
  )

  private static final int PRIOR_TURN_MEMORY_USER_MAX_CHARS = 2800
  private static final int PRIOR_TURN_MEMORY_ASSISTANT_MAX_CHARS = 1800

  /**
   * Author text for this turn only (after {@code Current request:}), not abbreviated prior conversation.
   */
  /**
   * Combined wire anchor + current-turn author text for intent {@code deterministicMatch} signals
   * (so research signals see {@code Repository path:} while patterns run on author wording only).
   */
  static String intentRoutingProbe(String anchorCarrier, String authorVisibleText) {
    String carrier = (anchorCarrier ?: '').toString().trim()
    String author = (authorVisibleText ?: '').toString().trim()
    if (carrier && author) {
      return carrier + '\n\n' + author
    }
    return carrier ?: author ?: ''
  }

  static String extractAuthorCurrentRequestVisible(String fullPrompt) {
    def s = (fullPrompt ?: '').toString()
    def cm = CURRENT_REQUEST_SECTION.matcher(s)
    if (cm.find()) {
      def tail = stripStudioInjectedPromptBlocks(cm.group(1) ?: '')
      return (tail ?: '').trim()
    }
    return stripStudioInjectedPromptBlocks(s)?.trim() ?: ''
  }

  /** Body between {@code [Prior conversation …]} header and the {@code ---} separator before {@code Current request:}. */
  static String extractPriorConversationBody(String fullPrompt) {
    def s = (fullPrompt ?: '').toString()
    def m = PRIOR_CONVERSATION_BODY.matcher(s)
    return m.find() ? (m.group(1) ?: '').toString().trim() : ''
  }

  private static String clipPriorTurnMemoryText(String text, int maxChars) {
    def t = (text ?: '').toString().trim()
    if (!t || maxChars <= 0) {
      return ''
    }
    if (t.length() <= maxChars) {
      return t
    }
    return t.substring(0, maxChars) + '…'
  }

  /**
   * Parses {@code User:} / {@code Assistant:} lines from the abbreviated prior-conversation block.
   * @return list of maps {@code [role: 'user'|'assistant', text: String]}
   */
  static List<Map> parsePriorConversationTurns(String priorBody) {
    def body = (priorBody ?: '').toString()
    if (!body.trim()) {
      return Collections.emptyList()
    }
    List<Map> turns = []
    String currentRole = null
    StringBuilder currentText = new StringBuilder()
    Closure flush = {
      if (!currentRole) {
        return
      }
      String t = currentText.toString().trim()
      if (t) {
        turns.add([role: currentRole, text: t])
      }
      currentRole = null
      currentText = new StringBuilder()
    }
    for (String line : body.split(/\r?\n/, -1)) {
      def userM = (line =~ /^(?i)User:\s*(.*)$/)
      if (userM.matches()) {
        flush.call()
        currentRole = 'user'
        currentText = new StringBuilder((userM.group(1) ?: '').toString())
        continue
      }
      def asstM = (line =~ /^(?i)Assistant:\s*(.*)$/)
      if (asstM.matches()) {
        flush.call()
        currentRole = 'assistant'
        currentText = new StringBuilder((asstM.group(1) ?: '').toString())
        continue
      }
      if (currentRole) {
        if (currentText.length() > 0) {
          currentText.append('\n')
        }
        currentText.append(line)
      }
    }
    flush.call()
    return turns
  }

  /**
   * Terse follow-up that only makes sense with {@linkplain #formatLastPriorTurnMemoryBlock prior turn memory}
   * (e.g. "make it shorter") — eligible for intent routing / refine even without CMS keywords.
   */
  static boolean authorCurrentRequestLooksLikePriorTurnFollowUp(String fullPrompt) {
    String current = extractAuthorCurrentRequestVisible(fullPrompt)?.trim()
    if (!current || current.length() > 320) {
      return false
    }
    if (!extractPriorConversationBody(fullPrompt)?.trim()) {
      return false
    }
    if (PRIOR_TURN_CONTENT_REFERENCE.matcher(current).find()) {
      return true
    }
    if ((current =~ /(?i)\b(make it|shorter|longer|brief|condense|trim it|expand it|rewrite it)\b/).find()) {
      return true
    }
    return current.tokenize().size() <= 8 &&
      (current =~ /(?i)\b(it|that|this|your|the story)\b/).find()
  }

  /**
   * Current turn asks for original prose in chat (story, poem, …) without naming a repository field or page edit.
   */
  static boolean authorCurrentRequestLooksLikeCreativeLlmOnly(String fullPrompt) {
    String current = extractAuthorCurrentRequestVisible(fullPrompt)?.trim()
    if (!current || current.length() > 1200) {
      return false
    }
    def stripped = stripStudioInjectedPromptBlocks(current)?.trim()
    if (!stripped) {
      return false
    }
    if (authorVisibleSuggestsOpenPageInquiryForAuthorText(fullPrompt, stripped) ||
      anchoredSiteXmlFieldPlacementIntentForAuthorText(fullPrompt, stripped) ||
      authorRefersToAnchoredOpenStudioItemForAuthorText(fullPrompt, stripped)) {
      return false
    }
    if ((stripped =~ /(?i)\bthis\s+page\b/).find()) {
      return false
    }
    return CREATIVE_LLM_ONLY.matcher(stripped).find()
  }

  /**
   * True when the author refers to chat output from the prior turn (e.g. “this story”), not CMS fields on the anchor.
   */
  static boolean authorCurrentRequestEditsPriorChatArtifact(String fullPrompt) {
    String current = extractAuthorCurrentRequestVisible(fullPrompt)?.trim()
    if (!current || !extractPriorConversationBody(fullPrompt)?.trim()) {
      return false
    }
    if (authorCurrentRequestSuggestsCmsTooling(fullPrompt)) {
      return false
    }
    if (anchoredSiteXmlFieldPlacementIntentForAuthorText(fullPrompt, current) ||
      authorRefersToAnchoredOpenStudioItemForAuthorText(fullPrompt, current)) {
      return false
    }
    if ((current =~ /(?i)\b(this|the)\s+page\b/).find()) {
      return false
    }
    if (CHAT_ARTIFACT_REFERENCE.matcher(current).find()) {
      return true
    }
    if (authorCurrentRequestLooksLikePriorTurnFollowUp(fullPrompt) &&
      (current =~ /(?i)\b(story|tale|poem|your\s+reply|that\s+answer)\b/).find()) {
      return true
    }
    return false
  }

  /**
   * Prior user+assistant turn produced chat-only creative text; current turn revises that artifact (not {@code index.xml}).
   */
  static boolean authorConversationPivotedToChatOnlyArtifact(String fullPrompt) {
    if (!authorCurrentRequestEditsPriorChatArtifact(fullPrompt)) {
      return false
    }
    return priorImmediateTurnWasChatCreativeExchange(fullPrompt)
  }

  private static boolean priorImmediateTurnWasChatCreativeExchange(String fullPrompt) {
    def priorBody = extractPriorConversationBody(fullPrompt)?.trim()
    if (!priorBody) {
      return false
    }
    List<Map> turns = parsePriorConversationTurns(priorBody)
    if (turns.isEmpty()) {
      return false
    }
    int lastUserIdx = -1
    for (int i = turns.size() - 1; i >= 0; i--) {
      if ('user'.equals(turns.get(i).role?.toString())) {
        lastUserIdx = i
        break
      }
    }
    if (lastUserIdx < 0) {
      return false
    }
    String userText = turns.get(lastUserIdx).text?.toString() ?: ''
    String asstText = ''
    if (lastUserIdx + 1 < turns.size() && 'assistant'.equals(turns.get(lastUserIdx + 1).role?.toString())) {
      asstText = turns.get(lastUserIdx + 1).text?.toString() ?: ''
    }
    if (authorTextLooksLikeCreativeGenerationRequest(userText)) {
      return true
    }
    return asstText?.trim() &&
      authorAssistantReplyLooksLikeChatCreativeArtifact(userText, asstText)
  }

  private static boolean authorTextLooksLikeCreativeGenerationRequest(String text) {
    def t = stripStudioInjectedPromptBlocks((text ?: '').toString())?.trim()
    if (!t) {
      return false
    }
    if (OPEN_PAGE_INQUIRY.matcher(t).find() || (t =~ /(?i)\bthis\s+page\b/).find()) {
      return false
    }
    return CREATIVE_LLM_ONLY.matcher(t).find()
  }

  private static boolean authorAssistantReplyLooksLikeChatCreativeArtifact(String priorUser, String assistant) {
    String a = (assistant ?: '').trim()
    if (!a || a.length() < 80) {
      return false
    }
    if (ASSISTANT_PAGE_SUMMARY_MARKERS.matcher(a).find()) {
      return false
    }
    if (authorTextLooksLikeCreativeGenerationRequest(priorUser)) {
      return true
    }
    return ASSISTANT_CHAT_CREATIVE_MARKERS.matcher(a).find()
  }

  /**
   * Markdown block for intent-refine / router LLM calls: the **last** user message and assistant reply before
   * {@code Current request:}, when the wire prompt includes prior conversation.
   */
  static String formatLastPriorTurnMemoryBlock(String fullPrompt) {
    def priorBody = extractPriorConversationBody(fullPrompt)
    if (!priorBody) {
      return ''
    }
    List<Map> turns = parsePriorConversationTurns(priorBody)
    if (turns.isEmpty()) {
      return ''
    }
    int lastUserIdx = -1
    for (int i = turns.size() - 1; i >= 0; i--) {
      if ('user'.equals(turns.get(i).role?.toString())) {
        lastUserIdx = i
        break
      }
    }
    if (lastUserIdx < 0) {
      return ''
    }
    String userText = clipPriorTurnMemoryText(turns.get(lastUserIdx).text?.toString(), PRIOR_TURN_MEMORY_USER_MAX_CHARS)
    if (!userText) {
      return ''
    }
    String asstText = ''
    if (lastUserIdx + 1 < turns.size() && 'assistant'.equals(turns.get(lastUserIdx + 1).role?.toString())) {
      asstText = clipPriorTurnMemoryText(turns.get(lastUserIdx + 1).text?.toString(), PRIOR_TURN_MEMORY_ASSISTANT_MAX_CHARS)
    }
    String asstLine = asstText ? asstText : '(none captured)'
    return """## Recent turn memory (immediately before this message)

**Previous user message:**
${userText}

**Previous assistant reply:**
${asstLine}"""
  }

  /**
   * Image-only turn: author asked for a new bitmap without a CMS write in the same message.
   * Evaluates {@linkplain #extractAuthorCurrentRequestVisible current request} when present.
   */
  static boolean authorCurrentRequestLooksLikeImageOnlyGenerate(String fullPrompt) {
    String u = extractAuthorCurrentRequestVisible(fullPrompt)
    if (!u) {
      u = stripStudioInjectedPromptBlocks((fullPrompt ?: '').toString())?.trim() ?: ''
    }
    if (!u || u.length() > 2400) {
      return false
    }
    String low = u.toLowerCase(Locale.ROOT)
    if (low.contains('writecontent') || low.contains('write content')) {
      return false
    }
    if (u.matches(/(?is).*\b(save|apply|insert|replace|upload|commit|publish)\b.{0,48}\b(to|into|on|in)\b.{0,48}\b(page|component|field|xml|content|repo)\b.*/)) {
      return false
    }
    boolean verb =
      u.matches(/(?is).*\b(generate|creating|create|draw|drawing|sketch|paint|making|make|render|illustrate)\b.*/) ||
        u.matches(/(?is).*\b(show me|give me|i want|i need|can you)\b.{0,48}\b(an?\s+)?(image|picture|illustration|drawing|photo|render)\b.*/)
    if (!verb) {
      return false
    }
    boolean noun =
      u.matches(/(?is).*\b(image|images|picture|pictures|illustration|illustrations|drawing|drawings|photo|photos|render|cover|banner|logo|artwork|graphic|icon|bitmap)\b.*/) ||
        u.matches(/(?is).*\b(image|picture|illustration|drawing|photo)\s+of\b.*/)
    if (!noun) {
      noun = u.matches(/(?is).*\b(draw|sketch|paint)\b.{0,100}\b(an?\s+|the\s+)?[a-z][a-z0-9\\-]{2,}\b.*/)
    }
    return noun
  }

  /** Alias for intent-router deterministic {@code generate_image} matching. */
  static boolean authorVisibleSuggestsIntentRecipeGenerateImage(String fullPrompt) {
    return authorCurrentRequestLooksLikeImageOnlyGenerate(fullPrompt)
  }

  private static final Pattern SHORT_CMS_CONTINUATION_AFFIRMATION = Pattern.compile(
    '(?is)^(yes|yeah|yep|ok|okay|let\'?s\\s+do\\s+it|do\\s+it|go\\s+ahead|please\\s+do|sure|confirm|sounds\\s+good|proceed)[\\s!.?]*$'
  )

  /**
   * {@code google.com}, {@code www.nytimes.com/…} — authors often omit {@code https://}. TLD allow-list avoids
   * matching {@code index.xml}, {@code form-definition.xml}, etc.
   */
  private static final Pattern BARE_REFERENCE_HOST_PATTERN = Pattern.compile(
    '(?i)\\b(?:www\\.)?(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])\\.)+(?:com|org|net|io|co\\.uk|co|gov|edu|dev|app|cms|ai|uk|de|fr|es|it|ca|au|nz|jp|cn|in|br|mx|blog|news|shop|store|tv|me|info|biz)\\b'
  )

  /**
   * Broad visual / reference language — used for **longer** prompts when a URL/host is present (see
   * {@link #isAuthoringIntentExpansionCandidate}); **short** prompts use length alone (still require CMS tooling signal).
   */
  private static final Pattern AUTHORING_INTENT_EXPANSION_VISUAL = Pattern.compile(
    '(?i)(\\blook\\s+like\\b|\\bsimilar\\s+to\\b|\\bmatch(?:es)?\\b|\\bresemble\\b|\\bfeel\\s+like\\b|\\bstyled?\\s+like\\b|\\bsame\\s+(look|style)\\b|\\bvisual\\s+(transform|overhaul|refresh|redesign)\\b|\\bredesign\\b|\\bbranding\\b|\\bmockup\\b|\\btheme\\b|\\b(css|stylesheet|scss|less)\\b.*\\b(template|templates?|ftl|layout|site|page)\\b|\\b(template|templates?|ftl|layout)\\b.*\\b(css|stylesheet|theme)\\b)'
  )

  /** Author-visible text (after stripping Studio blocks) this long or shorter is treated as likely underspecified. */
  private static final int AUTHORING_INTENT_EXPANSION_SHORT_VISIBLE_MAX_CHARS = 320

  /** Live / current-events lookup — route to {@code web_research} + {@code WebSearch}, not CMS tools. */
  private static final Pattern WEB_RESEARCH_SIGNAL = Pattern.compile(
    '(?i)(\\b(latest|recent|current|today\'?s|breaking)\\s+news\\b|\\bnews\\s+(on|about|for)\\b|\\bheadlines?\\b|' +
      '\\bcurrent\\s+events?\\b|\\bsearch\\s+the\\s+web\\b|\\bweb\\s+search\\b|\\bon\\s+the\\s+web\\b|' +
      '\\blook\\s+up\\s+(?:the\\s+)?latest\\b|\\blook\\s+up\\b.{0,96}\\b(?:on\\s+the\\s+web|online)\\b|' +
      '\\bwhat\\s+happened\\s+(today|this\\s+week)\\b|\\bwhat\'?s\\s+new\\s+with\\b|' +
      '\\bresearch\\b(?:\\s+\\d+)?\\s+(?:tips?|ideas|guides?|steps?|best\\s+practices|facts?)\\b|' +
      '\\bresearch\\s+\\d+\\s+\\w+)'
  )

  /** General knowledge / explanation — route to {@code llm_research} with CMS tools off. */
  private static final Pattern LLM_RESEARCH_SIGNAL = Pattern.compile(
    '(?i)(\\bexplain\\s+(?:what|how|why)|\\bwhat\\s+is\\b|\\bwhat\\s+are\\b|\\btell\\s+me\\s+about\\b|' +
      '\\bcompare\\s+|\\bdifference\\s+between\\b|\\bpros\\s+and\\s+cons\\b|\\bhow\\s+does\\s+.+\\s+work\\b)'
  )

  /** Find / summarize existing repository content — route to {@code site_content_research} + {@code ResearchSiteContent}. */
  private static final Pattern SITE_CONTENT_RESEARCH_SIGNAL = Pattern.compile(
    '(?i)(\\bsearch\\s+(?:the\\s+|our\\s+|this\\s+)?site\\b|\\bsite\\s+search\\b|\\bfind\\s+(?:pages?|content|items?)\\s+(?:about|on|for|in)\\b|' +
      '\\bwhat\\s+(?:pages?|content)\\s+(?:do\\s+we\\s+have|exists?)\\s+(?:about|on|for)\\b|\\bwhere\\s+(?:in\\s+the\\s+site|on\\s+the\\s+site)\\b|' +
      '\\bpages?\\s+(?:about|mentioning|on)\\b|\\bcontent\\s+about\\b|\\bin\\s+(?:our|the)\\s+(?:site|cms|repository)\\b)'
  )

  private static final Pattern PAGE_SUMMARIZE_SIGNAL = Pattern.compile(
    '(?i)\\b(summarize|summary|sum\\s+up)\\b'
  )

  /**
   * Author names the **open** Studio item in their own words ({@code this page}, {@code the component}, …)
   * while the wire prompt carries a {@code Repository path: /site/.../*.xml} anchor.
   */
  private static final Pattern ANCHORED_OPEN_STUDIO_ITEM_REFERENCE = Pattern.compile(
    '(?is)\\b(?:this|the)\\s+(?:page|component|item)\\b'
  )

  /**
   * Author asks what the **open** preview item is about (read/interpret), not open-web news or translate.
   * Requires anchored {@code /site/.../*.xml} (see {@link #authorVisibleSuggestsOpenPageInquiry}).
   */
  private static final Pattern OPEN_PAGE_INQUIRY = Pattern.compile(
    '(?is)\\b(?:what\\s+(?:is|are|(?:do|would)\\s+you\\s+(?:think|say))\\b.{0,96}\\b(?:this|the)\\s+page\\b|' +
      'how\\s+would\\s+you\\s+(?:describe|characterize|summarize)\\s+(?:this|the)\\s+page\\b|' +
      'tell\\s+me\\s+about\\s+(?:this|the)\\s+page\\b|' +
      'describe\\s+(?:this|the)\\s+page\\b|' +
      '(?:this|the)\\s+page\\b.{0,96}\\b(?:about|for|mean|purpose|topic|describe|explain|overview)\\b|' +
      'page\\s+is\\s+about\\b)'
  )

  /**
   * Studio already named {@code /site/.../*.xml} and the author refers to that open page or component
   * ({@code this page}, {@code the component}, …) — CMS authoring context, not general chit-chat.
   */
  static boolean authorRefersToAnchoredOpenStudioItem(String fullOrUserPrompt) {
    return authorRefersToAnchoredOpenStudioItemForAuthorText(fullOrUserPrompt, fullOrUserPrompt)
  }

  static boolean authorRefersToAnchoredOpenStudioItemForAuthorText(String anchorCarrier, String authorVisibleText) {
    def anchor = extractAnchoredRepositoryPath((anchorCarrier ?: '').toString())
    if (!anchor?.trim()) {
      return false
    }
    def low = anchor.toLowerCase(Locale.ROOT)
    if (!low.startsWith('/site/') || !low.endsWith('.xml')) {
      return false
    }
    def v = stripStudioInjectedPromptBlocks((authorVisibleText ?: '').toString())?.trim()
    return v && ANCHORED_OPEN_STUDIO_ITEM_REFERENCE.matcher(v).find()
  }

  /**
   * After stripping Studio-injected blocks, true when the author-visible text suggests CMS / repo / fetch work
   * (used server-side to avoid false “trivial greeting” tool suppression and to recover missing {@code tool_calls}).
   */
  static boolean authorVisibleSuggestsCmsTooling(String fullOrUserPrompt) {
    if (authorRefersToAnchoredOpenStudioItem(fullOrUserPrompt)) {
      return true
    }
    def v = stripStudioInjectedPromptBlocks((fullOrUserPrompt ?: '').toString())
    return v && CMS_TASK_SIGNAL.matcher(v).find()
  }

  /**
   * CMS intent for {@linkplain #extractAuthorCurrentRequestVisible this turn only}, not keywords replayed from
   * {@code [Prior conversation …]}. A repo anchor may stay on the wire after the author pivots to chat-only work
   * (e.g. generate a story, then “make it shorter”).
   */
  static boolean authorCurrentRequestSuggestsCmsTooling(String fullPrompt) {
    String current = extractAuthorCurrentRequestVisible(fullPrompt)?.trim()
    if (!current) {
      return authorVisibleSuggestsCmsTooling(fullPrompt)
    }
    def stripped = stripStudioInjectedPromptBlocks(current)?.trim()
    if (!stripped) {
      return false
    }
    if (CMS_TASK_SIGNAL.matcher(stripped).find()) {
      return true
    }
    if (authorVisibleSuggestsOpenPageInquiryForAuthorText(fullPrompt, stripped)) {
      return true
    }
    if (anchoredSiteXmlFieldPlacementIntentForAuthorText(fullPrompt, stripped)) {
      return true
    }
    return authorRefersToAnchoredOpenStudioItemForAuthorText(fullPrompt, stripped)
  }

  /**
   * “What is this page about?” / “what do you think this page is about” with Studio anchor — needs
   * {@code GetContent} on the open item, not LLM-only or web search.
   */
  static boolean authorVisibleSuggestsOpenPageInquiry(String fullOrUserPrompt) {
    return authorVisibleSuggestsOpenPageInquiryForAuthorText(fullOrUserPrompt, fullOrUserPrompt)
  }

  /**
   * Open-page inquiry: anchored {@code /site/.../*.xml} from {@code anchorCarrier} (wire prompt / anchor block),
   * author wording from {@code authorVisibleText} (current turn only when routing).
   */
  static boolean authorVisibleSuggestsOpenPageInquiryForAuthorText(String anchorCarrier, String authorVisibleText) {
    def anchor = extractAnchoredRepositoryPath((anchorCarrier ?: '').toString())
    if (!anchor?.trim()) {
      return false
    }
    def low = anchor.toLowerCase(Locale.ROOT)
    if (!low.startsWith('/site/') || !low.endsWith('.xml')) {
      return false
    }
    def v = stripStudioInjectedPromptBlocks((authorVisibleText ?: '').toString())?.trim()
    if (!v) {
      return false
    }
    if (PUBLISH_NOW_INTENT.matcher(v).find() || authorVisibleSuggestsPublishSiteBulk(v)) {
      return false
    }
    if (OPEN_PAGE_INQUIRY.matcher(v).find()) {
      return true
    }
    return (v =~ /(?is)\b(this|the)\s+page\b/).find() &&
      (v =~ /(?i)\b(about|think|purpose|mean|describe|explain|overview|topic)\b/).find()
  }

  /** Author asks for open-page / in-Studio summary (not open-web news). */
  static boolean authorVisibleSuggestsPageSummarize(String fullOrUserPrompt) {
    def v = stripStudioInjectedPromptBlocks((fullOrUserPrompt ?: '').toString())?.trim()
    if (!v) {
      return false
    }
    return PAGE_SUMMARIZE_SIGNAL.matcher(v).find() &&
      (FULL_PAGE_OR_SITE_COPY_INTENT.matcher(v).find() || v.matches('(?is).*(this|the)\\s+page.*'))
  }

  /** Freshness / headlines — use {@code WebSearch}, not {@code GenerateImage} or repo writes. */
  static boolean authorVisibleSuggestsWebResearch(String fullOrUserPrompt) {
    def v = stripStudioInjectedPromptBlocks((fullOrUserPrompt ?: '').toString())?.trim()
    if (!v) {
      return false
    }
    if (authorVisibleSuggestsPageSummarize(fullOrUserPrompt)) {
      return false
    }
    return WEB_RESEARCH_SIGNAL.matcher(v).find()
  }

  /** Timeless Q&A — answer from model knowledge with CMS tools disabled for the turn. */
  static boolean authorVisibleSuggestsLlmResearch(String fullOrUserPrompt) {
    def v = stripStudioInjectedPromptBlocks((fullOrUserPrompt ?: '').toString())?.trim()
    if (!v) {
      return false
    }
    if (authorVisibleSuggestsWebResearch(fullOrUserPrompt) ||
      authorVisibleSuggestsPageSummarize(fullOrUserPrompt)) {
      return false
    }
    String authorSlice = extractAuthorCurrentRequestVisible(fullOrUserPrompt)?.trim() ?: v
    if (authorVisibleSuggestsOpenPageInquiryForAuthorText(fullOrUserPrompt, authorSlice) ||
      authorVisibleSuggestsOpenPageInquiry(fullOrUserPrompt)) {
      return false
    }
    return LLM_RESEARCH_SIGNAL.matcher(v).find()
  }

  /** Entire site / publish everything / first go-live — use {@code publish_content} with {@code publishScope=all} or {@code bulk}. */
  static boolean authorVisibleSuggestsPublishSiteBulk(String fullOrUserPrompt) {
    def v = stripStudioInjectedPromptBlocks((fullOrUserPrompt ?: '').toString())?.trim()
    if (!v) {
      return false
    }
    if (PUBLISH_SITE_BULK_INTENT.matcher(v).find()) {
      return true
    }
    return PUBLISH_NOW_INTENT.matcher(v).find() &&
      (FULL_PAGE_OR_SITE_COPY_INTENT.matcher(v).find() &&
        (v =~ /(?i)\b(entire|whole|everything|all|site|first)\b/).find())
  }

  /**
   * Optional Studio metadata: whether the site has ever been published (from v2 {@code PublishService}).
   */
  static String appendSitePublishingStatus(String prompt, Boolean siteEverPublished) {
    def base = (prompt ?: '').toString()
    if (siteEverPublished == null) {
      return base
    }
    if (siteEverPublished == Boolean.TRUE) {
      return base
    }
    return """${base}

--- Studio publishing status (metadata; not the author's request) ---
This site has **never** been published to the delivery tier. For first go-live or "publish everything", call **publish_content** with **publishScope** `all` (PublishService.publishAll) — **not** only the open **index.xml** path unless the author explicitly narrowed to one item after first publish.
---"""
  }

  /** Search indexed site copy (OpenSearch) — {@code ResearchSiteContent}, not open web or generic LLM-only. */
  static boolean authorVisibleSuggestsSiteContentResearch(String fullOrUserPrompt) {
    def v = stripStudioInjectedPromptBlocks((fullOrUserPrompt ?: '').toString())?.trim()
    if (!v) {
      return false
    }
    if (authorVisibleSuggestsWebResearch(fullOrUserPrompt) ||
      authorVisibleSuggestsPageSummarize(fullOrUserPrompt) ||
      authorVisibleSuggestsOpenPageInquiry(fullOrUserPrompt)) {
      return false
    }
    if (SITE_CONTENT_RESEARCH_SIGNAL.matcher(v).find()) {
      return true
    }
    return (v =~ /(?i)\bsearch\b/).find() &&
      (v =~ /(?i)\b(site|cms|repository|pages?|content)\b/).find() &&
      !(v =~ /(?i)\b(web|internet|google|news|headlines)\b/).find()
  }

  /** Non-CMS research prompts still run intent recipe routing (web / site / llm recipes). */
  static boolean authorVisibleSuggestsIntentRecipeResearch(String fullOrUserPrompt) {
    return authorVisibleSuggestsWebResearch(fullOrUserPrompt) ||
      authorVisibleSuggestsSiteContentResearch(fullOrUserPrompt) ||
      authorVisibleSuggestsLlmResearch(fullOrUserPrompt) ||
      authorCurrentRequestLooksLikeCreativeLlmOnly(fullOrUserPrompt) ||
      authorConversationPivotedToChatOnlyArtifact(fullOrUserPrompt)
  }

  /** {@code Repository path: /site/...} from a Studio request anchor block, when present. */
  static String extractAnchoredRepositoryPath(String fullPrompt) {
    def s = (fullPrompt ?: '').toString()
    if (!s.trim()) {
      return ''
    }
    def m = REPOSITORY_PATH_IN_PROMPT.matcher(s)
    return m.find() ? normalizeRepoPath(m.group(1)) : ''
  }

  /**
   * Author wants to place or change copy on the open {@code /site/.../*.xml} item (hero title, body field, etc.)
   * even when they did not say “update” / “edit”.
   */
  static boolean anchoredSiteXmlFieldPlacementIntent(String fullPrompt) {
    return anchoredSiteXmlFieldPlacementIntentForAuthorText(fullPrompt, fullPrompt)
  }

  static boolean anchoredSiteXmlFieldPlacementIntentForAuthorText(String anchorCarrier, String authorVisibleText) {
    def anchor = extractAnchoredRepositoryPath((anchorCarrier ?: '').toString())
    if (!anchor || !anchor.toLowerCase(Locale.ROOT).startsWith('/site/') ||
      !anchor.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      return false
    }
    def v = stripStudioInjectedPromptBlocks((authorVisibleText ?: '').toString())?.trim()
    return v && ANCHORED_FIELD_PLACEMENT.matcher(v).find()
  }

  /** Length / URL gates for intent expansion when prior turns are on the wire — use the current author line only. */
  private static String intentExpansionVisibleSlice(String fullPrompt) {
    def prior = extractPriorConversationBody(fullPrompt)?.trim()
    if (prior) {
      String current = extractAuthorCurrentRequestVisible(fullPrompt)?.trim()
      if (current) {
        return stripStudioInjectedPromptBlocks(current)?.trim() ?: current
      }
    }
    return stripStudioInjectedPromptBlocks((fullPrompt ?: '').toString())?.trim() ?: ''
  }

  /** Undo / revert / restore a prior repository version (not a generative rewrite). */
  static boolean authorVisibleSuggestsRevertIntent(String visible) {
    def v = (visible ?: '').toString().trim()
    return v && REPOSITORY_VERSION_REVERT.matcher(v).find()
  }

  /** Author wants the oldest revertible history entry (e.g. “initial commit”), not one step back. */
  static boolean authorVisibleSuggestsRevertToInitialVersion(String visible) {
    def v = (visible ?: '').toString().trim()
    return v && REVERT_TO_INITIAL_VERSION_SIGNAL.matcher(v).find()
  }

  /** Author refers to assistant-generated copy from an earlier turn (“these tips”, “use these tips”). */
  static boolean authorVisibleSuggestsPriorTurnContent(String visible) {
    def v = (visible ?: '').toString().trim()
    return v && PRIOR_TURN_CONTENT_REFERENCE.matcher(v).find()
  }

  /**
   * Short “yes / let’s do it” after a prior turn that already scoped CMS work on the anchored item.
   */
  static boolean isShortAffirmationContinuingPriorCmsWork(String fullPrompt) {
    def s = (fullPrompt ?: '').toString()
    if (!s.contains('[Prior conversation')) {
      return false
    }
    def currentVisible = ''
    def cm = CURRENT_REQUEST_SECTION.matcher(s)
    if (cm.find()) {
      currentVisible = stripStudioInjectedPromptBlocks(cm.group(1) ?: '')?.trim() ?: ''
    }
    if (!currentVisible || !SHORT_CMS_CONTINUATION_AFFIRMATION.matcher(currentVisible).matches()) {
      return false
    }
    if (!extractAnchoredRepositoryPath(s)) {
      return false
    }
    def prior = s
    def curIdx = s.indexOf('Current request:')
    if (curIdx > 0) {
      prior = s.substring(0, curIdx)
    }
    return authorVisibleSuggestsCmsTooling(prior) || anchoredSiteXmlFieldPlacementIntent(prior)
  }

  /**
   * True when the author-visible text names an {@code http(s)} URL or a **likely external host** (e.g. {@code google.com}
   * without a scheme).
   */
  static boolean authorVisibleContainsHttpOrLikelyExternalHost(String visible) {
    def v = (visible ?: '').toString()
    if (!v) {
      return false
    }
    def low = v.toLowerCase(Locale.ROOT)
    if (low.contains('http://') || low.contains('https://')) {
      return true
    }
    return BARE_REFERENCE_HOST_PATTERN.matcher(v).find()
  }

  /**
   * When non-null, intent recipe routing / expansion is skipped for this turn (stable codes for Studio logs).
   * See {@link #isAuthoringIntentExpansionCandidate}.
   */
  static String intentRecipeRouterEligibilitySkipReason(String fullPrompt) {
    String currentReq = extractAuthorCurrentRequestVisible(fullPrompt)
    if (currentReq && authorVisibleSuggestsPageSummarize(currentReq)) {
      return 'author_summarize_no_intent_recipe'
    }
    if (authorCurrentRequestLooksLikeImageOnlyGenerate(fullPrompt)) {
      return null
    }
    if (isTrivialNonAuthoringTurn(fullPrompt)) {
      if (isShortAffirmationContinuingPriorCmsWork(fullPrompt)) {
        return null
      }
      return 'trivial_non_authoring_turn'
    }
    def v = stripStudioInjectedPromptBlocks((fullPrompt ?: '').toString())
    if (!v) {
      return 'empty_visible_after_strip'
    }
    if (v.length() > 1600 && !authorCurrentRequestLooksLikeImageOnlyGenerate(fullPrompt)) {
      String currentOnly = extractAuthorCurrentRequestVisible(fullPrompt)?.trim()
      if (!(currentOnly && currentOnly.length() <= 1600)) {
        return 'visible_exceeds_1600_chars'
      }
    }
    if (!authorCurrentRequestSuggestsCmsTooling(fullPrompt)) {
      String currentOnly = extractAuthorCurrentRequestVisible(fullPrompt)?.trim()
      if (currentOnly) {
        if (anchoredSiteXmlFieldPlacementIntentForAuthorText(fullPrompt, currentOnly)) {
          return null
        }
        if (authorRefersToAnchoredOpenStudioItemForAuthorText(fullPrompt, currentOnly)) {
          return null
        }
      } else {
        if (anchoredSiteXmlFieldPlacementIntent(fullPrompt)) {
          return null
        }
        if (authorRefersToAnchoredOpenStudioItem(fullPrompt)) {
          return null
        }
      }
      if (authorVisibleSuggestsIntentRecipeResearch(fullPrompt)) {
        return null
      }
      if (authorCurrentRequestLooksLikeImageOnlyGenerate(fullPrompt)) {
        return null
      }
      if (currentOnly && authorVisibleSuggestsIntentRecipeResearch(currentOnly)) {
        return null
      }
      if (authorVisibleSuggestsRevertIntent(v) &&
        extractAnchoredRepositoryPath(fullPrompt)?.trim()) {
        return null
      }
      if (authorCurrentRequestLooksLikePriorTurnFollowUp(fullPrompt)) {
        return null
      }
      if (authorCurrentRequestLooksLikeCreativeLlmOnly(fullPrompt)) {
        return null
      }
      if (authorConversationPivotedToChatOnlyArtifact(fullPrompt)) {
        return null
      }
      if (extractAnchoredRepositoryPath(fullPrompt)?.trim() || extractPriorConversationBody(fullPrompt)?.trim()) {
        return null
      }
      return 'no_cms_task_signal'
    }
    if (authorConversationPivotedToChatOnlyArtifact(fullPrompt)) {
      return null
    }
    if (authorCurrentRequestLooksLikePriorTurnFollowUp(fullPrompt)) {
      return null
    }
    String expansionVisible = intentExpansionVisibleSlice(fullPrompt) ?: v
    if (expansionVisible.length() <= AUTHORING_INTENT_EXPANSION_SHORT_VISIBLE_MAX_CHARS) {
      return null
    }
    if (authorCurrentRequestLooksLikeImageOnlyGenerate(fullPrompt)) {
      return null
    }
    String currentOnlyForGate = extractAuthorCurrentRequestVisible(fullPrompt)?.trim()
    if (currentOnlyForGate && authorVisibleSuggestsIntentRecipeResearch(currentOnlyForGate)) {
      return null
    }
    if (!authorVisibleContainsHttpOrLikelyExternalHost(expansionVisible)) {
      return 'long_message_no_url_for_expansion_gate'
    }
    if (!AUTHORING_INTENT_EXPANSION_VISUAL.matcher(expansionVisible).find()) {
      return 'long_message_url_without_visual_reference_phrase'
    }
    return null
  }

  /**
   * Eligible for the server’s **pre-tools** intent-expansion completion: either **short** author-visible text
   * (usually a one-liner too terse for reliable tool planning) or a **longer** message that combines a URL/host with
   * reference / visual language.
   */
  static boolean isAuthoringIntentExpansionCandidate(String fullPrompt) {
    return intentRecipeRouterEligibilitySkipReason(fullPrompt) == null
  }

  /**
   * Same as {@link #isAuthoringIntentExpansionCandidate(String)} unless project config disables the eligibility gate
   * ({@code intentRecipeRouting.eligibilityGateEnabled} false / omitted — default): then any non-empty prompt is eligible.
   */
  static boolean isAuthoringIntentExpansionCandidate(String fullPrompt, Map projectCfg) {
    if (projectCfg != null && !StudioAiAssistantProjectConfig.intentRecipeEligibilityGateEnabled(projectCfg)) {
      return (fullPrompt ?: '').toString().trim().length() > 0
    }
    return isAuthoringIntentExpansionCandidate(fullPrompt)
  }

  /**
   * True when the author-visible part of the prompt is a short greeting / chit-chat with
   * no CMS authoring signal — used to force tools off for preview chat (avoids destructive
   * tool runs when only Studio metadata was appended).
   */
  static boolean isTrivialNonAuthoringTurn(String fullPrompt) {
    def visible = stripStudioInjectedPromptBlocks(fullPrompt)
    if (!visible) {
      return true
    }
    // Any CMS / site / template signal — never force tools off (check before length heuristics).
    if (authorVisibleSuggestsCmsTooling(fullPrompt) ||
      anchoredSiteXmlFieldPlacementIntent(fullPrompt)) {
      return false
    }
    if (isShortAffirmationContinuingPriorCmsWork(fullPrompt)) {
      return false
    }
    if (visible.length() > 160) {
      return false
    }
    def t = visible.trim().toLowerCase(Locale.ROOT)
    if (t.matches('(?is)^(hello|hi|hey(\\s+there)?|good\\s+(morning|afternoon|evening)|thanks?|thank\\s+you|thx|ok(ay)?|yes|no|howdy|sup|yo|\\?)+[\\s!.?]*$')) {
      return true
    }
    def words = t.split(/\s+/).findAll { it }
    if (words.isEmpty() || words.size() > 6) {
      return false
    }
    def first = words[0].replaceAll('^\\p{Punct}+|\\p{Punct}+$', '')
    def openers = [
      'hello', 'hi', 'hey', 'thanks', 'thank', 'thx', 'yo', 'sup', 'howdy',
      'greetings', 'morning', 'evening', 'afternoon', 'ok', 'okay', 'yes', 'no', 'cheers'
    ] as Set
    if (openers.contains(first)) {
      return true
    }
    return words.size() >= 2 && first == 'good' && ['morning', 'afternoon', 'evening'].contains(words[1])
  }

  /**
   * If {@code contentPath} is non-empty, appends a fixed block the model must honor.
   * {@code contentTypeId} is optional (e.g. from Studio preview); may be blank.
   * {@code contentTypeLabelRaw} optional Studio UI label for the open item’s type when the client supplies it.
   */
  static String appendToUserPrompt(String prompt, Object contentPathRaw, Object contentTypeIdRaw, Object contentTypeLabelRaw = null) {
    def path = normalizeRepoPath(contentPathRaw?.toString())
    if (!path) {
      return (prompt ?: '').toString()
    }

    def ctRaw = (contentTypeIdRaw ?: '').toString().trim()
    def ctLine = ''
    if (ctRaw) {
      def ct = ctRaw.startsWith('/') ? ctRaw : '/' + ctRaw
      ctLine = "\nCurrent item content-type id (from Studio preview): ${ct}"
    }

    def labelRaw = (contentTypeLabelRaw ?: '').toString().trim()
    def labelLine = ''
    if (labelRaw) {
      labelLine = "\nCurrent item content-type **label** (Studio UI; from client when available): ${labelRaw}"
    }

    def base = (prompt ?: '').toString()
    def fastPublish = fastPathPublishHint(base, path)
    def fastTranslate = fastPathTranslateHint(base, path)
    def fastTemplate = fastPathTemplateDisplayHint(base, path)
    def fastEdit = fastPathSingleItemEditHint(base, path)
    def fastNewContent = fastPathNewContentItemHint(base, path)
    def fastOpenPageInquiry = fastPathOpenPageInquiryHint(base, path)
    return """${base}

--- Studio authoring context (when the user does not name a repository path) ---
**This block is Studio metadata, not the author’s request.** Quoted example phrases below explain how to **resolve paths** when the author’s **own words** use relative references — **do not** treat those examples as the author having said “this page”, “update my content”, etc., unless the same words appear **above** this block in the author’s message. **Greeting-only turns** (“hello”, “thanks”, tiny chitchat with **no** ask to change the site): **do not** open with **ListContentTranslationScope**, **TranslateContentItem**, **TranslateContentBatch**, or broad **GetContent** “discovery” just because this path exists — answer in prose. **That narrow rule applies only to pure greetings.** When the author **does** ask to change the site — including **CSS**, **SCSS/LESS**, **templates / FTL**, **static-assets**, **themes**, **layout**, **“make it look like”** a **URL**, or **reference-site** styling — that **is** an authoring task: use the normal **GetContent** / **update_template** / **WriteContent** / **FetchHttpUrl** (read-only reference) / **analyze_template** flow per system policy; **do not** refuse because the work is “not content XML.”
Current content item repository path: ${path}${ctLine}${labelLine}
**Content-type id vs. Studio label:** Authors often name a type by its **Studio list label** while items use a repository **content-type id** (`/page/...`, `/component/...`). **Resolve** with **siteId** + **ListStudioContentTypes**, then system **Exact catalog match beats guessing** (**string equality** after the same normalization on **`label`**, **`name`**, and **`name`** tail — **no** fuzzy “closest” pick). If **exactly one** row matches, use its **`name`** as **`contentTypeId`**; if **zero** or **many**, ask the author. **Do not** invent a **content-type id** from keywords alone.
When the author says "this page", "my page", "the current page", "this item", "update my content", or similar without specifying which file, treat that as **this** repository path. Use it as **contentPath** for update_content, GetContent, ListContentTranslationScope, WriteContent, GetContentTypeFormDefinition (when reading **that** item’s form), publish_content, revert_change, and for update_template / analyze_template when resolving the item's display-template. For **ListStudioContentTypes**, **do not** default to passing this path: call with **siteId** only first (full type catalog); pass **contentPath** only when you deliberately need folder-scoped allowed types. **Do not** call ListPagesAndComponents to guess a target when this block is present unless the user clearly refers to a different item or asks to browse or list the site.${fastPublish}${fastTranslate}${fastTemplate}${fastEdit}${fastNewContent}${fastOpenPageInquiry}
---"""
  }

  /**
   * Read / interpret the open page (“what is this about?”) — must load repository XML, not answer from memory alone.
   */
  static String fastPathOpenPageInquiryHint(String userPrompt, String repoPath) {
    def p = (userPrompt ?: '').toString()
    def path = normalizeRepoPath(repoPath?.toString())
    if (!path || !authorVisibleSuggestsOpenPageInquiry(p + "\nRepository path: ${path}")) {
      return ''
    }
    if (PUBLISH_NOW_INTENT.matcher(p).find() || CROSS_LANGUAGE_TRANSLATE_INTENT.matcher(p).find()) {
      return ''
    }
    return '''

**Fast path — understand / describe this page:** The author wants an interpretation of **this** open item. Call **GetContent** on **this** **contentPath** in the **first** tool round (plus **GetContentTypeFormDefinition** when field labels help). Optional **GetPreviewHtml** when a preview URL exists. Answer from the XML and preview — **do not** say you lack access to the page. **2–3** 📋 lines; no **WriteContent** unless they ask to change copy.'''
  }

  /**
   * When the author wants to publish what is already open in preview — skip discovery reads.
   */
  static String fastPathPublishHint(String userPrompt, String repoPath) {
    def p = (userPrompt ?: '').toString()
    def path = normalizeRepoPath(repoPath?.toString())
    if (!path || !PUBLISH_NOW_INTENT.matcher(p).find()) {
      return ''
    }
    if (authorVisibleSuggestsPublishSiteBulk(p)) {
      return '''

**Fast path — publish site / everything:** The author wants **entire site** or **first-time** go-live — **not** a single open item. Call **publish_content** with **publishScope** `all` (and **siteId**, **publishingTarget**, default **live**) as your **first** tool after a minimal **## Plan** — **do not** pass only **contentPath** from the open preview item. **Do not** call **GetContent**, **ListPagesAndComponents**, or **open_page_inquiry** first. Use **1–2** 📋 lines. Summarize from the tool result (**publishScope**, path counts) — **never** claim the whole site was published if the tool only deployed one path.'''
    }
    return '''

**Fast path — publish / go live (this item):** Studio context names **Current content item repository path**. Call **publish_content** with **publishScope** `item` and **contentPath** = that path (plus **siteId**, **publishingTarget**, default **live**) as your **first** tool after a minimal **## Plan** — **do not** call **GetContent**, **ListPagesAndComponents**, or **ListContentTranslationScope** first. For **several named paths**, use **publishScope** `paths` with **paths** / **contentPaths** array (one deploy). For **subtree** bulk, use **publishScope** `bulk` and **bulkRootPath** (default `/site`). Use **1–2** 📋 lines only.'''
  }

  /**
   * Cross-language / translate: avoid discovery reads before scoping; never re-list the same page without editing.
   */
  static String fastPathTranslateHint(String userPrompt, String repoPath) {
    def p = (userPrompt ?: '').toString()
    def path = normalizeRepoPath(repoPath?.toString())
    if (!path || !CROSS_LANGUAGE_TRANSLATE_INTENT.matcher(p).find()) {
      return ''
    }
    return '''

**Fast path — translate / localize:** You already have **Current content item repository path**. **Do not** open with **ListPagesAndComponents** or unrelated **GetContent** “discovery” reads. For **full page / everything visible** / **this page** copy: call **ListContentTranslationScope** **once** on that **contentPath**, then **TranslateContentBatch** (same instructions for every path) or **TranslateContentItem** per path — **reuse** the first scope result; **never** call **ListContentTranslationScope** twice on the same **contentPath** in one turn without a write in between. For **only this file / this item** when the author clearly narrowed scope to the open item: **TranslateContentItem** on **this path** alone is enough (no tree) unless they expand scope mid-task. Use **GetContentTypeFormDefinition** only when you need unknown field ids. Keep **## Plan** tight (see system policy).'''
  }

  /**
   * Display / FTL work tied to the open item: resolve {@code display-template} from XML first; avoid serial update_template discovery.
   */
  static String fastPathTemplateDisplayHint(String userPrompt, String repoPath) {
    def p = (userPrompt ?: '').toString()
    def path = normalizeRepoPath(repoPath?.toString())
    if (!path || !TEMPLATE_LAYER_MENTION.matcher(p).find() || !TEMPLATE_DISPLAY_SHAPE_INTENT.matcher(p).find()) {
      return ''
    }
    if (PUBLISH_NOW_INTENT.matcher(p).find() || CROSS_LANGUAGE_TRANSLATE_INTENT.matcher(p).find()) {
      return ''
    }
    return '''

**Fast path — template / how it renders (this preview item):** You have **Current content item repository path**. **Do not** open with **update_template** before reading XML. **Round 1:** **GetContent** on **this** **contentPath** and read **`<display-template>`** for the page shell **`.ftl`**. If the change affects a **listing/cards/grid** fed by **`sections_o`** or other node-selectors, **GetContent** those **referenced `.xml`** paths in the **same** tool round when possible and read **each** `<display-template>` — the real markup is often in a **component** template, not the page **index.xml**. Then **GetContent** or **update_template** on **each distinct `.ftl`**, **WriteContent** each changed template **once**. **Do not** loop **update_template → GetContent** on the same page XML for discovery. **One** **GetPreviewHtml** at the end when a preview URL exists. **2–4** 📋 lines; **do not** re-paste full **## Plan** every tool round.'''
  }

  /**
   * Same-language edits on the open XML only — not “this page” sitewide, not translate, not publish.
   */
  static String fastPathSingleItemEditHint(String userPrompt, String repoPath) {
    def p = (userPrompt ?: '').toString()
    def path = normalizeRepoPath(repoPath?.toString())
    if (!path) {
      return ''
    }
    if (PUBLISH_NOW_INTENT.matcher(p).find() || CROSS_LANGUAGE_TRANSLATE_INTENT.matcher(p).find()) {
      return ''
    }
    if (FULL_PAGE_OR_SITE_COPY_INTENT.matcher(p).find()) {
      return ''
    }
    if (TEMPLATE_LAYER_MENTION.matcher(p).find()) {
      return ''
    }
    if (!SINGLE_ITEM_EDIT_INTENT.matcher(p).find()) {
      return ''
    }
    return '''

**Fast path — edit open item (same language):** Scope is the **current** repository item. Use **GetContent** on **this** **contentPath**, revise fields in the **main** chat, **WriteContent** — **do not** call **TranslateContentItem** / **TranslateContentBatch**. **Do not** call **ListContentTranslationScope** unless the author asked for **referenced components** or **full-page** copy. **Do not** call **ListPagesAndComponents**. Optional **GetPreviewHtml** when preview URL exists. **2–3** 📋 lines.'''
  }

  /**
   * New repository content: avoid hard-coded site shapes; still discourage pointless listing.
   */
  static String fastPathNewContentItemHint(String userPrompt, String repoPath) {
    def p = (userPrompt ?: '').toString()
    def path = normalizeRepoPath(repoPath?.toString())
    if (!path || !NEW_CONTENT_INTENT.matcher(p).find()) {
      return ''
    }
    if (PUBLISH_NOW_INTENT.matcher(p).find()) {
      return ''
    }
    return '''

**Fast path — new content:** Do **not** assume a fixed URL folder layout or **content-type** id for this site. **Do not** open with **ListPagesAndComponents** (especially large **size** or whole-site inventory) — authors see empty chat and long waits; **ListPagesAndComponents** lists **items**, not **types**. Resolve **`contentTypeId`** first: call **ListStudioContentTypes** with **siteId** only (omit **contentPath**) for the **full** catalog (`mode` **all**); **`/page/...` rows are listed before** **`/component/...`**. Add **contentPath** only if you must check **allowed** types under a **known** parent folder — **not** as the default because preview is open on a hub or component. Apply **Exact catalog match beats guessing**: **only** when **exactly one** row **equals** the author’s **type phrase** (normalized **`label`**, **`name`**, or **`name`** tail), set **`contentTypeId`** to **that row’s `name`** — **do not** substitute **`/page/page_generic`**, **`/page/generic-page`**, or another type when that single exact match exists; if **zero** or **many** rows match, **ask** the author. Then **exactly one** **GetContentTypeFormDefinition** with that **`contentTypeId`** — **do not** pass **contentPath** of a **section hub** `…/<section>/index.xml` when its `<content-type>` is a **shell** (e.g. catch-all **`/page/…`**) and the **new** item’s resolved type is **different** — see system **Section hub `index.xml` vs child pages**. **Do not** batch **GetContentTypeFormDefinition** for unrelated **`/component/...`** types. Use **one** **GetContent** on a **sibling** item of the **same** type under the same `/site/website/…` tree when you still need XML field order (not the listing index unless it shares that type). In the **same first assistant `content`** as **## Plan** and **`tool_calls`**, include a **readable draft** of the new piece (title, outline, body in Markdown) so the author can **read while tools run** — **never** leave **`content`** empty on that first tool round. Then **WriteContent**. Use **ListPagesAndComponents** only when the author asked to **browse/search existing pages/items** or you truly lack **any** path hint after **ListStudioContentTypes** — keep **size** modest. **Never** use it right after **GetContentTypeFormDefinition** for a **resolved create** type — that sequence adds **no** value vs **one sibling GetContent**. After a successful **create**, if **formDefinitionXml** has **image-picker** fields and the new item’s XML still has no real images, add **one** optional invite in **## Plan Execution** for **GenerateImage** on author opt-in (see system STUDIO POLICY **New item — offer AI art for empty image fields**). **Before first WriteContent** on the new item, **GetContent** one **existing sibling** of the **same** `<content-type>` when any exist (fixes **`publishedDate_dt`** / **`*_dt`** literals and folder conventions). **Do not** ask “which folder?” before **tool_calls** — start tools immediately. **Do not** stream **`## Plan Execution`** in the same assistant message as **`tool_calls`** — one final recap after all tools (see STUDIO POLICY **Recap boundary**).'''
  }

  /**
   * Maps {@code /site/website/...} repository paths to the Engine browse path used in preview (hint only).
   */
  static String browsePathFromRepoWebsitePath(String repoPath) {
    def p = normalizeRepoPath(repoPath)
    if (!p || !p.startsWith('/site/website/')) return '/'
    def tail = p.substring('/site/website/'.length())
    if (!tail || tail.equalsIgnoreCase('index.xml')) return '/'
    def low = tail.toLowerCase(Locale.ROOT)
    if (low.endsWith('/index.xml')) {
      def folder = tail.substring(0, tail.length() - '/index.xml'.length()).replaceAll('/+$', '')
      return folder ? '/' + folder : '/'
    }
    int slash = tail.lastIndexOf('/')
    if (slash > 0) return '/' + tail.substring(0, slash)
    def fn = tail.replaceAll(/(?i)\.xml$/, '')
    return fn ? '/' + fn : '/'
  }

  /**
   * Same {@code scheme://host:port} as Studio uses for preview (from the servlet request).
   */
  static String previewOriginFromRequest(Object requestRaw) {
    def request = requestRaw
    if (!request) return ''
    try {
      def scheme = request.scheme?.toString() ?: 'http'
      def host = request.serverName?.toString()?.trim()
      if (!host) return ''
      int port = -1
      try {
        def sp = request.serverPort
        if (sp instanceof Integer) {
          port = (Integer) sp
        } else {
          port = Integer.parseInt(sp?.toString() ?: '-1')
        }
      } catch (Throwable ignored) {
        port = -1
      }
      boolean defPort = port < 0 ||
        (scheme.equalsIgnoreCase('https') && port == 443) ||
        (scheme.equalsIgnoreCase('http') && port == 80)
      return "${scheme}://${host}${defPort ? '' : ":${port}"}"
    } catch (Throwable ignored) {
      return ''
    }
  }

  /** True when {@code u} looks like Studio’s XB preview shell ({@code …/studio/preview#/?page=…&site=…}). */
  static boolean looksLikeStudioPreviewShellUrl(Object urlRaw) {
    def u = (urlRaw ?: '').toString().trim()
    if (!u) return false
    if (!u.contains('#')) return false
    return u.toLowerCase(Locale.ROOT).contains('/studio/preview')
  }

  /**
   * Studio Experience Builder address-bar style URL: {@code /studio/preview#/?page=/path&site=siteId} (fragment carries {@code page}).
   * Authors recognize this form; it is **not** valid for raw HTTP GET to fetch Engine HTML.
   */
  static String buildStudioPreviewShellAbsoluteUrl(Object requestRaw, Object siteIdRaw, Object contentPathRaw) {
    def site = (siteIdRaw ?: '').toString().trim()
    def repo = normalizeRepoPath(contentPathRaw?.toString())
    if (!site || !repo) return ''
    String origin = previewOriginFromRequest(requestRaw)
    if (!origin) return ''
    try {
      String browse = browsePathFromRepoWebsitePath(repo)
      if (!browse.startsWith('/')) browse = '/' + browse
      String encPage = URLEncoder.encode(browse, 'UTF-8')
      String encSite = URLEncoder.encode(site, 'UTF-8')
      return "${origin}/studio/preview#/?page=${encPage}&site=${encSite}"
    } catch (Throwable ignored) {
      return ''
    }
  }

  /**
   * Absolute Engine preview URL (same host as the Studio request) for GetPreviewHtml. Empty when inputs are insufficient.
   */
  static String buildEnginePreviewAbsoluteUrl(Object requestRaw, Object siteIdRaw, Object contentPathRaw) {
    def request = requestRaw
    if (!request) return ''
    def site = (siteIdRaw ?: '').toString().trim()
    def repo = normalizeRepoPath(contentPathRaw?.toString())
    if (!site || !repo) return ''
    try {
      String origin = previewOriginFromRequest(requestRaw)
      if (!origin) return ''
      String browse = browsePathFromRepoWebsitePath(repo)
      def encSite = URLEncoder.encode(site, 'UTF-8')
      if (browse == '/' || !browse) {
        return "${origin}/?crafterSite=${encSite}"
      }
      String pathPart = browse.startsWith('/') ? browse : '/' + browse
      return "${origin}${pathPart}?crafterSite=${encSite}"
    } catch (Throwable ignored) {
      return ''
    }
  }

  /**
   * Appends Studio shell URL (author-facing) plus Engine URL (tool-facing for GetPreviewHtml).
   * {@code clientStudioPreviewPageUrlRaw} optional — when the browser already has {@code …/studio/preview#/?page=…&site=…}, pass it so the prompt matches the author’s address bar.
   */
  static String appendEnginePreviewHintIfPossible(
    String prompt,
    Object request,
    Object siteIdRaw,
    Object contentPathRaw,
    Object clientStudioPreviewPageUrlRaw = null
  ) {
    def engineUrl = buildEnginePreviewAbsoluteUrl(request, siteIdRaw, contentPathRaw)
    def studioFromClient = (clientStudioPreviewPageUrlRaw ?: '').toString().trim()
    def studioDisplay = looksLikeStudioPreviewShellUrl(studioFromClient) ? studioFromClient : buildStudioPreviewShellAbsoluteUrl(request, siteIdRaw, contentPathRaw)
    if (!engineUrl && !studioDisplay) return (prompt ?: '').toString()
    def base = (prompt ?: '').toString()
    def studioBlock = studioDisplay ? """--- Studio preview URL (Experience Builder — matches the Studio address bar) ---
${studioDisplay}
When you tell the author **where to open preview** in Studio, use **this** URL (or an equivalent `/studio/preview#/?page=…&site=…` link). **Do not** present a bare Engine browse URL like `http://host/locale/path?crafterSite=…` as the author’s “Studio preview” link — that is for server-side HTML fetch only.

""" : ''
    def engineBlock = engineUrl ? """--- Engine preview URL (**GetPreviewHtml** tool only) ---
**GetPreviewHtml** performs an HTTP GET; it **cannot** use a Studio shell URL (`…/studio/preview#…`) because the `#…` fragment is never sent to a server. After writes that affect rendered output, pass **this** absolute Engine URL as the tool **url** (the plugin rewrites `/studio/preview#…` when needed, but prefer this ready-to-fetch URL):
${engineUrl}
""" : ''
    return """${base}

${studioBlock}${engineBlock}---"""
  }

  /**
   * Minimal notice for {@code authoringSurface: formEngine} only. Does **not** change XB / preview behavior.
   * Strong client-side JSON apply instructions are added only when the client sets {@code formEngineClientJsonApply: true}.
   */
  static String appendFormEngineAuthoringNotice(String prompt) {
    def base = (prompt ?: '').toString()
    return base + '''

--- Studio form-engine context ---
The author is in the **Studio content form** (legacy form engine), not Experience Builder. If this prompt includes a **Current Studio content form** block, it lists **paths and field ids only** (not full item XML/JSON) so you can plan with tools; values may still be **live in the browser** until Save.

**Server-side tools** (GetContent, WriteContent, etc.) read and write **repository** files only; they do not update the open form's in-memory fields.
---'''
  }

  /**
   * Optional extra instructions: Studio form assistant asks the model to return {@code aiassistantFormFieldUpdates} JSON
   * so the browser can apply values. Sent only when the client sets {@code formEngineClientJsonApply: true}.
   * **Never** append for Experience Builder / preview chat ({@code authoringSurface} is not formEngine).
   */
  static String appendFormEngineClientJsonApplyInstructions(String prompt) {
    def base = (prompt ?: '').toString()
    return base + '''

--- Studio form client-apply instructions (JSON for in-browser form only) ---
The client will **parse** a fenced JSON block and **write values into the open form** (not the repository).

When the author asks you to **translate, localize, rewrite, update, improve, shorten, fix, or write** field content, use **GetContent** / **update_content** as needed (full bodies are not inlined in the prompt). Output the new strings in **`aiassistantFormFieldUpdates`** using **field ids** from the metadata appendix or form definition. **Do not** answer with generic CrafterCMS documentation: no "Access the Content Item", "Translation Configuration", "add a supported language", "use Studio translation tools", step-by-step CMS guides, or "click Save" as a substitute for the actual translated text. **Do not** refuse to translate if you can output the target language.

At the **end** of your reply, include:
```json
{ "aiassistantFormFieldUpdates": { "field_id": "new string value", "body_html": "<p>...</p>" } }
```
Use **real field ids** from the form definition / XML in the prompt; values must be **strings** only. List every field you changed. For **pure Q&A** with no content change, omit the JSON block.
---'''
  }
}
