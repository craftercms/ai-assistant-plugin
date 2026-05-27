package plugins.org.craftercms.aiassistant.recipes

import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.general.SlackConfirmationPostFormatter

/**
 * Optional JVM confirmation {@code llmRefine} step before outbound confirmation tools.
 * <ul>
 *   <li>{@code outputFormat: "json"} — model returns one JSON object; required string fields are
 *   {@code outputKeys} on the step. Result is exposed as {@code payload} for {@code $name.key} bindings.
 *   Optional {@code passthroughFromSource} copies keys from {@code ## <heading>} blocks in the assistant turn
 *   without an LLM rewrite (for long bodies such as a full draft on its own Slack post).</li>
 *   <li>{@code outputFormat: "markdown"} (default) — rewrites assistant prose (optional {@code markdownSection}).</li>
 * </ul>
 * Audience, structure, and quality rules belong in the recipe step ({@code userPreamble}, {@code hints},
 * {@code systemPrompt}).
 */
final class AuthoringIntentRecipeLlmRefiner {

  private static final Logger log = LoggerFactory.getLogger(AuthoringIntentRecipeLlmRefiner.class)

  private static final String DEFAULT_USER_PREAMBLE =
    'Refine the draft block below per the system instructions and recipe refine hints.\n\n'

  private AuthoringIntentRecipeLlmRefiner() {}

  /**
   * @param llmContext keys: {@code apiKey}, {@code model}, {@code wireBaseUrl}, optional {@code toolsLoopSessionBundle}
   * @return {@code ok}, {@code skipped}, {@code message}, {@code refinedMarkdown} and/or {@code payload}
   */
  static Map refine(
    String lastAssistantMarkdown,
    Map refineStep,
    Map projectCfg,
    Map llmContext
  ) {
    String source = (lastAssistantMarkdown ?: '').toString().trim()
    if (!source) {
      return [ok: true, skipped: true, message: 'no assistant markdown', refinedMarkdown: source]
    }
    if (!(projectCfg instanceof Map) || !StudioAiAssistantProjectConfig.intentRecipeEngineEnabled(projectCfg)) {
      return [ok: true, skipped: true, message: 'recipe engine disabled', refinedMarkdown: source]
    }
    String apiKey = llmContext?.apiKey?.toString()?.trim() ?: ''
    String model = llmContext?.model?.toString()?.trim() ?: ''
    if (!apiKey || !model) {
      log.warn('AuthoringIntentRecipeLlmRefiner: missing apiKey or model — skip refine')
      return [ok: true, skipped: true, message: 'missing llm credentials', refinedMarkdown: source]
    }
    String outputFormat = refineStep?.get('outputFormat')?.toString()?.trim()?.toLowerCase(Locale.ROOT) ?: 'markdown'
    if ('json'.equals(outputFormat)) {
      return refineStructuredJson(source, refineStep, projectCfg, llmContext)
    }
    return refineMarkdown(source, refineStep, projectCfg, llmContext)
  }

