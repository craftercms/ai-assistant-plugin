package plugins.org.craftercms.aiassistant.recipes

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsContentExists
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsGetContent
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsGetContentTypeFormDefinition
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsListPagesAndComponents
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsListStudioContentTypes
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsRepositorySupport
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsResearchSiteContent
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsWriteContent

import java.util.ArrayList
import java.util.Calendar
import java.util.LinkedHashMap
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Deterministic recipe-engine steps on the Studio JVM (same request as intent routing).
 * <ul>
 *   <li><strong>Prefetch</strong> — read-only {@code engineSteps} from {@code phases.context} and
 *   {@code phases.action}; results are injected into the tools-loop user message.</li>
 *   <li><strong>Confirmation</strong> — mutating or outbound tools from {@code phases.confirmation}
 *   {@code engineSteps} run after Action-phase chat work via
 *   {@link #runConfirmationStepsBlock}; see {@link AuthoringIntentRecipePlanCompiler}.</li>
 * </ul>
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
    return runPrefetchBlock(ops, recipe, projectCfg, null, null)
  }

  /**
   * Same as {@link #runPrefetchBlock(StudioToolOperations, Map, Map)} with a custom markdown header label
   * (e.g. {@code intent routing prefetch (initial)} for {@link AuthoringIntentRoutingEngine}).
   *
   * @param blockLabel when set, replaces the default {@code recipe engine prefetch} marker in the markdown header
   */
  static Map runPrefetchBlock(StudioToolOperations ops, Map recipe, Map projectCfg, String blockLabel) {
    return runPrefetchBlock(ops, recipe, projectCfg, blockLabel, null)
  }

  /**
   * @param prefetchProgressListener optional {@code (toolName, phase, input, err, result, durationMs) -> void} for chat SSE
   */
  static Map runPrefetchBlock(
    StudioToolOperations ops,
    Map recipe,
    Map projectCfg,
    String blockLabel,
    Closure prefetchProgressListener
  ) {
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

    List<Map> steps = AuthoringIntentRecipeCatalog.collectPrefetchEngineSteps(recipe)
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
      Map progressInput = recipePrefetchToolProgressInput(tool, resolvedArgs)
      long stepT0 = System.currentTimeMillis()
      emitConfirmationProgress(prefetchProgressListener, tool, 'start', progressInput, null, null, null)

      try {
        resultPayload = executeReadOnlyTool(ops, tool, resolvedArgs)
        summary.put('ok', true)
      } catch (Throwable tex) {
        summary.put('ok', false)
        summary.put('error', tex.message ?: tex.toString())
        log.debug('AuthoringIntentRecipeEngine step {} {} failed: {}', index, tool, tex.message)
        emitConfirmationProgress(
          prefetchProgressListener,
          tool,
          'error',
          progressInput,
          tex,
          null,
          System.currentTimeMillis() - stepT0
        )
      }

      if (Boolean.TRUE.equals(summary.get('ok'))) {
        emitConfirmationProgress(
          prefetchProgressListener,
          tool,
          'done',
          progressInput,
          null,
          resultPayload,
          System.currentTimeMillis() - stepT0
        )
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

  /**
   * Runs {@code phases.confirmation} {@code engineSteps} on the JVM after Action-phase chat work.
   *
   * @return map keys: {@code markdown}, {@code steps}, {@code ok}
   */
  static Map runConfirmationStepsBlock(StudioToolOperations ops, Map recipe, Map projectCfg) {
    return runConfirmationStepsBlock(ops, recipe, projectCfg, null, null, null)
  }

  /**
   * @param lastAssistantMarkdown optional Action-phase assistant prose after the tools loop; passed to each
   *   confirmation tool via {@link plugins.org.craftercms.aiassistant.tools.catalog.StudioAiToolRegistry#mergeRecipeConfirmationArgs}
   *   so tools may fill empty {@code engineSteps} {@code args} (tool-specific; default is no merge).
   * @param confirmationProgressListener optional {@code (toolName, phase, input, err, result, durationMs) -> void}
   *   for chat SSE (same contract as orchestration {@code writeToolProgressSse}).
   */
  static Map runConfirmationStepsBlock(
    StudioToolOperations ops,
    Map recipe,
    Map projectCfg,
    String lastAssistantMarkdown,
    Map confirmationLlmContext = null,
    Closure confirmationProgressListener = null
  ) {
    Map empty = [markdown: '', steps: [], ok: true]
    if (ops == null || recipe == null || projectCfg == null) {
      return empty
    }
    if (!StudioAiAssistantProjectConfig.intentRecipeEngineEnabled(projectCfg)) {
      return empty
    }
    List<Map> steps = AuthoringIntentRecipeCatalog.collectConfirmationEngineSteps(recipe)
    if (steps.isEmpty()) {
      return empty
    }
    int maxSteps = StudioAiAssistantProjectConfig.intentRecipeEngineMaxSteps(projectCfg)
    if (steps.size() > maxSteps) {
      steps = new ArrayList<>(steps.subList(0, maxSteps))
    }
    Set<String> confirmationWires =
      plugins.org.craftercms.aiassistant.tools.catalog.StudioAiToolRegistry.recipeEngineConfirmationWireNames()
    Map bindings = ops.recipeEngineAuthoringBindings()
    List<Map> stepSummaries = new ArrayList<>()
    List<Map> stepResults = new ArrayList<>()
    Map<String, Map> namedSoFar = new LinkedHashMap<>()
    Map<String, Map> initialNamed =
      AuthoringIntentRecipeBindings.deepCopyBindingMap(
        AuthoringIntentRecipeBindings.initialBindingsFromRequest(ops)
      )
    Map<String, Map> currentNamed =
      AuthoringIntentRecipeBindings.deepCopyBindingMap(
        AuthoringIntentRecipeBindings.currentBindingsFromRequest(ops)
      )
    boolean allOk = true
    int index = 0
    String sourceMarkdown = (lastAssistantMarkdown ?: '').toString().trim()
    Map<String, String> confirmationPayload = [:]
    String authorPreviewMarkdown = ''
    for (Object stepObj : steps) {
      if (!(stepObj instanceof Map)) {
        index++
        continue
      }
      Map step = (Map) stepObj
      String llmRefineProfile = step.get('llmRefine')?.toString()?.trim()
      if (llmRefineProfile) {
        String refineToolLabel = llmRefineProfile ? "LLM refine (${llmRefineProfile})" : 'LLM refine'
        long refineStepStartMs = System.currentTimeMillis()
        emitConfirmationProgress(confirmationProgressListener, refineToolLabel, 'start', [:], null, null, null)
        Map refineSummary = [index: index, step: 'llmRefine', profile: llmRefineProfile, ok: true]
        Map refineStepResult = [:] as Map
        if (StudioAiAssistantProjectConfig.intentRecipeConfirmationLlmRefineEnabled(projectCfg)) {
          String refineSource = (authorPreviewMarkdown ?: sourceMarkdown)
          Map namedBindings = new LinkedHashMap()
          if (initialNamed instanceof Map) {
            namedBindings.putAll(initialNamed)
          }
          if (namedSoFar instanceof Map) {
            namedBindings.putAll(namedSoFar)
          }
          Map mergedCurrentNamed = new LinkedHashMap(currentNamed instanceof Map ? currentNamed : [:])
          if (namedSoFar instanceof Map) {
            mergedCurrentNamed.putAll(namedSoFar)
          }
          Map refineStep = AuthoringIntentRecipeBindings.resolveLlmRefineStepBindingRefs(
            step,
            bindings,
            stepResults,
            namedBindings,
            mergedCurrentNamed
          )
          Map refineOut = AuthoringIntentRecipeLlmRefiner.refine(
            refineSource,
            refineStep,
            projectCfg,
            confirmationLlmContext instanceof Map ? (Map) confirmationLlmContext : [:]
          )
          refineSummary.put('ok', Boolean.TRUE.equals(refineOut?.ok))
          refineSummary.put('skipped', Boolean.TRUE.equals(refineOut?.skipped))
          if (refineOut?.message) {
            refineSummary.put('message', refineOut.message.toString())
          }
          if (refineOut?.outputFormat) {
            refineSummary.put('outputFormat', refineOut.outputFormat.toString())
          }
          if (refineOut?.payload instanceof Map && !((Map) refineOut.payload).isEmpty()) {
            confirmationPayload = new LinkedHashMap<>((Map) refineOut.payload)
            refineStepResult.putAll(confirmationPayload)
            refineSummary.put('outputKeys', new ArrayList<>(confirmationPayload.keySet()))
          }
          if (refineOut?.refinedMarkdown) {
            authorPreviewMarkdown = refineOut.refinedMarkdown.toString().trim()
            refineStepResult.put('refinedMarkdown', authorPreviewMarkdown)
          }
          if (!Boolean.TRUE.equals(refineSummary.get('ok'))) {
            allOk = false
            log.error(
              'AuthoringIntentRecipeEngine: confirmation llmRefine failed profile={} message={}',
              llmRefineProfile,
              refineOut?.message ?: '(none)'
            )
          }
        } else {
          refineSummary.put('skipped', true)
          refineSummary.put('message', 'confirmationLlmRefineEnabled=false')
        }
        String refineAs = AuthoringIntentRecipeBindings.stepOutputName(step)
        if (refineAs && refineStepResult instanceof Map && !refineStepResult.isEmpty()) {
          Map refineBinding = new LinkedHashMap<>(refineStepResult)
          namedSoFar.put(refineAs, refineBinding)
          if (currentNamed instanceof Map) {
            currentNamed.put(refineAs, new LinkedHashMap<>(refineBinding))
          }
          refineSummary.put('as', refineAs)
        }
        emitConfirmationProgress(
          confirmationProgressListener,
          refineToolLabel,
          Boolean.TRUE.equals(refineSummary.get('ok')) ? 'done' : 'error',
          [:],
          Boolean.TRUE.equals(refineSummary.get('ok')) ? null : new IllegalStateException(refineSummary.get('message')?.toString() ?: 'llmRefine failed'),
          refineSummary,
          System.currentTimeMillis() - refineStepStartMs
        )
        stepSummaries.add(refineSummary)
        stepResults.add(refineStepResult)
        index++
        continue
      }
      String tool = step.get('tool')?.toString()?.trim()
      if (!tool || !confirmationWires.contains(tool)) {
        stepSummaries.add([
          index: index,
          tool : tool ?: '(missing)',
          ok   : false,
          error: 'tool not allowlisted for recipe confirmation engine'
        ])
        stepResults.add([:] as Map)
        allOk = false
        index++
        continue
      }
      Object argsObj = step.get('args')
      Map argsTemplate = argsObj instanceof Map ? (Map) argsObj : [:]
      Map resolvedArgs
      Map namedBindings = new LinkedHashMap()
      if (initialNamed instanceof Map) {
        namedBindings.putAll(initialNamed)
      }
      if (namedSoFar instanceof Map) {
        namedBindings.putAll(namedSoFar)
      }
      Map mergedCurrentNamed = new LinkedHashMap(currentNamed instanceof Map ? currentNamed : [:])
      if (namedSoFar instanceof Map) {
        mergedCurrentNamed.putAll(namedSoFar)
      }
      try {
        resolvedArgs = resolveArgsMap(argsTemplate, bindings, stepResults, namedBindings, mergedCurrentNamed)
      } catch (Throwable te) {
        stepSummaries.add([index: index, tool: tool, ok: false, error: 'arg resolution: ' + te.message])
        stepResults.add([:] as Map)
        allOk = false
        index++
        continue
      }
      resolvedArgs = coerceConfirmationArgStrings(
        plugins.org.craftercms.aiassistant.tools.catalog.StudioAiToolRegistry.mergeRecipeConfirmationArgs(
          tool,
          resolvedArgs,
          authorPreviewMarkdown ?: sourceMarkdown
        )
      )
      Map summary = [index: index, tool: tool, ok: true]
      Map resultPayload = [:] as Map
      Map progressInput = confirmationToolProgressInput(tool, resolvedArgs)
      long toolStepStartMs = System.currentTimeMillis()
      emitConfirmationProgress(confirmationProgressListener, tool, 'start', progressInput, null, null, null)
      try {
        resultPayload = executeConfirmationTool(ops, tool, resolvedArgs)
        summary.put('ok', Boolean.TRUE.equals(resultPayload?.get('ok')))
        String toolMsg = resultPayload?.get('message')?.toString()?.trim()
        if (toolMsg) {
          summary.put('message', toolMsg)
        }
        if ('ConsultCrafterQ'.equals(tool) && resultPayload?.get('answer')?.toString()?.trim()) {
          summary.put('answer', resultPayload.get('answer')?.toString()?.trim())
          String fb = resultPayload?.get('feedbackMarkdown')?.toString()?.trim()
          if (fb) {
            summary.put('feedbackMarkdown', fb)
          }
        }
        if (!Boolean.TRUE.equals(summary.get('ok'))) {
          allOk = false
          log.error(
            'AuthoringIntentRecipeEngine: confirmation step {} {} failed: {}',
            index,
            tool,
            (summary.get('error') ?: toolMsg ?: 'ok=false')
          )
        }
      } catch (Throwable tex) {
        summary.put('ok', false)
        summary.put('error', tex.message ?: tex.toString())
        allOk = false
        log.error('AuthoringIntentRecipeEngine confirmation step {} {} failed: {}', index, tool, tex.message)
      }
      emitConfirmationProgress(
        confirmationProgressListener,
        tool,
        Boolean.TRUE.equals(summary.get('ok')) ? 'done' : 'error',
        progressInput,
        Boolean.TRUE.equals(summary.get('ok')) ? null : new IllegalStateException(summary.get('error')?.toString() ?: 'step failed'),
        resultPayload,
        System.currentTimeMillis() - toolStepStartMs
      )
      String asName = AuthoringIntentRecipeBindings.stepOutputName(step)
      if (asName && Boolean.TRUE.equals(summary.get('ok')) && resultPayload instanceof Map) {
        namedSoFar.put(asName, (Map) resultPayload)
        if (currentNamed instanceof Map) {
          currentNamed.put(asName, (Map) resultPayload)
        }
        summary.put('as', asName)
      }
      stepSummaries.add(summary)
      stepResults.add(resultPayload instanceof Map ? (Map) resultPayload : [:] as Map)
      index++
    }
    Map envelope = [
      recipeId: recipe.get('id')?.toString(),
      phase   : 'confirmation',
      steps   : stepSummaries
    ]
    String markdown =
      '[Studio — recipe confirmation steps executed]\n\n```json\n' +
        JsonOutput.prettyPrint(JsonOutput.toJson(envelope)) +
        '\n```\n\n'
    return [
      markdown                 : markdown,
      steps                    : stepSummaries,
      ok                       : allOk,
      confirmationPayload      : confirmationPayload,
      refinedAssistantMarkdown : authorPreviewMarkdown ?: sourceMarkdown
    ]
  }

  /** Ensures resolved confirmation tool args are strings (binding refs may return non-strings). */
  private static Map coerceConfirmationArgStrings(Map args) {
    Map out = args instanceof Map ? new LinkedHashMap<>(args) : [:]
    for (Map.Entry e : out.entrySet()) {
      Object v = e.value
      if (v != null && !(v instanceof String) && !(v instanceof Map) && !(v instanceof List)) {
        out.put(e.key, v.toString())
      }
    }
    return out
  }

  /**
   * Optional chat SSE hook for each confirmation {@code engineSteps} row (llmRefine or tool).
   * Listener args: {@code toolName}, {@code phase}, {@code input}, {@code err}, {@code result}, {@code durationMs}.
   */
  private static void emitConfirmationProgress(
    Closure listener,
    String toolName,
    String phase,
    Map input,
    Throwable err,
    Object result,
    Long durationMs
  ) {
    if (!(listener instanceof Closure)) {
      return
    }
    try {
      listener.call(
        (toolName ?: '').toString(),
        (phase ?: '').toString(),
        input instanceof Map ? input : [:],
        err,
        result,
        durationMs
      )
    } catch (Throwable ignored) {
    }
  }

  /** Maps resolved recipe prefetch args to tool-progress {@code input} (content type id, path, etc.). */
  static Map recipePrefetchToolProgressInput(String tool, Map resolvedArgs) {
    Map a = resolvedArgs instanceof Map ? resolvedArgs : [:]
    String tn = (tool ?: '').toString().trim()
    if ('GetContentTypeFormDefinition'.equals(tn)) {
      String contentTypeId = (a.contentTypeId ?: '').toString().trim()
      String contentPath = (a.contentPath ?: '').toString().trim()
      Map out = [:]
      if (contentTypeId) {
        out.contentTypeId = contentTypeId
        out.path = contentTypeId
      }
      if (contentPath) {
        out.contentPath = contentPath
        if (!out.path) {
          out.path = contentPath
        }
      }
      return out
    }
    String path = (a.path ?: a.contentPath ?: a.contentTypeId ?: a.url ?: a.previewUrl ?: '').toString().trim()
    return path ? [path: path] : [:]
  }

  /** Path/url fragment for confirmation tool-progress lines (channel, emoji, or text preview). */
  private static Map confirmationToolProgressInput(String tool, Map args) {
    Map a = args instanceof Map ? args : [:]
    String hint = confirmationToolProgressPathHint(tool, a)
    if ('GetContentTypeFormDefinition'.equals((tool ?: '').toString().trim())) {
      String contentTypeId = (a.contentTypeId ?: '').toString().trim()
      Map out = [:]
      if (contentTypeId) {
        out.contentTypeId = contentTypeId
        out.path = contentTypeId
      }
      if (hint) {
        out.contentPath = hint
        if (!out.path) {
          out.path = hint
        }
      }
      return out.isEmpty() ? [:] : out
    }
    return hint ? [path: hint, url: hint] : [:]
  }

  private static String confirmationToolProgressPathHint(String tool, Map args) {
    if (!(args instanceof Map) || !(tool ?: '').toString().trim()) {
      return ''
    }
    if ('GetContentTypeFormDefinition'.equals(tool)) {
      String contentTypeId = (args.contentTypeId ?: '').toString().trim()
      if (contentTypeId) {
        return contentTypeId
      }
      String contentPath = (args.contentPath ?: '').toString().trim()
      if (contentPath) {
        return contentPath
      }
    }
    if ('SlackPostMessage'.equals(tool)) {
      String ch = (args.channel ?: args.channelId ?: '').toString().trim()
      if (ch) {
        return ch.startsWith('#') ? ch : '#' + ch
      }
      String emoji = (args.iconEmoji ?: '').toString().trim()
      if (emoji) {
        return emoji
      }
    }
    String text = (args.text ?: args.message ?: args.query ?: args.url ?: args.previewUrl ?: '').toString().trim()
    if (text) {
      text = text.replaceAll(/\s+/, ' ').trim()
      return text.length() > 72 ? text.substring(0, 69) + '…' : text
    }
    return ''
  }

  /** Dispatches one allowlisted read-only core tool through {@link StudioAiToolRegistry#executeRecipePrefetchTool}. */
  private static Map executeReadOnlyTool(StudioToolOperations ops, String tool, Map input) {
    return plugins.org.craftercms.aiassistant.tools.catalog.StudioAiToolRegistry.executeRecipePrefetchTool(
      tool,
      (Map) (input ?: [:]),
      ops
    )
  }

  /** Delegates to {@link plugins.org.craftercms.aiassistant.tools.catalog.StudioAiToolRegistry#executeRecipeConfirmationTool}. */
  private static Map executeConfirmationTool(StudioToolOperations ops, String tool, Map input) {
    return plugins.org.craftercms.aiassistant.tools.catalog.StudioAiToolRegistry.executeRecipeConfirmationTool(
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
   * True when recipe-engine prefetch JSON already includes a non-truncated {@code GetContentTypeFormDefinition}.
   */
  static boolean prefetchIncludesFormDefinitions(String prefetchBlock) {
    return extractPrefetchFormDefinitionXml((prefetchBlock ?: '').toString()).trim().length() > 0
  }

  /**
   * Scans prefetch step results for the first successful, non-truncated {@code GetContentTypeFormDefinition}
   * {@code formDefinitionXml}.
   */
  static String extractPrefetchFormDefinitionXml(String prefetchBlock) {
    if (!(prefetchBlock instanceof CharSequence) || !prefetchBlock.toString().trim()) {
      return ''
    }

    String text = prefetchBlock.toString()
    int searchFrom = 0
    while (true) {
      int fence = text.indexOf('```json', searchFrom)
      if (fence < 0) {
        break
      }
      int jsonStart = text.indexOf('\n', fence)
      if (jsonStart < 0) {
        break
      }
      jsonStart++
      int jsonEnd = text.indexOf('\n```', jsonStart)
      if (jsonEnd <= jsonStart) {
        break
      }
      String jsonStr = text.substring(jsonStart, jsonEnd).trim()
      searchFrom = jsonEnd + 4
      if (!jsonStr) {
        continue
      }
      Object parsed
      try {
        parsed = new JsonSlurper().parseText(jsonStr)
      } catch (Throwable ignored) {
        continue
      }
      String fromBindings = formDefinitionXmlFromPrefetchBindings(parsed)
      if (fromBindings) {
        return fromBindings
      }
      String fromSteps = formDefinitionXmlFromPrefetchSteps(parsed)
      if (fromSteps) {
        return fromSteps
      }
    }
    return ''
  }

  private static String formDefinitionXmlFromPrefetchBindings(Object parsed) {
    if (!(parsed instanceof Map)) {
      return ''
    }
    Object bindingsObj = ((Map) parsed).get('bindings')
    if (!(bindingsObj instanceof Map)) {
      return ''
    }
    Object initialObj = ((Map) bindingsObj).get('initial')
    if (!(initialObj instanceof Map)) {
      return ''
    }
    for (String prefer : ['postForm', 'parentForm', 'form', 'contentTypeForm']) {
      Object bindingObj = ((Map) initialObj).get(prefer)
      String xml = formDefinitionXmlFromToolResult(bindingObj)
      if (xml) {
        return xml
      }
    }
    for (Object bindingObj : ((Map) initialObj).values()) {
      String xml = formDefinitionXmlFromToolResult(bindingObj)
      if (xml) {
        return xml
      }
    }
    return ''
  }

  private static String formDefinitionXmlFromPrefetchSteps(Object parsed) {
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
      String xml = formDefinitionXmlFromToolResult(step.get('result'))
      if (xml) {
        return xml
      }
    }
    return ''
  }

  private static String formDefinitionXmlFromToolResult(Object resultObj) {
    if (!(resultObj instanceof Map)) {
      return ''
    }
    Object fx = ((Map) resultObj).get('formDefinitionXml')
    if (!(fx instanceof CharSequence)) {
      return ''
    }
    String xml = fx.toString().trim()
    if (!xml || prefetchFieldLooksTruncated(xml)) {
      return ''
    }
    return xml
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
   * When prefetch did not already load a sibling item via {@code GetContent}, instructs the tools loop to
   * fetch one existing item of the target type before {@code WriteContent} (avoids invented XML shapes).
   */
  static Map buildNewContentItemSiblingReadDirective(String prefetchBlock) {
    Map out = new LinkedHashMap()
    out.put('directive', '')
    out.put('siblingGetContentPresent', Boolean.FALSE)

    Map gc = extractPrefetchSuccessfulGetContent((prefetchBlock ?: '').toString())
    if ((gc?.contentXml ?: '').toString().trim()) {
      out.put('siblingGetContentPresent', Boolean.TRUE)
      return out
    }

    out.put(
      'directive',
      '[Studio — new content item: sibling XML required before WriteContent]\n' +
        'After **ListStudioContentTypes** and **GetContentTypeFormDefinition** for the resolved **contentTypeId**, ' +
        'you **must** call **GetContent** on **one existing repository item** whose `<content-type>` equals that id. ' +
        'Mirror that item\'s **full** XML document shape (root element, field element names, node-selector / inline component layout, ' +
        'date literal formats, objectId/objectGroupId style).\n' +
        '**Do not** invent field ids or generic wrappers (e.g. `<sections>`, `<title>`, `<description>`) that are not in ' +
        '**GetContentTypeFormDefinition** and the sibling XML. Map author copy from **[Prior conversation]** draft sections ' +
        '(headings configured on the matched recipe) or the current message into the **actual** fields (commonly `*_html` or embedded inline components).\n' +
        'The **first** tool round that will create the item must include this sibling **GetContent** — **do not** call **WriteContent** ' +
        'until sibling XML is in hand.\n\n'
    )
    return out
  }

  /**
   * Recipe-declared prefetch supplement (see {@code toolsLoopPrefetchSupplement} in intent-recipes.json).
   * Orchestration calls this by id only — implementations live here, not in {@code AiOrchestration}.
   */
  static Map runPrefetchSupplement(
    String supplementId,
    StudioToolOperations ops,
    Map projectCfg,
    String wirePrompt,
    Map supplementConfig = null
  ) {
    String id = (supplementId ?: '').toString().trim()
    if (!id) {
      return [:]
    }
    Map cfg = supplementConfig instanceof Map ? new LinkedHashMap<>(supplementConfig) : [:]
    attachContextFormDefinitionsForSupplement(cfg)
    switch (id) {
      case 'createFromChatDraft':
        return runCreateFromPriorDraftSupplementalPrefetch(ops, projectCfg, wirePrompt, cfg)
      default:
        log.warn('AuthoringIntentRecipeEngine: unknown toolsLoopPrefetchSupplement id={}', id)
        return [:]
    }
  }

  /**
   * When {@code phases.context.engineSteps} already ran {@code GetContentTypeFormDefinition}, pass that XML into
   * supplement config so implementations (e.g. {@code createFromChatDraft}) do not load the same form again.
   * Orchestration supplies {@code recipeContextPrefetchMarkdown}; supplement-specific logic stays in the engine.
   */
  private static void attachContextFormDefinitionsForSupplement(Map supplementConfig) {
    if (!(supplementConfig instanceof Map) || supplementConfig.isEmpty()) {
      return
    }
    if (Boolean.FALSE.equals(supplementConfig.get('reuseContextFormDefinitions'))) {
      return
    }
    String existing = (supplementConfig.get('contextFormDefinitionXml') ?: '').toString().trim()
    if (existing && !prefetchFieldLooksTruncated(existing)) {
      supplementConfig.put('contextFormDefsPrefetched', Boolean.TRUE)
      return
    }
    String contextMarkdown = (supplementConfig.get('recipeContextPrefetchMarkdown') ?: '').toString()
    String ctxFormXml = extractPrefetchFormDefinitionXml(contextMarkdown)
    if (!ctxFormXml) {
      return
    }
    supplementConfig.put('contextFormDefsPrefetched', Boolean.TRUE)
    supplementConfig.put('contextFormDefinitionXml', ctxFormXml)
  }

  /** Hotpath directive for a prefetch supplement result (paired with {@link #runPrefetchSupplement}). */
  static String buildPrefetchSupplementHotpath(String supplementId, String wirePrompt, Map supplemental) {
    String id = (supplementId ?: '').toString().trim()
    if (!id) {
      return ''
    }
    switch (id) {
      case 'createFromChatDraft':
        return buildCreateFromPriorDraftHotpath(wirePrompt, supplemental)
      default:
        return ''
    }
  }

  /**
   * JVM prefetch for {@code createFromChatDraft}: exact catalog match, form {@code quickCreatePath}, sibling
   * {@code GetContent} under that tree — site-agnostic (no hardcoded content-type paths).
   */
  static Map runCreateFromPriorDraftSupplementalPrefetch(
    StudioToolOperations ops,
    Map projectCfg,
    String wirePrompt,
    Map supplementConfig = null
  ) {
    Map empty = [
      markdown                   : '',
      prefetchSteps              : [],
      resolvedContentTypeId      : '',
      quickCreatePathTemplate    : '',
      suggestedNewItemPath       : '',
      siblingPath                : '',
      bannedAnchorPath           : '',
      siblingGetContentPresent   : Boolean.FALSE
    ]

    if (ops == null || projectCfg == null) {
      return empty
    }
    if (!StudioAiAssistantProjectConfig.intentRecipeEngineEnabled(projectCfg)) {
      return empty
    }

    String siteId = ops.resolveEffectiveSiteId(null)
    Map bindings = ops.recipeEngineAuthoringBindings()
    String anchorPath = (bindings?.get('contentPath') ?: '').toString().trim()
    empty.bannedAnchorPath = anchorPath

    String prior = AuthoringPreviewContext.extractPriorConversationBody(wirePrompt)
    String current = AuthoringPreviewContext.extractAuthorCurrentRequestVisible(wirePrompt)
    List<String> phraseCandidates = inferCreateTypePhraseCandidates(prior, current)
    String resolvedType = (supplementConfig?.resolvedContentTypeId ?: supplementConfig?.contentTypeId ?: '')
      .toString().trim()

    Map typesRes = CmsListStudioContentTypes.list(ops, siteId, false, null) as Map
    List<Map> typeRows = []
    Object ctObj = typesRes?.get('contentTypes')
    if (ctObj instanceof List) {
      for (Object row : (List) ctObj) {
        if (row instanceof Map) {
          typeRows.add((Map) row)
        }
      }
    }

    if (!resolvedType) {
      for (String phrase : phraseCandidates) {
        resolvedType = exactCatalogMatchContentTypeId(typeRows, phrase)
        if (resolvedType) {
          break
        }
      }
    }

    List<Map> stepSummaries = new ArrayList<>()
    int stepIdx = 0

    if (!resolvedType) {
      return empty
    }

    Closure prefetchProgressListener =
      supplementConfig?.recipePrefetchProgressListener instanceof Closure ?
        (Closure) supplementConfig.recipePrefetchProgressListener :
        null
    Map formProgressInp = recipePrefetchToolProgressInput(
      'GetContentTypeFormDefinition',
      [siteId: siteId, contentTypeId: resolvedType]
    )
    String formXml = ''
    boolean contextFormDefsPrefetched = Boolean.TRUE.equals(supplementConfig?.contextFormDefsPrefetched)
    String injectedFormXml = (supplementConfig?.contextFormDefinitionXml ?: '').toString().trim()
    long formT0 = System.currentTimeMillis()
    if (injectedFormXml && !prefetchFieldLooksTruncated(injectedFormXml)) {
      emitConfirmationProgress(prefetchProgressListener, 'GetContentTypeFormDefinition', 'start', formProgressInp, null, null, null)
      formXml = injectedFormXml
      Map reusedResult = [
        ok            : true,
        siteId        : siteId,
        contentTypeId : resolvedType,
        note          : 'Reused from recipe context engineSteps prefetch (no duplicate load)'
      ]
      stepSummaries.add([
        index : stepIdx++,
        tool  : 'GetContentTypeFormDefinition',
        ok    : true,
        result: [
          ok                : true,
          siteId            : siteId,
          contentTypeId     : resolvedType,
          formDefinitionXml : shrinkToolResultForPrefetch([formDefinitionXml: injectedFormXml], 12000).formDefinitionXml,
          note              : reusedResult.note
        ]
      ])
      emitConfirmationProgress(
        prefetchProgressListener,
        'GetContentTypeFormDefinition',
        'done',
        formProgressInp,
        null,
        reusedResult,
        System.currentTimeMillis() - formT0
      )
    } else if (!contextFormDefsPrefetched) {
      emitConfirmationProgress(prefetchProgressListener, 'GetContentTypeFormDefinition', 'start', formProgressInp, null, null, null)
      Map formRes = [:]
      try {
        formRes = CmsGetContentTypeFormDefinition.load(ops, siteId, resolvedType) as Map
        stepSummaries.add([index: stepIdx++, tool: 'GetContentTypeFormDefinition', ok: true, result: shrinkToolResultForPrefetch(formRes, 12000)])
        formXml = (formRes?.formDefinitionXml ?: '').toString()
        emitConfirmationProgress(
          prefetchProgressListener,
          'GetContentTypeFormDefinition',
          'done',
          formProgressInp,
          null,
          formRes,
          System.currentTimeMillis() - formT0
        )
      } catch (Throwable tForm) {
        stepSummaries.add([index: stepIdx++, tool: 'GetContentTypeFormDefinition', ok: false, error: tForm.message ?: tForm.toString()])
        emitConfirmationProgress(
          prefetchProgressListener,
          'GetContentTypeFormDefinition',
          'error',
          formProgressInp,
          tForm,
          null,
          System.currentTimeMillis() - formT0
        )
        empty.prefetchSteps = stepSummaries
        empty.resolvedContentTypeId = resolvedType
        return empty
      }
    } else {
      log.warn('createFromChatDraft: contextFormDefsPrefetched=true but no contextFormDefinitionXml in supplement config')
    }
    String quickCreateTemplate = extractQuickCreatePathTemplate(formXml)
    String searchPrefix = quickCreatePathToSearchPrefix(quickCreateTemplate)
    String draftTitleFromPrior = PriorConversationDraftExtract.extractDraftTitle(prior, supplementConfig, projectCfg)
    String suggestedPath = suggestNewItemPathFromQuickCreate(quickCreateTemplate, draftTitleFromPrior)
    if (!suggestedPath) {
      suggestedPath = suggestNewItemPathFromQuickCreate(quickCreateTemplate, '')
    }
    int priorAssistantChars = PriorConversationDraftExtract.lastAssistantBlockText(prior).length()

    Boolean suggestedPathExists = null
    if (suggestedPath) {
      try {
        Map existsRes = CmsContentExists.probe(ops, siteId, suggestedPath, null) as Map
        suggestedPathExists = Boolean.TRUE.equals(existsRes?.get('exists'))
        stepSummaries.add([
          index : stepIdx++,
          tool  : 'ContentExists',
          ok    : Boolean.TRUE.equals(existsRes?.get('ok')),
          result: existsRes ?: [:]
        ])
        if (Boolean.TRUE.equals(suggestedPathExists)) {
          String available = CmsRepositorySupport.resolveFirstAvailableRepositoryPath(
            { String p -> CmsContentExists.probe(ops, siteId, p, null) as Map },
            suggestedPath,
            12
          )
          if (available && !available.equalsIgnoreCase(suggestedPath)) {
            suggestedPath = available
            suggestedPathExists = false
            stepSummaries.add([
              index : stepIdx++,
              tool  : 'ContentExists',
              ok    : true,
              result: [
                ok      : true,
                path    : available,
                exists  : false,
                note    : 'Alternate slug — original suggested path already exists in git'
              ]
            ])
          }
        }
      } catch (Throwable tExists) {
        stepSummaries.add([
          index : stepIdx++,
          tool  : 'ContentExists',
          ok    : false,
          error : tExists.message ?: tExists.toString(),
          path  : suggestedPath
        ])
      }
    }

    String siblingPath = ''
    String researchQuery = catalogTypeTailForResearch(resolvedType)
    Map researchRes = CmsResearchSiteContent.research(ops, siteId, researchQuery, 12, 3, searchPrefix) as Map
    stepSummaries.add([
      index : stepIdx++,
      tool  : 'ResearchSiteContent',
      ok    : Boolean.TRUE.equals(researchRes?.get('ok')),
      result: shrinkToolResultForPrefetch(researchRes ?: [:], 12000)
    ])

    Object hitsObj = researchRes?.get('hits')
    if (hitsObj instanceof List) {
      for (Object hitObj : (List) hitsObj) {
        if (!(hitObj instanceof Map)) {
          continue
        }
        Map hit = (Map) hitObj
        String path = (hit.get('path') ?: '').toString().trim()
        String ctype = (hit.get('contentType') ?: '').toString().trim()
        if (!path || !path.toLowerCase(Locale.ROOT).endsWith('.xml')) {
          continue
        }
        if (anchorPath && anchorPath.equalsIgnoreCase(path)) {
          continue
        }
        if (resolvedType.equalsIgnoreCase(ctype)) {
          siblingPath = path
          break
        }
      }
    }

    if (!siblingPath) {
      siblingPath = resolveSiblingPathFromCatalogList(ops, siteId, resolvedType, searchPrefix, anchorPath)
      if (siblingPath) {
        stepSummaries.add([
          index : stepIdx++,
          tool  : 'ListPagesAndComponents',
          ok    : true,
          result: [ok: true, note: 'Sibling resolved from catalog list fallback', siblingPath: siblingPath]
        ])
      }
    }

    boolean siblingGetContentPresent = false
    if (siblingPath) {
      try {
        Map siblingRes = CmsGetContent.read(ops, siteId, siblingPath) as Map
        String sibXml = (siblingRes?.contentXml ?: '').toString().trim()
        siblingGetContentPresent = sibXml.length() > 0 && !prefetchFieldLooksTruncated(sibXml)
        stepSummaries.add([
          index : stepIdx++,
          tool  : 'GetContent',
          ok    : siblingGetContentPresent,
          result: shrinkToolResultForPrefetch(siblingRes ?: [:], 12000)
        ])
      } catch (Throwable tSib) {
        stepSummaries.add([
          index : stepIdx++,
          tool  : 'GetContent',
          ok    : false,
          error : tSib.message ?: tSib.toString(),
          path  : siblingPath
        ])
      }
    }

    String priorAuthorLabel = PriorConversationDraftExtract.extractAuthorVoiceLabel(prior, supplementConfig, projectCfg)
    List<Map> nodeSelectorCandidates = collectNodeSelectorCandidatesFromPrefetch(
      ops,
      siteId,
      supplementConfig,
      priorAuthorLabel
    )
    Map priorDerivedRootFieldValues = priorDerivedRootFieldValuesFromDraft(prior, supplementConfig, projectCfg)

    boolean draftExtractReady = priorAssistantChars >= 200
    if (!draftExtractReady && suggestedPath && resolvedType) {
      log.warn(
        'createFromChatDraft: prior assistant reply under 200 chars (LLM reads [Prior conversation]) suggestedPath={} priorAssistantChars={} priorBlockChars={}',
        suggestedPath,
        priorAssistantChars,
        prior?.length() ?: 0
      )
    }

    boolean toolsLoopFastPath =
      draftExtractReady &&
        Boolean.TRUE.equals(supplementConfig?.toolsLoopFastPath)

    Map envelope = [
      flow                  : 'create_from_prior_chat_draft',
      resolvedContentTypeId : resolvedType,
      quickCreatePath       : quickCreateTemplate,
      suggestedNewItemPath  : suggestedPath,
      suggestedPathExists   : suggestedPathExists,
      draftTitleFromPrior   : draftTitleFromPrior,
      priorAuthorLabelFromPrior    : priorAuthorLabel,
      nodeSelectorCandidates       : nodeSelectorCandidates,
      priorDerivedRootFieldValues  : priorDerivedRootFieldValues,
      toolsLoopFastPath            : toolsLoopFastPath,
      siblingPath           : siblingPath,
      bannedAnchorPath      : anchorPath,
      draftExtractReady     : draftExtractReady,
      priorAssistantChars   : priorAssistantChars,
      note                  : 'Title, body, slug from [Prior conversation]; tools loop is ContentExists then WriteContent when toolsLoopFastPath. Use nodeSelectorCandidates paths only — do not invent repository paths.',
      steps                 : stepSummaries
    ]
    String json = JsonOutput.toJson(envelope)
    int maxTotal = StudioAiAssistantProjectConfig.intentRecipeEngineMaxTotalChars(projectCfg)
    if (json.length() > maxTotal) {
      json = json.substring(0, Math.max(0, maxTotal - 80)) + '\n…[create-from-draft prefetch truncated]'
    }

    return [
      markdown                 : '[Studio — create from prior chat draft (server prefetch)]\n\n```json\n' + json + '\n```\n\n',
      prefetchSteps            : stepSummaries,
      resolvedContentTypeId    : resolvedType,
      quickCreatePathTemplate    : quickCreateTemplate,
      suggestedNewItemPath       : suggestedPath,
      siblingPath                : siblingPath,
      bannedAnchorPath           : anchorPath,
      siblingGetContentPresent   : siblingGetContentPresent,
      draftExtractReady          : draftExtractReady,
      priorAssistantChars        : priorAssistantChars,
      suggestedPathExists        : suggestedPathExists,
      draftTitleFromPrior        : draftTitleFromPrior,
      priorAuthorLabelFromPrior    : priorAuthorLabel,
      nodeSelectorCandidates       : nodeSelectorCandidates,
      priorDerivedRootFieldValues  : priorDerivedRootFieldValues,
      toolsLoopFastPath            : toolsLoopFastPath,
      prefetchSupplementConfig   : supplementConfig instanceof Map ? supplementConfig : [:]
    ]
  }

  /**
   * Node-selector reference paths from recipe {@code prefetchSupplementConfig}:
   * {@code nodeSelectorCandidateBindings} (engine-step {@code as} names) plus optional
   * {@code nodeSelectorResearchFallback} (pathPrefix, contentTypeId, queries).
   */
  private static List<Map> collectNodeSelectorCandidatesFromPrefetch(
    StudioToolOperations ops,
    String siteId,
    Map supplementConfig,
    String priorAuthorLabel
  ) {
    LinkedHashMap<String, Map> byPath = new LinkedHashMap<>()
    Closure addRow = { Map row ->
      if (!(row instanceof Map)) {
        return
      }
      String path = (row.path ?: '').toString().trim()
      if (!path || !path.toLowerCase(Locale.ROOT).endsWith('.xml')) {
        return
      }
      if (!byPath.containsKey(path)) {
        byPath.put(path, row)
      }
    }

    Map catalogFilter = nodeSelectorCatalogFilterFromConfig(supplementConfig)
    Map initialBindings = prefetchInitialBindingsFromSupplement(supplementConfig)
    for (String bindingName : nodeSelectorCandidateBindingNames(supplementConfig)) {
      Object binding = initialBindings?.get(bindingName)
      compactResearchHits(binding).each { Map row -> addRow(row) }
      compactCatalogItems(binding, catalogFilter).each { Map row -> addRow(row) }
    }

    if (byPath.isEmpty() && ops != null && (siteId ?: '').trim()) {
      Map fallback = nodeSelectorResearchFallbackFromConfig(supplementConfig)
      String prefix = (fallback.pathPrefix ?: '').toString().trim()
      String ctype = (fallback.contentTypeId ?: '').toString().trim()
      for (String q : nodeSelectorResearchQueries(fallback, priorAuthorLabel)) {
        if (!byPath.isEmpty()) {
          break
        }
        if (!q) {
          continue
        }
        Map res = CmsResearchSiteContent.research(ops, siteId, q, 30, 20, prefix) as Map
        compactResearchHits(res).each { Map row -> addRow(row) }
      }
      if (byPath.isEmpty() && prefix && ctype) {
        Map listRes = CmsListPagesAndComponents.list(ops, siteId, 500) as Map
        compactCatalogItems(listRes, catalogFilter).each { Map row -> addRow(row) }
      }
    }

    if (byPath.isEmpty()) {
      log.warn('createFromChatDraft: nodeSelectorCandidates empty — site recipe should set engineSteps + nodeSelectorResearchFallback')
    }
    return new ArrayList<>(byPath.values())
  }

  private static Map priorDerivedRootFieldValuesFromDraft(String prior, Map supplementConfig, Map projectCfg) {
    Map out = new LinkedHashMap<>()
    Object specs = supplementConfig?.priorDerivedRootFieldsFromDraft
    if (!(specs instanceof List)) {
      return out
    }
    for (Object specObj : (List) specs) {
      if (!(specObj instanceof Map)) {
        continue
      }
      Map spec = (Map) specObj
      String fieldId = (spec.fieldId ?: '').toString().trim()
      if (!fieldId) {
        continue
      }
      int maxLen = spec.maxLength instanceof Number ? ((Number) spec.maxLength).intValue() : 250
      String snippet = PriorConversationDraftExtract.extractDraftFirstSentence(prior, supplementConfig, projectCfg, maxLen)
      if (snippet) {
        out.put(fieldId, snippet)
      }
    }
    return out
  }

  private static List<String> nodeSelectorCandidateBindingNames(Map supplementConfig) {
    List<String> out = []
    Object raw = supplementConfig?.nodeSelectorCandidateBindings
    if (!(raw instanceof List)) {
      return out
    }
    for (Object o : (List) raw) {
      String name = (o ?: '').toString().trim()
      if (name) {
        out.add(name)
      }
    }
    return out
  }

  private static Map nodeSelectorCatalogFilterFromConfig(Map supplementConfig) {
    Object raw = supplementConfig?.nodeSelectorCatalogFilter
    return raw instanceof Map ? new LinkedHashMap<>((Map) raw) : [:]
  }

  private static Map nodeSelectorResearchFallbackFromConfig(Map supplementConfig) {
    Object raw = supplementConfig?.nodeSelectorResearchFallback
    Map out = raw instanceof Map ? new LinkedHashMap<>((Map) raw) : [:]
    Map catalog = nodeSelectorCatalogFilterFromConfig(supplementConfig)
    if (!out.pathPrefix && catalog.pathPrefix) {
      out.pathPrefix = catalog.pathPrefix
    }
    if (!out.contentTypeId && catalog.contentTypeId) {
      out.contentTypeId = catalog.contentTypeId
    }
    return out
  }

  private static List<String> nodeSelectorResearchQueries(Map fallback, String priorAuthorLabel) {
    LinkedHashSet<String> queries = new LinkedHashSet<>()
    String label = (priorAuthorLabel ?: '').toString().trim()
    if (label && !Boolean.FALSE.equals(fallback.includePriorAuthorLabelQuery)) {
      queries.add(label)
      if (Boolean.TRUE.equals(fallback.includeFirstTokenOfPriorAuthorLabel)) {
        String firstToken = label.split(/\s+/)[0]?.trim()
        if (firstToken) {
          queries.add(firstToken)
        }
      }
    }
    Object literal = fallback.literalQueries
    if (literal instanceof List) {
      for (Object o : (List) literal) {
        String q = (o ?: '').toString().trim()
        if (q) {
          queries.add(q)
        }
      }
    }
    return new ArrayList<>(queries)
  }

  private static Map prefetchInitialBindingsFromSupplement(Map supplementConfig) {
    Object direct = supplementConfig?.prefetchInitialBindings
    if (direct instanceof Map && !((Map) direct).isEmpty()) {
      return (Map) direct
    }
    String md = (supplementConfig?.recipeContextPrefetchMarkdown ?: '').toString()
    if (!md.trim()) {
      return [:]
    }
    Object parsed = parseFirstPrefetchJsonBlock(md)
    if (!(parsed instanceof Map)) {
      return [:]
    }
    Object bindings = ((Map) parsed).get('bindings')
    if (!(bindings instanceof Map)) {
      return [:]
    }
    Object initial = ((Map) bindings).get('initial')
    return initial instanceof Map ? (Map) initial : [:]
  }

  /**
   * Rows from {@code ListPagesAndComponents} / OpenSearch — optional {@code pathPrefix} and {@code contentTypeId}
   * from site {@code prefetchSupplementConfig.nodeSelectorCatalogFilter}.
   */
  private static List<Map> compactCatalogItems(Object listResult, Map catalogFilter) {
    List<Map> out = []
    if (!(listResult instanceof Map)) {
      return out
    }
    Object items = ((Map) listResult).get('items')
    if (!(items instanceof List)) {
      return out
    }
    Map filter = catalogFilter instanceof Map ? catalogFilter : [:]
    String prefix = (filter.pathPrefix ?: '').toString().trim()
    if (prefix && !prefix.startsWith('/')) {
      prefix = '/' + prefix
    }
    String ctypeFilter = (filter.contentTypeId ?: '').toString().trim()
    for (Object rowObj : (List) items) {
      if (!(rowObj instanceof Map)) {
        continue
      }
      Map row = (Map) rowObj
      String path = (row.get('localId') ?: row.get('path') ?: '').toString().trim()
      if (!path || !path.toLowerCase(Locale.ROOT).endsWith('.xml')) {
        continue
      }
      if (prefix && !path.startsWith(prefix)) {
        continue
      }
      String ctype = (row.get('content-type') ?: row.get('contentType') ?: '').toString().trim()
      if (ctypeFilter && !ctypeFilter.equalsIgnoreCase(ctype)) {
        continue
      }
      Map entry = [path: path]
      String name =
        row.get('internal-name')?.toString()?.trim() ?:
          row.get('internalName')?.toString()?.trim() ?:
          row.get('title_t')?.toString()?.trim() ?:
          row.get('title')?.toString()?.trim() ?:
          row.get('navLabel')?.toString()?.trim() ?: ''
      if (name) {
        entry['internal-name'] = name
      }
      if (ctype) {
        entry.contentType = ctype
      }
      out.add(entry)
    }
    return out
  }

  private static Object parseFirstPrefetchJsonBlock(String prefetchBlock) {
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
    try {
      return new JsonSlurper().parseText(prefetchBlock.substring(jsonStart, jsonEnd).trim())
    } catch (Throwable ignored) {
      return null
    }
  }

  private static List<Map> compactResearchHits(Object researchResult) {
    List<Map> out = []
    if (!(researchResult instanceof Map)) {
      return out
    }
    Object hits = ((Map) researchResult).get('hits')
    if (!(hits instanceof List)) {
      return out
    }
    for (Object hitObj : (List) hits) {
      if (!(hitObj instanceof Map)) {
        continue
      }
      Map hit = (Map) hitObj
      String path = (hit.get('path') ?: '').toString().trim()
      if (!path || !path.toLowerCase(Locale.ROOT).endsWith('.xml')) {
        continue
      }
      Map row = [path: path]
      String ctype = (hit.get('contentType') ?: '').toString().trim()
      if (ctype) {
        row.contentType = ctype
      }
      String name =
        (hit.get('internal-name') ?: hit.get('internalName') ?: hit.get('title') ?: hit.get('name') ?: '')
          .toString().trim()
      if (name) {
        row['internal-name'] = name
      }
      out.add(row)
    }
    return out
  }

  static String buildCreateFromPriorDraftHotpath(String wirePrompt, Map supplemental) {
    Map sup = supplemental instanceof Map ? supplemental : [:]
    String anchor = (sup.bannedAnchorPath ?: '').toString().trim()
    String resolved = (sup.resolvedContentTypeId ?: '').toString().trim()
    String qcp = (sup.quickCreatePathTemplate ?: '').toString().trim()
    String suggested = (sup.suggestedNewItemPath ?: '').toString().trim()
    String sibling = (sup.siblingPath ?: '').toString().trim()
    boolean siblingXmlInPrefetch = Boolean.TRUE.equals(sup.siblingGetContentPresent)
    boolean fastPath = Boolean.TRUE.equals(sup.toolsLoopFastPath)
    String draftTitle = (sup.draftTitleFromPrior ?: '').toString().trim()
    Object suggestedExists = sup.suggestedPathExists

    StringBuilder sb = new StringBuilder()
    sb.append('[Studio — create repository item from **prior chat draft** (not the open preview item)]\n')
    sb.append(
      '**This draft** is the **last Assistant** reply in **[Prior conversation]** (full prose there) — ' +
        '**not** the file at **Current content item repository path** / **Request anchor** unless the author explicitly asked to edit that path.\n'
    )
    if (anchor) {
      sb.append('**Banned this turn:** **GetContent**, **WriteContent**, and **update_content** on **')
        .append(anchor)
        .append('** (preview context only — do not save the draft onto the home/listing page open in Studio).\n')
    }
    if (resolved) {
      sb.append('**Resolved contentTypeId:** `').append(resolved).append('` (exact catalog match from prior turn + current request).\n')
    }
    if (qcp) {
      sb.append('**New item folder pattern** (from form **quickCreatePath**): `').append(qcp).append('`.\n')
    }
    if (draftTitle) {
      sb.append('**Draft title (from prior assistant reply):** ').append(draftTitle).append(' — use for slug / `file-name` (kebab-case).\n')
    }
    String priorLabel = (sup.priorAuthorLabelFromPrior ?: '').toString().trim()
    if (priorLabel) {
      sb.append('**Prior author label (from prior assistant reply):** ').append(priorLabel).append('\n')
    }
    Object nodeCands = sup.nodeSelectorCandidates
    if (nodeCands instanceof List && !((List) nodeCands).isEmpty()) {
      sb.append('**Node-selector reference paths from prefetch (match prior label to **internal-name**; copy path exactly):**\n')
      for (Object o : (List) nodeCands) {
        if (!(o instanceof Map)) {
          continue
        }
        Map row = (Map) o
        String path = (row.path ?: '').toString().trim()
        if (!path) {
          continue
        }
        String label = (row['internal-name'] ?: row.displayName ?: '').toString().trim()
        sb.append('- `').append(path).append('`')
        if (label) {
          sb.append(' — **').append(label).append('**')
        }
        sb.append('\n')
      }
      sb.append('**Forbidden:** invented repository paths — only paths listed above.\n')
    } else if (priorLabel) {
      sb.append('**Node-selector catalog empty in prefetch** — do not invent paths. Stop and report missing search hits.\n')
    }
    Object derivedFields = sup.priorDerivedRootFieldValues
    if (derivedFields instanceof Map && !((Map) derivedFields).isEmpty()) {
      for (Map.Entry e : ((Map) derivedFields).entrySet()) {
        String fieldId = (e.key ?: '').toString().trim()
        String value = (e.value ?: '').toString().trim()
        if (fieldId && value) {
          sb.append('**').append(fieldId).append('** (from prior draft — copy verbatim into XML): ').append(value).append('\n')
        }
      }
    }
    if (suggested) {
      sb.append('**Suggested path (from title + quickCreatePath):** `').append(suggested).append('`')
      if (suggestedExists instanceof Boolean) {
        sb.append(Boolean.TRUE.equals(suggestedExists) ? ' — **prefetch ContentExists: exists=true** (pick another slug).' : ' — **prefetch ContentExists: exists=false** (available if you use this path).')
      }
      sb.append('\n')
    }
    if (fastPath) {
      sb.append(
        '**Fast path:** Recipe-engine prefetch already has form defs, taxonomy, reference catalog, and sibling XML shape. ' +
          '**Only** call **ContentExists** on your final new path, then **WriteContent** once with full **contentXml**. ' +
          '**Do not** call **GetContentTypeFormDefinition**, **GetContent**, **ResearchSiteContent**, or **ListStudioContentTypes** unless prefetch failed.\n'
      )
    }
    if (sibling) {
      if (siblingXmlInPrefetch) {
        sb.append('**Sibling XML (structure only, in prefetch):** `').append(sibling).append('` — mirror field ids, node-selector layout, date formats, and taxonomy element shape; **do not** copy sibling title/body text.\n')
      } else {
        sb.append('**Sibling path:** `').append(sibling).append('` — call **GetContent** on it **before WriteContent** to mirror XML **structure** (not field values).\n')
      }
    }
    sb.append(
      '**New file:** **suggestedNewItemPath** does not exist until **WriteContent** succeeds — **never** **GetContent** on that path first.\n'
    )
    if (!fastPath) {
      sb.append(
        '**Form-definition checklist (mandatory):** **GetContentTypeFormDefinition** for `').append(resolved ?: 'resolved contentTypeId').append('`; ' +
          'then **GetContentTypeFormDefinition** for **each** nested/inline **content-type** the parent form references. ' +
          'Populate **every required field** from those form defs — element ids and constraints come from the XML, not from this recipe.\n'
      )
    }
    sb.append(ToolPrompts.getLlm_CREATE_REPOSITORY_ITEM_HOTPATH_XML()).append('\n')
    if (Boolean.TRUE.equals(sup.draftExtractReady)) {
      sb.append(
        '**Copy source:** Read the last **Assistant** block in **[Prior conversation]** for title, body, and any labels the author used. Map fields using **GetContentTypeFormDefinition** + **Project authoring context** — the server does not parse or inject title/body text.\n'
      )
    } else {
      sb.append(
        '**Prior assistant reply is short or missing** — read **[Prior conversation]** yourself; if there is no article to save, ask the author to regenerate the draft before **WriteContent**.\n'
      )
    }
    sb.append(
      '**Draft copy:** Title, body, slug, and XML field values are **your** job from **[Prior conversation]** + form defs — **never** copy sibling field values.\n'
    )
    sb.append(
      '**Topics / tags (taxonomy checkbox-groups):** Prefetch includes allowed taxonomy keys (e.g. `taxonomyTopics`, `taxonomyTags` bindings). Pick keys that **reasonably match** the draft title and body — do **not** copy sibling item topics/tags verbatim. Omit a field when nothing fits; do not pad with unrelated keys.\n'
    )
    sb.append(
      '**API tool_calls required:** **WriteContent** via **tool_calls** — Studio does not save from prose-only tool lists. **Write verification** rejects incomplete or ill-formed **contentXml** (per recipe config: short body, bad UUIDs, unverified node-selector paths, etc.) — fix tool errors and retry. For **required** image-picker fields with no author art instructions, omit the field — **WriteContent** may apply the Studio sample placeholder; do **not** paste a large custom data URL. **GenerateImage** is excluded.\n'
    )
    if (!fastPath) {
      sb.append(
        '**Project context:** **GetContent** / **ResearchSiteContent** on paths **named there** for taxonomy keys and shared component refs — never invent repository paths. Field **values** from **[Prior conversation]**; **shape** from form defs + sibling/prefetch **GetContent**.\n'
      )
    } else {
      sb.append(
        '**Project context:** Field **values** from **[Prior conversation]** (verbatim). Field ids and XML **shape** from recipe-engine prefetch bindings + project authoring context — never invent repository paths.\n'
      )
      sb.append(
        '**Before WriteContent:** satisfy every required field in **Project authoring context** and recipe **writeVerification** (site config). **Write verification** rejects incomplete XML per that config — fix tool errors and retry.\n'
      )
    }
    sb.append('\n')
    return sb.toString()
  }

  /**
   * When OpenSearch research returns no hits, pick one existing item of {@code contentTypeId} under {@code pathPrefix}.
   */
  static String resolveSiblingPathFromCatalogList(
    StudioToolOperations ops,
    String siteId,
    String contentTypeId,
    String pathPrefix,
    String bannedPath
  ) {
    if (ops == null || !(contentTypeId ?: '').toString().trim()) {
      return ''
    }
    String resolvedType = contentTypeId.toString().trim()
    String prefix = (pathPrefix ?: '').toString().trim()
    if (!prefix) {
      return ''
    }
    if (!prefix.startsWith('/')) {
      prefix = '/' + prefix
    }
    String banned = (bannedPath ?: '').toString().trim()
    Map listRes = CmsListPagesAndComponents.list(ops, siteId, 500) as Map
    Object itemsObj = listRes?.get('items')
    if (!(itemsObj instanceof List)) {
      return ''
    }
    String best = ''
    for (Object rowObj : (List) itemsObj) {
      if (!(rowObj instanceof Map)) {
        continue
      }
      Map row = (Map) rowObj
      String localId = (row.get('localId') ?: row.get('path') ?: '').toString().trim()
      if (!localId || !localId.toLowerCase(Locale.ROOT).endsWith('.xml')) {
        continue
      }
      if (!localId.startsWith(prefix)) {
        continue
      }
      if (banned && banned.equalsIgnoreCase(localId)) {
        continue
      }
      String ctype = (row.get('content-type') ?: row.get('contentType') ?: '').toString().trim()
      if (!resolvedType.equalsIgnoreCase(ctype)) {
        continue
      }
      if (!best || localId.compareTo(best) > 0) {
        best = localId
      }
    }
    return best
  }

  static List<String> inferCreateTypePhraseCandidates(String priorBody, String currentRequest) {
    LinkedHashSet<String> phrases = new LinkedHashSet<>()
    String prior = (priorBody ?: '').toString()
    String current = (currentRequest ?: '').toString()

    if ((current =~ /(?i)\b(?:blog\s+)?post\b/).find()) {
      phrases.add('post')
      phrases.add('blog post')
    }
    if ((current =~ /(?i)\bblog\s+page\b/).find()) {
      phrases.add('post')
      phrases.add('blog post')
      phrases.add('blog')
    }
    if ((current =~ /(?i)\barticle\b/).find()) {
      phrases.add('article')
    }
    if ((prior =~ /(?i)\bdraft\s+(?:a\s+)?(?:\w+\s+){0,3}blog\b/).find() || (prior =~ /(?i)\bblog\b/).find()) {
      phrases.add('blog')
      phrases.add('blog post')
      phrases.add('post')
    }
    if ((prior =~ /(?i)\bpost\b/).find()) {
      phrases.add('post')
    }
    if (phrases.isEmpty()) {
      phrases.add('post')
      phrases.add('article')
      phrases.add('blog post')
    }
    return new ArrayList<>(phrases)
  }

  static String exactCatalogMatchContentTypeId(List<Map> typeRows, String authorTypePhrase) {
    String norm = normalizeCatalogMatchPhrase(authorTypePhrase)
    if (!norm || !(typeRows instanceof List)) {
      return ''
    }
    List<Map> hits = []
    for (Map row : typeRows) {
      if (!(row instanceof Map)) {
        continue
      }
      String name = (row.get('name') ?: '').toString().trim()
      String label = (row.get('label') ?: '').toString().trim()
      String tail = name.contains('/') ? name.substring(name.lastIndexOf('/') + 1) : name
      if (norm == normalizeCatalogMatchPhrase(label) ||
        norm == normalizeCatalogMatchPhrase(name) ||
        norm == normalizeCatalogMatchPhrase(tail)) {
        hits.add(row)
      }
    }
    return hits.size() == 1 ? (hits.get(0).get('name') ?: '').toString().trim() : ''
  }

  static String normalizeCatalogMatchPhrase(String phrase) {
    String s = (phrase ?: '').toString().trim().toLowerCase(Locale.ROOT)
    s = s.replace('/', ' ')
    s = s.replaceAll('[-_]', ' ')
    s = s.replaceAll('\\s+', ' ').trim()
    return s
  }

  static String extractQuickCreatePathTemplate(String formDefinitionXml) {
    if (!(formDefinitionXml instanceof CharSequence) || !formDefinitionXml.toString().trim()) {
      return ''
    }
    def m = (formDefinitionXml.toString() =~ /<quickCreatePath>\s*([^<]+?)\s*<\/quickCreatePath>/)
    return m.find() ? (m.group(1) ?: '').toString().trim() : ''
  }

  static String quickCreatePathToSearchPrefix(String quickCreateTemplate) {
    String p = (quickCreateTemplate ?: '').toString().trim()
    if (!p) {
      return ''
    }
    if (!p.startsWith('/')) {
      p = '/' + p
    }
    p = p.replaceAll(/\{[^}]+\}/, '')
    p = p.replaceAll('/+', '/')
    if (!p.endsWith('/')) {
      p = p + '/'
    }
    return p
  }

  static String catalogTypeTailForResearch(String contentTypeId) {
    String id = (contentTypeId ?: '').toString().trim()
    if (!id) {
      return 'component'
    }
    int slash = id.lastIndexOf('/')
    return slash >= 0 ? id.substring(slash + 1).replace('-', ' ') : id
  }

  static String suggestNewItemPathFromQuickCreate(String quickCreateTemplate, String draftTitle) {
    String template = (quickCreateTemplate ?: '').toString().trim()
    if (!template) {
      return ''
    }
    Calendar cal = Calendar.getInstance()
    String path = template
      .replace('{yyyy}', String.format('%04d', cal.get(Calendar.YEAR)))
      .replace('{year}', String.format('%04d', cal.get(Calendar.YEAR)))
      .replace('{mm}', String.format('%02d', cal.get(Calendar.MONTH) + 1))
      .replace('{month}', String.format('%02d', cal.get(Calendar.MONTH) + 1))
      .replace('{dd}', String.format('%02d', cal.get(Calendar.DAY_OF_MONTH)))
    String slug = slugifyForRepositoryFileName(draftTitle)
    if (!slug) {
      slug = 'draft-' + String.format(
        '%04d%02d%02d-%02d%02d',
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE)
      )
    }
    if (!path.endsWith('/')) {
      path = path + '/'
    }
    return path + slug + '.xml'
  }

  static String slugifyForRepositoryFileName(String title) {
    String s = (title ?: '').toString().trim().toLowerCase(Locale.ROOT)
    if (!s) {
      return ''
    }
    s = s.replaceAll(/[^a-z0-9]+/, '-').replaceAll(/-+/, '-').replaceAll(/^-|-$/, '')
    if (s.length() > 80) {
      s = s.substring(0, 80).replaceAll(/-+$/, '')
    }
    return s
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
