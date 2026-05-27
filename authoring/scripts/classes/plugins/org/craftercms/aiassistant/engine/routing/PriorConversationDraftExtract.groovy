package plugins.org.craftercms.aiassistant.engine.routing

import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig

/**
 * Extracts prior-turn draft prose using recipe or project {@code intentRecipeRouting} config — not hardcoded
 * site content-type or blog labels.
 */
final class PriorConversationDraftExtract {

  /**
   * Private constructor; not for direct use.
   */
private PriorConversationDraftExtract() {}

  /**
   * Extracts draft body from repository XML or related text.
   * @param priorBody Caller-supplied input.
   * @param supplementConfig Caller-supplied input.
   * @param projectCfg Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String extractDraftBody(String priorBody, Map supplementConfig, Map projectCfg = null) {
    String prior = (priorBody ?: '').toString()
    if (!prior.trim()) {
      return ''
    }
    List<String> headings = mergeStringLists(
      stringList(supplementConfig?.priorDraftSectionHeadings),
      stringList(projectPriorDraftDetection(projectCfg)?.priorDraftSectionHeadings)
    )
    List<String> markers = mergeStringLists(
      stringList(supplementConfig?.priorDraftInlineMarkers),
      stringList(projectPriorDraftDetection(projectCfg)?.priorDraftInlineMarkers)
    )
    for (String marker : markers) {
      String fromMarker = extractAfterInlineMarker(prior, marker, 80)
      if (fromMarker) {
        return fromMarker
      }
    }
    for (String heading : headings) {
      String section = RecipeMarkdownSections.extractSection(prior, heading)?.trim()
      if (!section) {
        continue
      }
      for (String nestedMarker : markers) {
        String nested = extractAfterInlineMarker(section, nestedMarker, 80)
        if (nested) {
          return nested
        }
      }
      if (section.length() > 80) {
        return section
      }
    }
    return lastAssistantBlockText(prior)
  }

  /**
   * Label after configured markers (e.g. {@code Author voice: **Name**}) from prior assistant prose.
   */
  static String extractAuthorVoiceLabel(String priorBody, Map supplementConfig, Map projectCfg = null) {
    String prior = (priorBody ?: '').toString()
    if (!prior.trim()) {
      return ''
    }
    List<String> markers = mergeStringLists(
      stringList(supplementConfig?.priorAuthorVoiceInlineMarkers),
      stringList(projectPriorDraftDetection(projectCfg)?.priorAuthorVoiceInlineMarkers)
    )
    for (String marker : markers) {
      String v = extractInlineMarkerValue(prior, marker)
      if (v) {
        return normalizeAuthorVoiceLabel(v)
      }
    }
    def bold = (prior =~ /(?is)Author\s+voice\s*:\s*\*{1,2}([^*\n]+?)\*{1,2}/)
    if (bold.find()) {
      return normalizeAuthorVoiceLabel((bold.group(1) ?: '').toString())
    }
    return ''
  }

  /** Strips markdown emphasis and trailing persona clauses (e.g. {@code Sara** | Engaging…}). */
  static String normalizeAuthorVoiceLabel(String raw) {
    String v = (raw ?: '').toString().trim()
    if (!v) {
      return ''
    }
    v = v.replaceAll(/^\*+/, '').replaceAll(/\*+$/, '').trim()
    v = v.replaceAll(/\*{1,2}/, '').trim()
    int pipe = v.indexOf('|')
    if (pipe > 0) {
      v = v.substring(0, pipe).trim()
    }
    return v.replaceAll(/^\*+|\*+$/, '').trim()
  }

  /**
   * Extracts draft title from repository XML or related text.
   * @param priorBody Caller-supplied input.
   * @param supplementConfig Caller-supplied input.
   * @param projectCfg Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String extractDraftTitle(String priorBody, Map supplementConfig, Map projectCfg = null) {
    String prior = (priorBody ?: '').toString()
    if (!prior.trim()) {
      return ''
    }
    List<String> headings = mergeStringLists(
      stringList(supplementConfig?.priorTitleSectionHeadings),
      stringList(projectPriorDraftDetection(projectCfg)?.priorTitleSectionHeadings)
    )
    List<String> markers = mergeStringLists(
      stringList(supplementConfig?.priorTitleInlineMarkers),
      stringList(projectPriorDraftDetection(projectCfg)?.priorTitleInlineMarkers)
    )
    for (String marker : markers) {
      String t = extractInlineMarkerValue(prior, marker)
      if (t) {
        return t
      }
    }
    for (String heading : headings) {
      String section = RecipeMarkdownSections.extractSection(prior, heading)?.trim()
      if (section) {
        String firstLine = section.readLines().find { it?.trim() }?.trim()
        if (firstLine && firstLine.length() > 2) {
          return firstLine.replaceAll(/^\*+|\*+$/, '').trim()
        }
      }
    }
    return inferTitleFromDraftProse(lastAssistantBlockText(prior))
  }

  /**
   * Human-readable label for repository {@code file-name} / slug: configured title markers first, then
   * a sensible line from draft prose — never a date-based synthetic name.
   */
  static String resolveItemNameForSlug(
    String priorBody,
    String draftTitle,
    String draftBody,
    Map supplementConfig,
    Map projectCfg = null
  ) {
    String title = (draftTitle ?: '').toString().trim()
    if (!title) {
      title = extractDraftTitle(priorBody, supplementConfig, projectCfg)?.trim()
    }
    if (title) {
      return title
    }
    String body = (draftBody ?: '').toString().trim()
    if (!body) {
      body = extractDraftBody(priorBody, supplementConfig, projectCfg)?.trim()
    }
    return inferTitleFromDraftProse(body)
  }