  private static Map refineStructuredJson(
    String sourceMarkdown,
    Map refineStep,
    Map projectCfg,
    Map llmContext
  ) {
    List<String> outputKeys = outputKeysFromStep(refineStep)
    if (outputKeys.isEmpty()) {
      log.error('AuthoringIntentRecipeLlmRefiner: outputFormat=json requires non-empty outputKeys on the refine step')
      return [
        ok     : false,
        skipped: false,
        message: 'outputKeys required for json outputFormat',
        payload: [:]
      ]
    }
    Map<String, List<String>> passthroughSpec = passthroughFromSourceSpec(refineStep)
    Map<String, String> passthrough = extractPassthroughFromSource(sourceMarkdown, refineStep)
    List<String> llmKeys = new ArrayList<>()
    for (String key : outputKeys) {
      if (!passthroughSpec.containsKey(key)) {
        llmKeys.add(key)
      }
    }
    Map payload = new LinkedHashMap<>(passthrough)
    if (!llmKeys.isEmpty()) {
      String system = resolveStructuredJsonSystemPrompt(refineStep, llmKeys)
      StringBuilder user = new StringBuilder()
      String preamble = refineStep?.get('userPreamble')?.toString()?.trim()
      user.append(preamble ?: DEFAULT_USER_PREAMBLE)
      appendStepHints(user, refineStep)
      if (!passthrough.isEmpty()) {
        user.append(
          '\n**Passthrough (do not generate these keys — already taken from SOURCE ## sections):** '
        )
        user.append(passthrough.keySet().join(', '))
        user.append('.\n')
      }
      user.append(
        '\n**Output:** Return **only** one JSON object (no markdown fences) with **exactly** these string keys: '
      )
      user.append(llmKeys.join(', '))
      user.append(
        '.\nEach value is the full message body for that outbound post (Slack mrkdwn). Do not nest objects.\n'
      )
      user.append(
        'When a value uses several emoji-shortcode labels (`:name:`), put **one label per line** (newline before each shortcode after the first).\n'
      )
      user.append('\n---\n\n**SOURCE (author request, reference fetch, assistant notes):**\n\n')
      user.append(sourceMarkdown.trim())
      user.append('\n')
      if (sourceMarkdown.contains('## Author request') && sourceMarkdown.contains('## Reference material')) {
        user.append(
          '\n**Grounding:** Blog draft keys must summarize the **author request** using facts from **Reference material** — ' +
            'do not invent unrelated topics (generic CMS operations, workflow tips, etc.) when the author named specific subjects or a source URL.\n'
        )
      }

      String apiKey = llmContext?.apiKey?.toString()?.trim() ?: ''
      String model = llmContext?.model?.toString()?.trim() ?: ''
      int maxOut = structuredJsonMaxOutTokens(projectCfg, llmKeys.size())
      int readTimeout = StudioAiAssistantProjectConfig.intentRecipeConfirmationLlmRefineReadTimeoutMs(projectCfg)
      Map bundle = llmContext?.toolsLoopSessionBundle instanceof Map ? (Map) llmContext.toolsLoopSessionBundle : null
      String raw = ''
      try {
        raw = AiOrchestration.toolsLoopSimpleCompletionAssistantText(
          apiKey,
          model,
          system,
          user.toString(),
          maxOut,
          readTimeout,
          'RecipeConfirmationStructuredJson',
          llmContext?.wireBaseUrl?.toString(),
          bundle
        )?.trim() ?: ''
      } catch (Throwable t) {
        log.error('AuthoringIntentRecipeLlmRefiner: structured JSON refine failed: {}', t.message ?: t.toString())
        return [ok: false, skipped: false, message: t.message ?: t.toString(), payload: payload]
      }
      if (!raw) {
        log.error('AuthoringIntentRecipeLlmRefiner: structured JSON refine returned empty')
        return [ok: false, skipped: true, message: 'empty refine response', payload: payload]
      }
      Map llmPayload = parseJsonObjectPayload(raw)
      if (!(llmPayload instanceof Map) || llmPayload.isEmpty()) {
        log.error('AuthoringIntentRecipeLlmRefiner: structured JSON refine did not parse to an object')
        return [ok: false, skipped: false, message: 'invalid JSON object in refine response', payload: payload]
      }
      for (String key : llmKeys) {
        payload.put(key, llmPayload.get(key))
      }
    }
    fillPassthroughFallbacks(sourceMarkdown, refineStep, payload, outputKeys, projectCfg, llmContext)
    List<String> missing = missingPayloadKeys(payload, outputKeys)
    if (!missing.isEmpty()) {
      log.error(
        'AuthoringIntentRecipeLlmRefiner: structured JSON refine missing required keys: {}',
        missing.join(', ')
      )
      return [
        ok          : false,
        skipped     : false,
        message     : 'missing keys: ' + missing.join(', '),
        payload     : payload,
        missingKeys : missing
      ]
    }
    Map normalized = normalizePayloadStrings(payload, outputKeys)
    log.info(
      'AuthoringIntentRecipeLlmRefiner: structured JSON refine ok ({} keys, {} passthrough)',
      outputKeys.size(),
      passthrough.size()
    )
    return [
      ok              : true,
      skipped         : false,
      outputFormat    : 'json',
      payload         : normalized,
      refinedMarkdown : buildAuthorPreviewMarkdown(normalized, outputKeys),
      profile         : refineStep?.get('llmRefine')?.toString()?.trim() ?: '',
      passthroughKeys : new ArrayList<>(passthrough.keySet())
    ]
  }

