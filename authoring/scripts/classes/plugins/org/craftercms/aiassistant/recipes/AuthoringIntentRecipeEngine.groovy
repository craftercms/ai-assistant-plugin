package plugins.org.craftercms.aiassistant.recipes

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Deterministic **read-only** prefetch: runs {@code engineSteps} from a matched recipe on the Studio JVM
 * (same request as the intent router), then formats a compact block for the main tools-loop user message.
 */
final class AuthoringIntentRecipeEngine {

  private static final Logger log = LoggerFactory.getLogger(AuthoringIntentRecipeEngine.class)

  private static final Pattern STEP_REF = Pattern.compile('^\\$step(\\d+)\\.(.+)$')

  private AuthoringIntentRecipeEngine() {}

  private static final Set<String> READ_ONLY_TOOLS =
    plugins.org.craftercms.aiassistant.tools.catalog.StudioAiToolRegistry.recipeEngineReadOnlyWireNames()

  /**
   * Runs each read-only {@code engineSteps} entry on the Studio JVM before the tools-loop LLM turn: resolves args
   * from authoring bindings, executes allowlisted tools, builds named {@code initial.*} bindings, and returns a
   * fenced JSON markdown block plus step summaries for the user message.
   *
   * @return map keys: {@code markdown}, {@code prefetchSteps}, {@code prefetchEnvelopeTruncated}, {@code initialBindings}
   */
  static Map runPrefetchBlock(StudioToolOperations ops, Map recipe, Map projectCfg) {
    return runPrefetchBlock(ops, recipe, projectCfg, null)
  }