  /**
   * Prior conversation contains extractable draft.
   * @param priorBody Caller-supplied input.
   * @param supplementConfig Caller-supplied input.
   * @param projectCfg Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean priorConversationContainsExtractableDraft(String priorBody, Map supplementConfig, Map projectCfg) {
    return extractDraftBody(priorBody, supplementConfig, projectCfg)?.trim()?.length() > 80
  }

  /**
   * First sentence of configured draft body markers (plain text) for SEO fields on create-from-draft.
   */
  static String extractDraftFirstSentence(String priorBody, Map supplementConfig, Map projectCfg, int maxLen) {
    String body = extractDraftBody(priorBody, supplementConfig, projectCfg)?.trim()
    return firstPlainSentence(body, maxLen)
  }

  /**
   * First plain sentence.
   * @param plain Caller-supplied input.
   * @param maxLen Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String firstPlainSentence(String plain, int maxLen) {
    String t = (plain ?: '').replaceAll(/<[^>]+>/, ' ').replaceAll(/\s+/, ' ').trim()
    if (!t) {
      return ''
    }
    int cap = maxLen > 0 ? maxLen : 250
    int end = t.indexOf('. ')
    String sentence = end > 20 ? t.substring(0, end + 1).trim() : t
    if (sentence.length() > cap) {
      sentence = sentence.substring(0, Math.max(0, cap - 3)).trim() + '...'
    }
    return sentence
  }

  /**
   * Prior abbreviated block has enough assistant prose to persist — last {@code Assistant:} reply length,
   * not configured marker phrases.
   */
  static boolean priorConversationHasMaterializableAssistantReply(String priorBody, int minChars = 200) {
    String last = lastAssistantBlockText(priorBody)
    return last.length() >= Math.max(80, minChars)
  }

  /**
   * Prior chat block has prose the author may want persisted (configured markers, substantial {@code ##} sections,
   * or a long last assistant reply).
   */
  static boolean priorConversationContainsActionableContent(String priorBody, Map supplementConfig, Map projectCfg) {
    if (priorConversationContainsExtractableDraft(priorBody, supplementConfig, projectCfg)) {
      return true
    }
    if (priorConversationContainsSubstantialMarkdownSection(priorBody)) {
      return true
    }
    return priorConversationLastAssistantBlockSubstantial(priorBody, 200)
  }