  private static Map refineMarkdown(
    String sourceMarkdown,
    Map refineStep,
    Map projectCfg,
    Map llmContext
  ) {
    String sectionHeading = refineStep?.get('markdownSection')?.toString()?.trim() ?: ''
    boolean sectionRequested = !!sectionHeading
    String sectionBody = sectionRequested ?
      RecipeMarkdownSections.extractSection(sourceMarkdown, sectionHeading) :
      sourceMarkdown
    boolean sectionFound = !!sectionBody?.trim()
    if (sectionRequested && !sectionFound) {
      sectionBody = sourceMarkdown
    }
    String system = resolveMarkdownSystemPrompt(refineStep)
    StringBuilder user = new StringBuilder()
    String preamble = refineStep?.get('userPreamble')?.toString()?.trim()
    user.append(preamble ?: DEFAULT_USER_PREAMBLE)
    appendStepHints(user, refineStep)
    user.append('---\n\n').append(sectionBody.trim()).append('\n')
    int maxOut = StudioAiAssistantProjectConfig.intentRecipeConfirmationLlmRefineMaxOutTokens(projectCfg)
    int readTimeout = StudioAiAssistantProjectConfig.intentRecipeConfirmationLlmRefineReadTimeoutMs(projectCfg)
    Map bundle = llmContext?.toolsLoopSessionBundle instanceof Map ? (Map) llmContext.toolsLoopSessionBundle : null
    String refinedSection = ''
    try {
      refinedSection = AiOrchestration.toolsLoopSimpleCompletionAssistantText(
        llmContext.apiKey.toString(),
        llmContext.model.toString(),
        system,
        user.toString(),
        maxOut,
        readTimeout,
        'RecipeConfirmationLlmRefine',
        llmContext?.wireBaseUrl?.toString(),
        bundle
      )?.trim() ?: ''
    } catch (Throwable t) {
      log.warn('AuthoringIntentRecipeLlmRefiner: markdown refine failed: {}', t.message)
      return [
        ok              : false,
        skipped         : false,
        message         : t.message ?: t.toString(),
        refinedMarkdown : sourceMarkdown
      ]
    }
    if (!refinedSection) {
      return [ok: true, skipped: true, message: 'empty refine response', refinedMarkdown: sourceMarkdown]
    }
    String full = (sectionRequested && sectionFound) ?
      RecipeMarkdownSections.replaceSection(sourceMarkdown, sectionHeading, refinedSection) :
      refinedSection
    return [
      ok               : true,
      skipped          : false,
      outputFormat     : 'markdown',
      refinedMarkdown  : full,
      profile          : refineStep?.get('llmRefine')?.toString()?.trim() ?: '',
      sectionCharsIn   : sectionBody.length(),
      sectionCharsOut  : refinedSection.length(),
      markdownSection  : sectionHeading ?: null
    ]
  }

  /**
   * {@code passthroughFromSource} on the refine step: map of payload key → markdown {@code ##} heading
   * or list of headings to try (e.g. {@code "draft": ["Draft body", "Pitch draft"]}).
   * Body is copied from the assistant turn without LLM rewrite.
   */
  static Map<String, String> extractPassthroughFromSource(String sourceMarkdown, Map refineStep) {
    Map<String, String> out = new LinkedHashMap<>()
    if (!(refineStep instanceof Map)) {
      return out
    }
    Map<String, List<String>> spec = passthroughFromSourceSpec(refineStep)
    if (spec.isEmpty()) {
      return out
    }
    String source = (sourceMarkdown ?: '').toString().trim()
    for (Map.Entry<String, List<String>> entry : spec.entrySet()) {
      String payloadKey = entry.key
      String body = extractFirstSectionForHeadings(source, entry.value)
      if (!body) {
        log.warn(
          'AuthoringIntentRecipeLlmRefiner: passthrough key={} — no section for headings: {}',
          payloadKey,
          entry.value.join(', ')
        )
        continue
      }
      out.put(payloadKey, body)
    }
    return out
  }

  static Map<String, List<String>> passthroughFromSourceSpec(Map refineStep) {
    Map<String, List<String>> spec = new LinkedHashMap<>()
    if (!(refineStep instanceof Map)) {
      return spec
    }
    Object raw = refineStep.get('passthroughFromSource')
    if (!(raw instanceof Map) || ((Map) raw).isEmpty()) {
      return spec
    }
    for (Map.Entry entry : ((Map) raw).entrySet()) {
      String payloadKey = entry.key?.toString()?.trim()
      if (!payloadKey) {
        continue
      }
      List<String> headings = headingsFromPassthroughValue(entry.value)
      if (!headings.isEmpty()) {
        spec.put(payloadKey, headings)
      }
    }
    return spec
  }

  private static List<String> headingsFromPassthroughValue(Object value) {
    List<String> headings = new ArrayList<>()
    if (value instanceof List) {
      for (Object h : (List) value) {
        String s = h?.toString()?.trim()
        if (s && !headings.contains(s)) {
          headings.add(s)
        }
      }
    } else {
      String s = value?.toString()?.trim()
      if (s) {
        headings.add(s)
      }
    }
    return headings
  }