  /**
   * Same as {@link #runPrefetchBlock(StudioToolOperations, Map, Map)} with a custom markdown header label
   * (e.g. {@code intent routing prefetch (initial)} for {@link AuthoringIntentRoutingEngine}).
   *
   * @param blockLabel when set, replaces the default {@code recipe engine prefetch} marker in the markdown header
   */
  static Map runPrefetchBlock(StudioToolOperations ops, Map recipe, Map projectCfg, String blockLabel) {
    Map empty = [
      markdown                   : '',
      prefetchSteps                : [],
      prefetchEnvelopeTruncated    : false
    ]

    if (ops == null || recipe == null || projectCfg == null) {
      return empty
    }
    if (!StudioAiAssistantProjectConfig.intentRecipeEngineEnabled(projectCfg)) {
      return empty
    }

    List<Map> steps = AuthoringIntentRecipeCatalog.collectEngineSteps(recipe)
    if (steps.isEmpty()) {
      return empty
    }

    int maxSteps = StudioAiAssistantProjectConfig.intentRecipeEngineMaxSteps(projectCfg)
    int maxTotal = StudioAiAssistantProjectConfig.intentRecipeEngineMaxTotalChars(projectCfg)
    int maxField = StudioAiAssistantProjectConfig.intentRecipeEngineMaxFieldChars(projectCfg)
    if (steps.size() > maxSteps) {
      steps = new ArrayList<>(steps.subList(0, maxSteps))
    }

    Map bindings = ops.recipeEngineAuthoringBindings()
    List<Map> stepSummaries = new ArrayList<>()
    List<Map> stepResults = new ArrayList<>()
    Map<String, Map> namedSoFar = new LinkedHashMap<>()
    int index = 0

    for (Object stepObj : steps) {
      if (!(stepObj instanceof Map)) {
        index++
        continue
      }

      Map step = (Map) stepObj
      String tool = step.get('tool')?.toString()?.trim()

      if (!tool || !READ_ONLY_TOOLS.contains(tool)) {
        stepSummaries.add([
          index: index,
          tool : tool ?: '(missing)',
          ok   : false,
          error: 'tool not allowlisted for recipe engine (read-only built-ins only)'
        ])
        stepResults.add([:] as Map)
        index++
        continue
      }

      Object argsObj = step.get('args')
      Map argsTemplate = argsObj instanceof Map ? (Map) argsObj : [:]
      Map resolvedArgs

      try {
        resolvedArgs = resolveArgsMap(argsTemplate, bindings, stepResults, namedSoFar, namedSoFar)
      } catch (Throwable te) {
        stepSummaries.add([index: index, tool: tool, ok: false, error: 'arg resolution: ' + te.message])
        stepResults.add([:] as Map)
        index++
        continue
      }

      Map summary = [index: index, tool: tool, ok: true]
      Map resultPayload = [:] as Map

      try {
        resultPayload = executeReadOnlyTool(ops, tool, resolvedArgs)
        summary.put('ok', true)
      } catch (Throwable tex) {
        summary.put('ok', false)
        summary.put('error', tex.message ?: tex.toString())
        log.debug('AuthoringIntentRecipeEngine step {} {} failed: {}', index, tool, tex.message)
      }

      if (Boolean.TRUE.equals(summary.get('ok'))) {
        summary.put('result', shrinkToolResultForPrefetch(resultPayload, maxField))
      }

      String asName = AuthoringIntentRecipeBindings.stepOutputName(step)
      if (asName && Boolean.TRUE.equals(summary.get('ok')) && resultPayload instanceof Map) {
        namedSoFar.put(asName, (Map) resultPayload)
        summary.put('as', asName)
      }

      stepSummaries.add(summary)
      stepResults.add(resultPayload instanceof Map ? (Map) resultPayload : [:] as Map)
      index++
    }

    Map<String, Map> initialBindings = AuthoringIntentRecipeBindings.buildInitialBindings(steps, stepResults)
    Map<String, Map> shrunkInitial = new LinkedHashMap<>()

    for (Map.Entry e : initialBindings.entrySet()) {
      shrunkInitial.put(
        e.key.toString(),
        shrinkToolResultForPrefetch(e.value instanceof Map ? (Map) e.value : [:], maxField)
      )
    }

    AuthoringIntentRecipeBindings.installTurnState(ops, initialBindings)

    Map envelope = [
      recipeId: recipe.get('id')?.toString(),
      steps   : stepSummaries,
      bindings: [initial: shrunkInitial]
    ]
    String json = JsonOutput.toJson(envelope)
    boolean truncated = false

    if (json.length() > maxTotal) {
      json = json.substring(0, Math.max(0, maxTotal - 80)) + '\n…[recipe engine prefetch truncated to engineMaxTotalChars]'
      truncated = true
    }

    String label = (blockLabel ?: 'recipe engine prefetch').toString().trim()
    String markdown = '[Studio — ' + label + ']\n\n```json\n' + json + '\n```\n\n'

    return [
      markdown                : markdown,
      prefetchSteps             : stepSummaries,
      prefetchEnvelopeTruncated : truncated,
      initialBindings           : initialBindings
    ]
  }