  /**
   * Generic fallback when no headings/markers are configured: prior turn has a substantial {@code ##} section.
   */
  static boolean priorConversationContainsSubstantialMarkdownSection(String priorBody) {
    String prior = (priorBody ?: '').toString().trim()
    if (!prior) {
      return false
    }
    def m = (prior =~ /(?ism)^##\s+[^\n]+\r?\n([\s\S]{120,}?)(?=^##\s|\z)/)
    return m.find()
  }

  /**
   * Last {@code Assistant:} block in the abbreviated prior-conversation wire format is long enough to materialize.
   */
  static boolean priorConversationLastAssistantBlockSubstantial(String priorBody, int minChars) {
    String prior = (priorBody ?: '').toString()
    if (!prior.trim() || minChars <= 0) {
      return false
    }
    String lastAssistant = ''
    def m = (prior =~ /(?is)Assistant:\s*([\s\S]*?)(?=\nUser:|\z)/)
    while (m.find()) {
      lastAssistant = (m.group(1) ?: '').toString().trim()
    }
    return lastAssistant.length() >= minChars
  }

  /**
   * Confirmation follow up section headings.
   * @param recipe Caller-supplied input.
   * @param supplementConfig Caller-supplied input.
   * @return List<String> result.
   */
  static List<String> confirmationFollowUpSectionHeadings(Map recipe, Map supplementConfig) {
    List<String> fromRecipe = stringList(recipe?.confirmationFollowUpChatSections)
    if (!fromRecipe.isEmpty()) {
      return fromRecipe
    }
    return mergeStringLists(
      stringList(supplementConfig?.confirmationFollowUpSectionHeadings),
      stringList(supplementConfig?.priorDraftSectionHeadings)
    )
  }

  /**
   * First configured markdown heading for a confirmation payload key (e.g. {@code draft} → {@code Draft body}).
   */
  static String followUpHeadingForPayloadKey(Map refineStep, String payloadKey) {
    if (!(refineStep instanceof Map) || !(payloadKey ?: '').toString().trim()) {
      return ''
    }
    Map spec = AuthoringIntentRecipeLlmRefiner.passthroughFromSourceSpec(refineStep)
    List<String> headings = spec.get(payloadKey?.toString()?.trim())
    return headings && !headings.isEmpty() ? headings[0]?.toString()?.trim() : ''
  }

  /**
   * Project prior draft detection.
   * @param projectCfg Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  private static Map projectPriorDraftDetection(Map projectCfg) {
    if (!(projectCfg instanceof Map)) {
      return [:]
    }
    Object o = StudioAiAssistantProjectConfig.intentRecipeRoutingSection(projectCfg)?.get('priorDraftDetection')
    return o instanceof Map ? (Map) o : [:]
  }

  /**
   * Extracts after inline marker from repository XML or related text.
   * @param text Caller-supplied input.
   * @param marker Caller-supplied input.
   * @param minChars Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String extractAfterInlineMarker(String text, String marker, int minChars) {
    String m = (marker ?: '').toString().trim()
    if (!m || !(text ?: '').toString().trim()) {
      return ''
    }
    String escaped = java.util.regex.Pattern.quote(m)
    def match = (text =~ /(?is)${escaped}\s*([\s\S]+)/)
    if (!match.find()) {
      return ''
    }
    String body = (match.group(1) ?: '').toString().trim()
    body = body.replaceFirst(/(?is)\n##\s+.*$/, '').trim()
    return body.length() >= minChars ? body : ''
  }

  /**
   * Infer title from draft prose.
   * @param draftBody Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String inferTitleFromDraftProse(String draftBody) {
    String body = (draftBody ?: '').toString().trim()
    if (!body) {
      return ''
    }
    def mdHeading = (body =~ /(?m)^#{1,3}\s+(.+)$/)
    if (mdHeading.find()) {
      String h = (mdHeading.group(1) ?: '').toString().trim()
      if (h.length() >= 3 && h.length() <= 120) {
        return h
      }
    }
    for (String line : body.split(/\r?\n/)) {
      String t = (line ?: '').trim()
      if (!t || t.startsWith('*') || t.startsWith('-') || t.startsWith('#')) {
        continue
      }
      if (t.length() >= 3 && t.length() <= 120) {
        return t
      }
    }
    String flat = body.replaceAll(/\s+/, ' ').trim()
    if (flat.length() < 10) {
      return ''
    }
    int period = flat.indexOf('.')
    if (period >= 10 && period <= 120) {
      return flat.substring(0, period).trim()
    }
    return flat.length() > 100 ? flat.substring(0, 100).trim() : flat
  }

  /** Last {@code Assistant:} block in the client prior-conversation wire format. */
  static String lastAssistantBlockText(String priorBody) {
    String prior = (priorBody ?: '').toString()
    if (!prior.trim()) {
      return ''
    }
    String lastAssistant = ''
    def m = (prior =~ /(?is)Assistant:\s*([\s\S]*?)(?=\nUser:|\z)/)
    while (m.find()) {
      lastAssistant = (m.group(1) ?: '').toString().trim()
    }
    return lastAssistant
  }

  /**
   * Extracts inline marker value from repository XML or related text.
   * @param text Caller-supplied input.
   * @param marker Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String extractInlineMarkerValue(String text, String marker) {
    String m = (marker ?: '').toString().trim()
    if (!m) {
      return ''
    }
    String escaped = java.util.regex.Pattern.quote(m)
    def match = (text =~ /(?is)${escaped}\s*([^\n]+)/)
    if (!match.find()) {
      return ''
    }
    return (match.group(1) ?: '').toString().replaceAll(/^\*+|\*+$/, '').trim()
  }

  /**
   * String list.
   * @param raw Caller-supplied input.
   * @return List<String> result.
   */
  private static List<String> stringList(Object raw) {
    if (!(raw instanceof List)) {
      return []
    }
    List<String> out = []
    for (Object o : (List) raw) {
      String s = o?.toString()?.trim()
      if (s) {
        out.add(s)
      }
    }
    return out
  }

  /**
   * Merges string lists without dropping prior conversation context.
   * @param a Caller-supplied input.
   * @param b Caller-supplied input.
   * @return List<String> result.
   */
  private static List<String> mergeStringLists(List<String> a, List<String> b) {
    LinkedHashSet<String> merged = new LinkedHashSet<>()
    if (a) {
      merged.addAll(a)
    }
    if (b) {
      merged.addAll(b)
    }
    return new ArrayList<>(merged)
  }
}