  private static String extractFirstSectionForHeadings(String sourceMarkdown, List<String> headings) {
    if (!(headings instanceof List) || headings.isEmpty()) {
      return ''
    }
    String source = (sourceMarkdown ?: '').toString().trim()
    for (String heading : headings) {
      String body = RecipeMarkdownSections.extractSection(source, heading)?.trim()
      if (body) {
        return body
      }
    }
    return ''
  }

  /**
   * When {@code passthroughFromSource} did not find a section, optional {@code passthroughFallbackHints}
   * trigger a dedicated single-key JSON completion (higher token budget for long bodies).
   */
  private static void fillPassthroughFallbacks(
    String sourceMarkdown,
    Map refineStep,
    Map payload,
    List<String> outputKeys,
    Map projectCfg,
    Map llmContext
  ) {
    Map<String, List<String>> spec = passthroughFromSourceSpec(refineStep)
    if (spec.isEmpty()) {
      return
    }
    for (String key : outputKeys) {
      if (!spec.containsKey(key)) {
        continue
      }
      String existing = payload.get(key)?.toString()?.trim()
      if (existing) {
        continue
      }
      String generated = generatePassthroughFallbackKey(
        sourceMarkdown,
        refineStep,
        key,
        projectCfg,
        llmContext
      )
      if (generated) {
        payload.put(key, generated)
        log.info(
          'AuthoringIntentRecipeLlmRefiner: passthrough fallback generated key={} ({} chars)',
          key,
          generated.length()
        )
      } else {
        log.error(
          'AuthoringIntentRecipeLlmRefiner: passthrough fallback failed for key={} — Slack post will be empty',
          key
        )
      }
    }
  }

  private static String generatePassthroughFallbackKey(
    String sourceMarkdown,
    Map refineStep,
    String key,
    Map projectCfg,
    Map llmContext
  ) {
    String apiKey = llmContext?.apiKey?.toString()?.trim() ?: ''
    String model = llmContext?.model?.toString()?.trim() ?: ''
    if (!apiKey || !model) {
      return ''
    }
    List<String> keys = [key]
    String system = resolveStructuredJsonSystemPrompt(refineStep, keys)
    StringBuilder user = new StringBuilder()
    user.append(
      'The assistant turn below did not include a usable `##` section for passthrough. '
    )
    user.append('Generate **only** the JSON key **')
    user.append(key)
    user.append('** now (full body for its own Slack message — do not shorten).\n\n')
    appendPassthroughFallbackHints(user, refineStep, key)
    user.append('\n**Output:** Return **only** one JSON object with **exactly** this string key: ')
    user.append(key)
    user.append('.\n\n---\n\n**SOURCE:**\n\n')
    user.append((sourceMarkdown ?: '').trim())
    user.append('\n')
    int maxOut = passthroughFallbackMaxOutTokens(projectCfg, refineStep, key)
    int readTimeout = StudioAiAssistantProjectConfig.intentRecipeConfirmationLlmRefineReadTimeoutMs(projectCfg)
    Map bundle = llmContext?.toolsLoopSessionBundle instanceof Map ? (Map) llmContext.toolsLoopSessionBundle : null
    String raw = ''
    try {
      raw = AiOrchestration.toolsLoopSimpleCompletionAssistantText(
        apiKey,
        model,
        system,
        user.toString(),
        maxOut,
        readTimeout,
        'RecipeConfirmationPassthroughFallback',
        llmContext?.wireBaseUrl?.toString(),
        bundle
      )?.trim() ?: ''
    } catch (Throwable t) {
      log.error(
        'AuthoringIntentRecipeLlmRefiner: passthrough fallback key={} failed: {}',
        key,
        t.message ?: t.toString()
      )
      return ''
    }
    Map parsed = parseJsonObjectPayload(raw)
    return parsed.get(key)?.toString()?.trim() ?: ''
  }

  private static void appendPassthroughFallbackHints(StringBuilder user, Map refineStep, String key) {
    if (!(refineStep instanceof Map) || !key) {
      return
    }
    Object hintsRoot = refineStep.get('passthroughFallbackHints')
    if (!(hintsRoot instanceof Map)) {
      return
    }
    Object hints = ((Map) hintsRoot).get(key)
    if (!(hints instanceof List) || ((List) hints).isEmpty()) {
      return
    }
    user.append('\n**Passthrough fallback hints:**\n')
    for (Object h : (List) hints) {
      String line = h?.toString()?.trim()
      if (line) {
        user.append('- ').append(line).append('\n')
      }
    }
  }