  /**
   * Server hotpath for a single anchored field edit: loads {@code modify_page_content}, runs {@link #runPrefetchBlock},
   * maps the author field label to an element id, and returns prefetched {@code contentXml} so the tools loop can
   * call {@code WriteContent} on round 0 without re-fetching the item.
   */
  static Map bootstrapConcreteFieldEditPrefetch(StudioToolOperations ops, Map projectCfg, String authorFieldLabelPhrase) {
    Map empty = [
      applied                     : Boolean.FALSE,
      markdown                    : '',
      prefetchSteps               : [],
      prefetchEnvelopeTruncated   : Boolean.FALSE,
      resolvedFieldId             : '',
      resolvedFieldLabel          : '',
      duplicateGetContentBanned   : Boolean.FALSE,
      contentPath                 : '',
      contentXml                  : ''
    ]

    if (ops == null || projectCfg == null) {
      return empty
    }
    if (!StudioAiAssistantProjectConfig.intentRecipeEngineEnabled(projectCfg)) {
      return empty
    }

    String label = (authorFieldLabelPhrase ?: '').toString().trim()
    if (!label) {
      return empty
    }

    Map bindings = ops.recipeEngineAuthoringBindings()
    String anchorPath = (bindings?.get('contentPath') ?: '').toString().trim()

    if (!anchorPath || !anchorPath.toLowerCase(Locale.ROOT).startsWith('/site/') ||
      !anchorPath.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      return empty
    }

    List recipes = AuthoringIntentRecipeCatalog.loadRecipes(ops, projectCfg)
    Map recipe = AuthoringIntentRecipeCatalog.findRecipeById(recipes, 'modify_page_content')
    if (recipe == null) {
      return empty
    }

    Map pfb = runPrefetchBlock(ops, recipe, projectCfg)
    String markdown = (pfb?.markdown ?: '').toString()
    Map fieldHot = buildSimpleFieldEditHotpathExtras(markdown, label)
    String fieldId = (fieldHot?.resolvedFieldId ?: '').toString().trim()

    if (!fieldId) {
      return empty
    }

    Map gc = extractPrefetchSuccessfulGetContent(markdown)
    String contentXml = (gc?.contentXml ?: '').toString()
    String path = (gc?.path ?: anchorPath).toString().trim()

    if (!contentXml?.trim()) {
      return empty
    }

    Map hotMeta = buildPrefetchHotpathDirective(ops, markdown)

    return [
      applied                     : Boolean.TRUE,
      markdown                    : markdown,
      prefetchSteps               : pfb.prefetchSteps instanceof List ? (List) pfb.prefetchSteps : [],
      prefetchEnvelopeTruncated   : Boolean.TRUE.equals(pfb.prefetchEnvelopeTruncated),
      resolvedFieldId             : fieldId,
      resolvedFieldLabel          : (fieldHot?.resolvedFieldLabel ?: label).toString(),
      duplicateGetContentBanned   : Boolean.TRUE.equals(hotMeta?.duplicateGetContentBanned),
      contentPath                 : path,
      contentXml                  : contentXml
    ]
  }

  /**
   * Walks a recipe step {@code args} template and resolves each value via {@link AuthoringIntentRecipeBindings#resolveArgValue}
   * (studio bindings, {@code $stepN.*}, named step outputs, {@code $initial.*} / {@code $current.*}).
   */
  private static Map resolveArgsMap(
    Map template,
    Map bindings,
    List<Map> priorResults,
    Map<String, Map> initialNamed,
    Map<String, Map> currentNamed
  ) {
    Map out = new LinkedHashMap<>()

    for (Map.Entry e : template.entrySet()) {
      out.put(
        e.key,
        AuthoringIntentRecipeBindings.resolveArgValue(
          e.value,
          bindings,
          priorResults,
          initialNamed ?: [:],
          currentNamed ?: [:]
        )
      )
    }

    out
  }

  /** Dispatches one allowlisted read-only core tool through {@link StudioAiToolRegistry#executeRecipePrefetchTool}. */
  private static Map executeReadOnlyTool(StudioToolOperations ops, String tool, Map input) {
    return plugins.org.craftercms.aiassistant.tools.catalog.StudioAiToolRegistry.executeRecipePrefetchTool(
      tool,
      (Map) (input ?: [:]),
      ops
    )
  }

  /**
   * Copies a tool result map for the prefetch JSON envelope: caps list sizes (e.g. {@code versions}) and
   * truncates heavy string fields ({@code contentXml}, {@code formDefinitionXml}, {@code html}) to {@code maxField}.
   */
  private static Map shrinkToolResultForPrefetch(Map raw, int maxField) {
    if (raw == null) {
      return [:]
    }

    Map copy = new LinkedHashMap<>(raw)

    for (String heavy : ['contentXml', 'formDefinitionXml', 'html', 'body', 'versions']) {
      if (!copy.containsKey(heavy)) {
        continue
      }

      Object v = copy.get(heavy)

      if (v instanceof List && 'versions'.equals(heavy)) {
        List lst = (List) v
        int cap = Math.min(lst.size(), 50)
        copy.put(heavy, lst.subList(0, cap))
        copy.put(heavy + '_truncated', lst.size() > cap)
        continue
      }

      if (!(v instanceof String)) {
        continue
      }

      String s = (String) v
      if (s.length() > maxField) {
        copy.put(heavy, s.substring(0, maxField) + '\n…[truncated]')
        copy.put(heavy + 'Chars', s.length())
      }
    }

    copy
  }

  /** True when a prefetch string field was shortened (marker suffix present). */
  private static boolean prefetchFieldLooksTruncated(String fieldValue) {
    if (fieldValue == null) {
      return false
    }

    return fieldValue.contains('…[truncated]') || fieldValue.contains('...[truncated]')
  }

  /** Extracts and parses the fenced {@code ```json} block from a recipe-engine prefetch markdown section. */
  private static Object parsePrefetchJsonEnvelope(String prefetchBlock) {
    int fence = prefetchBlock.indexOf('```json')
    if (fence < 0) {
      return null
    }

    int jsonStart = prefetchBlock.indexOf('\n', fence)
    if (jsonStart < 0) {
      return null
    }
    jsonStart++

    int jsonEnd = prefetchBlock.indexOf('\n```', jsonStart)
    if (jsonEnd <= jsonStart) {
      return null
    }

    String jsonStr = prefetchBlock.substring(jsonStart, jsonEnd).trim()
    if (!jsonStr) {
      return null
    }

    try {
      return new JsonSlurper().parseText(jsonStr)
    } catch (Throwable ignored) {
      return null
    }
  }

  /**
   * Scans prefetch step results for the first successful, non-truncated {@code GetContentTypeFormDefinition}
   * {@code formDefinitionXml}.
   */
  private static String extractPrefetchFormDefinitionXml(String prefetchBlock) {
    if (!(prefetchBlock instanceof CharSequence) || !prefetchBlock.toString().trim()) {
      return ''
    }

    Object parsed = parsePrefetchJsonEnvelope(prefetchBlock.toString())
    if (!(parsed instanceof Map)) {
      return ''
    }

    Object stepsObj = ((Map) parsed).get('steps')
    if (!(stepsObj instanceof List)) {
      return ''
    }

    for (Object stepObj : (List) stepsObj) {
      if (!(stepObj instanceof Map)) {
        continue
      }

      Map step = (Map) stepObj
      if (!'GetContentTypeFormDefinition'.equalsIgnoreCase(step.get('tool')?.toString())) {
        continue
      }
      if (!Boolean.TRUE.equals(step.get('ok'))) {
        continue
      }

      Object resObj = step.get('result')
      if (!(resObj instanceof Map)) {
        continue
      }

      Object fx = ((Map) resObj).get('formDefinitionXml')
      if (!(fx instanceof String)) {
        continue
      }

      String xml = ((String) fx).trim()
      if (!xml || prefetchFieldLooksTruncated(xml)) {
        continue
      }

      return xml
    }

    return ''
  }