  private static int passthroughFallbackMaxOutTokens(Map projectCfg, Map refineStep, String key) {
    if (refineStep instanceof Map) {
      Object perKey = refineStep.get('passthroughFallbackMaxOutTokens')
      if (perKey instanceof Map) {
        Object v = ((Map) perKey).get(key)
        if (v != null) {
          try {
            int n = Integer.parseInt(v.toString().trim())
            if (n >= 512) {
              return Math.min(n, 16_384)
            }
          } catch (NumberFormatException ignored) {
          }
        }
      }
    }
    int cfgMax = StudioAiAssistantProjectConfig.intentRecipeConfirmationLlmRefineMaxOutTokens(projectCfg)
    if ('draft'.equals(key)) {
      return Math.max(cfgMax, 8192)
    }
    return Math.max(cfgMax, 4096)
  }

  static List<String> outputKeysFromStep(Map refineStep) {
    List<String> out = new ArrayList<>()
    if (!(refineStep instanceof Map)) {
      return out
    }
    Object keys = refineStep.get('outputKeys')
    if (keys instanceof List) {
      for (Object k : (List) keys) {
        String s = k?.toString()?.trim()
        if (s && !out.contains(s)) {
          out.add(s)
        }
      }
    }
    return out
  }

  private static List<String> missingPayloadKeys(Map payload, List<String> requiredKeys) {
    List<String> missing = new ArrayList<>()
    for (String key : requiredKeys) {
      String v = payload.get(key)?.toString()?.trim()
      if (!v) {
        missing.add(key)
      }
    }
    return missing
  }

  private static Map normalizePayloadStrings(Map payload, List<String> keys) {
    Map out = new LinkedHashMap()
    for (String key : keys) {
      String v = payload.get(key)?.toString()?.trim() ?: ''
      if (v) {
        v = SlackConfirmationPostFormatter.ensureEmojiLabelLineBreaks(v)
      }
      out.put(key, v)
    }
    return out
  }

  private static Map parseJsonObjectPayload(String raw) {
    String s = (raw ?: '').trim()
    if (!s) {
      return [:]
    }
    if (s.startsWith('```')) {
      s = s.replaceFirst('(?is)^```(?:json)?\\s*', '')
      s = s.replaceFirst('(?is)```\\s*$', '')
      s = s.trim()
    }
    try {
      Object parsed = new JsonSlurper().parseText(s)
      if (parsed instanceof Map) {
        return (Map) parsed
      }
    } catch (Throwable t) {
      log.warn('AuthoringIntentRecipeLlmRefiner: JSON parse failed: {}', t.message)
    }
    return [:]
  }

  static String buildAuthorPreviewMarkdown(Map payload, List<String> keyOrder) {
    if (!(payload instanceof Map) || payload.isEmpty()) {
      return ''
    }
    List<String> keys = keyOrder instanceof List && !keyOrder.isEmpty() ?
      keyOrder :
      new ArrayList<>(payload.keySet())
    StringBuilder sb = new StringBuilder()
    for (String key : keys) {
      String body = payload.get(key)?.toString()?.trim()
      if (!body) {
        continue
      }
      sb.append('## ').append(key).append('\n\n').append(body).append('\n\n')
    }
    return sb.toString().trim()
  }

  private static int structuredJsonMaxOutTokens(Map projectCfg, int keyCount) {
    int cfgMax = StudioAiAssistantProjectConfig.intentRecipeConfirmationLlmRefineMaxOutTokens(projectCfg)
    if (keyCount >= 5) {
      return Math.max(cfgMax, 8192)
    }
    if (keyCount >= 3) {
      return Math.max(cfgMax, 6144)
    }
    return Math.max(cfgMax, 4096)
  }

  private static String resolveMarkdownSystemPrompt(Map refineStep) {
    String custom = refineStep?.get('systemPrompt')?.toString()?.trim()
    return custom ?: ToolPrompts.getLlm_RECIPE_CONFIRMATION_LLM_REFINE_SYSTEM()
  }

  private static String resolveStructuredJsonSystemPrompt(Map refineStep, List<String> outputKeys) {
    String custom = refineStep?.get('systemPrompt')?.toString()?.trim()
    if (custom) {
      return custom
    }
    return ToolPrompts.getLlm_RECIPE_CONFIRMATION_STRUCTURED_JSON_SYSTEM(outputKeys)
  }

  private static void appendStepHints(StringBuilder user, Map refineStep) {
    if (!(refineStep instanceof Map)) {
      return
    }
    Object hints = refineStep.get('hints')
    if (!(hints instanceof List) || ((List) hints).isEmpty()) {
      return
    }
    user.append('\n**Recipe refine hints:**\n')
    for (Object h : (List) hints) {
      String line = h?.toString()?.trim()
      if (line) {
        user.append('- ').append(line).append('\n')
      }
    }
    user.append('\n')
  }
}