  /**
   * When prefetch already returned full {@code contentXml} for the anchored path, builds a user-message directive
   * that bans duplicate {@code GetContent} on that path for the rest of the turn.
   */
  static Map buildPrefetchHotpathDirective(StudioToolOperations ops, String prefetchBlock) {
    Map out = new LinkedHashMap()
    out.put('directive', '')
    out.put('duplicateGetContentBanned', Boolean.FALSE)
    out.put('anchorPath', '')

    if (ops == null || !(prefetchBlock instanceof CharSequence) || prefetchBlock.toString().trim().isEmpty()) {
      return out
    }

    String block = prefetchBlock.toString()
    if (block.contains('…[recipe engine prefetch truncated to engineMaxTotalChars]')) {
      return out
    }

    Map bindings = ops.recipeEngineAuthoringBindings()
    String anchorPath = (bindings?.get('contentPath') ?: '').toString().trim()
    out.put('anchorPath', anchorPath)

    Object parsed = parsePrefetchJsonEnvelope(block)
    if (!(parsed instanceof Map)) {
      return out
    }

    Object stepsObj = ((Map) parsed).get('steps')
    if (!(stepsObj instanceof List)) {
      return out
    }

    for (Object stepObj : (List) stepsObj) {
      if (!(stepObj instanceof Map)) {
        continue
      }

      Map step = (Map) stepObj
      if (!'GetContent'.equalsIgnoreCase(step.get('tool')?.toString())) {
        continue
      }
      if (!Boolean.TRUE.equals(step.get('ok'))) {
        continue
      }

      Object resObj = step.get('result')
      if (!(resObj instanceof Map)) {
        continue
      }

      Map res = (Map) resObj
      Object cxObj = res.get('contentXml')
      if (!(cxObj instanceof String)) {
        continue
      }

      String cx = ((String) cxObj).trim()
      if (!cx || prefetchFieldLooksTruncated(cx)) {
        continue
      }

      String p = (res.get('path') ?: res.get('contentPath') ?: '')?.toString()?.trim() ?: ''
      String pathForMsg = anchorPath ?: p
      if (!pathForMsg) {
        continue
      }
      if (anchorPath && p && !anchorPath.equalsIgnoreCase(p)) {
        continue
      }

      out.put('duplicateGetContentBanned', Boolean.TRUE)
      out.put(
        'directive',
        '[Studio — duplicate GetContent BANNED for this turn]\n' +
          'Repository path: ' + pathForMsg + '\n' +
          'Recipe-engine prefetch already includes successful **GetContent** with non-truncated **contentXml** for this path. ' +
          'Do **not** call **GetContent** again on this path; continue with **WriteContent** or **update_content**.\n\n'
      )
      break
    }

    return out
  }

  /**
   * Maps an author field label to a form element id via prefetched form definition XML, then emits a directive
   * telling the tools loop to {@code WriteContent} only that field on round 0.
   */
  static Map buildSimpleFieldEditHotpathExtras(String prefetchBlock, String authorFieldLabelPhrase) {
    Map out = new LinkedHashMap()
    out.put('directive', '')
    out.put('resolvedFieldId', '')
    out.put('resolvedFieldLabel', '')

    String label = (authorFieldLabelPhrase ?: '').toString().trim()
    if (!label) {
      return out
    }

    String formXml = extractPrefetchFormDefinitionXml(prefetchBlock)
    if (!formXml) {
      return out
    }

    String fieldId = AiOrchestrationTools.resolveFieldIdFromFormDefinitionByAuthorLabel(formXml, label)
    if (!fieldId) {
      return out
    }

    out.put('resolvedFieldId', fieldId)
    out.put('resolvedFieldLabel', label)
    out.put(
      'directive',
      '[Studio — simple field edit]\n' +
        'Author field **"' + label + '"** maps to XML element **`' + fieldId + '`**.\n' +
        'Prefetch is on the wire: **round 0** call **WriteContent** on the anchor path — update only **`' +
        fieldId +
        '`** in **contentXml**.\n\n'
    )

    return out
  }

  /**
   * Reads the first successful, non-truncated {@code GetContent} step from a prefetch JSON envelope
   * ({@code path}, {@code contentXml}).
   */
  static Map extractPrefetchSuccessfulGetContent(String prefetchOrUserText) {
    Map out = new LinkedHashMap()
    out.put('path', '')
    out.put('contentXml', '')

    if (!(prefetchOrUserText instanceof CharSequence) || !prefetchOrUserText.toString().trim()) {
      return out
    }

    Object parsed = parsePrefetchJsonEnvelope(prefetchOrUserText.toString())
    if (!(parsed instanceof Map)) {
      return out
    }

    Object stepsObj = ((Map) parsed).get('steps')
    if (!(stepsObj instanceof List)) {
      return out
    }

    for (Object stepObj : (List) stepsObj) {
      if (!(stepObj instanceof Map)) {
        continue
      }

      Map step = (Map) stepObj
      if (!'GetContent'.equalsIgnoreCase(step.get('tool')?.toString())) {
        continue
      }
      if (!Boolean.TRUE.equals(step.get('ok'))) {
        continue
      }

      Object resObj = step.get('result')
      if (!(resObj instanceof Map)) {
        continue
      }

      Map res = (Map) resObj
      Object cxObj = res.get('contentXml')
      if (!(cxObj instanceof String)) {
        continue
      }

      String cx = ((String) cxObj).trim()
      if (!cx || prefetchFieldLooksTruncated(cx)) {
        continue
      }

      String p = (res.get('path') ?: res.get('contentPath') ?: '')?.toString()?.trim() ?: ''
      if (!p) {
        continue
      }

      out.put('path', p)
      out.put('contentXml', cx)
      break
    }

    return out
  }

  /**
   * Replaces the inner XML of {@code <fieldId>…</fieldId>} with RTE CDATA or escaped text depending on field suffix.
   */
  static String patchContentXmlFieldValue(String contentXml, String fieldId, String newPlainText) {
    if (!contentXml?.trim() || !fieldId?.trim() || newPlainText == null) {
      return ''
    }

    String tag = fieldId.trim()
    String inner = formatContentFieldInnerXml(tag, newPlainText.toString())
    Pattern pat = Pattern.compile(
      '(?s)(<' + Pattern.quote(tag) + '>)(.*?)(</' + Pattern.quote(tag) + '>)'
    )
    Matcher m = pat.matcher(contentXml)

    if (!m.find()) {
      return ''
    }

    return m.replaceFirst(
      Matcher.quoteReplacement('<' + tag + '>' + inner + '</' + tag + '>')
    )
  }

  /** Chooses CDATA-wrapped HTML for {@code *_html} fields or XML-escaped text for plain element fields. */
  private static String formatContentFieldInnerXml(String fieldId, String plain) {
    String raw = (plain ?: '').trim()
    if (!raw) {
      return ''
    }

    if (fieldId.endsWith('_html')) {
      if (raw.contains('\n')) {
        return formatRteCdataFromPlainText(raw)
      }

      return '<![CDATA[<p>' + escapeXmlElementText(raw) + '</p>]]>'
    }

    return escapeXmlElementText(raw)
  }

  /**
   * Converts author plain text into RTE CDATA: blank-line-separated blocks become {@code <p>} paragraphs;
   * single newlines inside a block become {@code <br/>}.
   */
  private static String formatRteCdataFromPlainText(String plain) {
    String[] blocks = plain.split(/\n\s*\n+/)
    StringBuilder sb = new StringBuilder('<![CDATA[')
    boolean wrote = false

    for (String block : blocks) {
      String b = (block ?: '').trim()
      if (!b) {
        continue
      }

      sb.append('<p>')
      String[] lines = b.split(/\r?\n/)
      boolean firstLine = true

      for (String lineRaw : lines) {
        String line = (lineRaw ?: '').trim()
        if (!line) {
          continue
        }

        if (!firstLine) {
          sb.append('<br/>')
        }
        sb.append(escapeXmlElementText(line))
        firstLine = false
      }

      sb.append('</p>')
      wrote = true
    }

    if (!wrote) {
      sb.append('<p>').append(escapeXmlElementText(plain.trim())).append('</p>')
    }

    sb.append(']]>')

    return sb.toString()
  }

  /** Escapes {@code &}, {@code <}, {@code >} for safe insertion inside XML element text nodes. */
  private static String escapeXmlElementText(String s) {
    if (!s) {
      return ''
    }

    return s
      .replace('&', '&amp;')
      .replace('<', '&lt;')
      .replace('>', '&gt;')
  }
}
