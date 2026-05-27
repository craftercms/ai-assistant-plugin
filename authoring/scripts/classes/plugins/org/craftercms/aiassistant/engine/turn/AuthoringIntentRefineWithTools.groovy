package plugins.org.craftercms.aiassistant.engine.turn

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.function.FunctionToolCallback
import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.engine.prompt.ToolPrompts

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.List
import java.util.Map
import java.util.Set

/**
 * Bounded native-tool completions for **pre-plan** intent routing refine steps (clarify/enrich, expansion,
 * JSON whole-turn router, plan-defer probe). Read/lookup/site-user tools only — no repository writes.
 */
final class AuthoringIntentRefineWithTools {

  private static final Logger log = LoggerFactory.getLogger(AuthoringIntentRefineWithTools)

  /** Utility class; no instances. */
  private AuthoringIntentRefineWithTools() {}

  /** Wired tool names allowed during routing refine (writes and heavy mutators excluded). */
  private static final Set<String> REFINE_WIRE_ALLOWLIST = Collections.unmodifiableSet(
    new LinkedHashSet<>([
      'GetContent',
      'GetContentTypeFormDefinition',
      'GetContentVersionHistory',
      'ListContentDependencyScope',
      'ListContentTranslationScope',
      'ListStudioContentTypes',
      'ListPagesAndComponents',
      'ResearchSiteContent',
      'QueryExpertGuidance',
      'GetCrafterizingPlaybook',
      'WebSearch',
      'FetchHttpUrl',
      'InvokeSiteUserTool',
      'GenerateTextNoTools'
    ] as Set)
  )

  /**
   * Tool-enabled completion when site config allows and the session bundle exposes callbacks; otherwise {@code null}
   * so callers fall back to {@link AiOrchestration#toolsLoopSimpleCompletionAssistantText}.
   *
   * @param workerPhasePrefix log/SSE phase label
   * @param toolsLoopSessionBundle must expose {@code tools} callbacks and optional telemetry keys
   * @param cfg merged project config ({@link StudioAiAssistantProjectConfig#intentRecipeRefineToolsEnabled})
   * @return assistant text, or {@code null} to signal prose-only fallback
   */
  static String completion(
    String apiKey,
    String model,
    String systemText,
    String userText,
    int maxOutTokens,
    int readTimeoutMs,
    String workerPhasePrefix,
    String wireBaseUrl,
    Map toolsLoopSessionBundle,
    Map cfg
  ) {
    if (!(cfg instanceof Map) || !StudioAiAssistantProjectConfig.intentRecipeRefineToolsEnabled(cfg)) {
      return null
    }
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return null
    }
    String key = (apiKey ?: '').toString().trim()
    String mdl = (model ?: '').toString().trim()
    if (!key || !mdl) {
      return null
    }
    List refineTools = filterRefineTools(allToolsFromBundle(toolsLoopSessionBundle))
    if (!refineTools) {
      return null
    }
    int maxRounds = StudioAiAssistantProjectConfig.intentRecipeRefineMaxToolRounds(cfg)
    if (maxRounds <= 0) {
      return null
    }
    String system = (systemText ?: '').toString() + '\n\n' + ToolPrompts.getLlm_AUTHORING_INTENT_REFINE_TOOLS_APPENDIX()
    String agentId = (toolsLoopSessionBundle?.agentId ?: '').toString()
    String phase = (workerPhasePrefix ?: 'AuthoringIntentRefine').toString()
    Map refineBundle = toolsLoopSessionBundle instanceof Map ?
      new LinkedHashMap((Map) toolsLoopSessionBundle) :
      new LinkedHashMap()
    if (maxOutTokens > 0) {
      refineBundle.intentRefineMaxOutTokens = maxOutTokens
    }
    if (readTimeoutMs > 0) {
      refineBundle.intentRefineReadTimeoutMs = readTimeoutMs
    }
    try {
      Map loopOut = AiOrchestration.runAuthoringIntentRefineNativeToolLoop(
        key,
        mdl,
        system,
        (userText ?: '').toString(),
        refineTools,
        agentId,
        maxRounds,
        wireBaseUrl,
        refineBundle,
        phase
      )
      recordRefineTelemetry(toolsLoopSessionBundle, phase, loopOut)
      String text = loopOut?.text?.toString()?.trim()
      if (!text) {
        return null
      }
      return text
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt()
      return null
    } catch (Throwable t) {
      log.warn('AuthoringIntentRefineWithTools.completion skipped phase={}: {}', phase, t.message)
      return null
    }
  }

  /**
   * Optional tool probe injected ahead of the plan-defer catalog so the main tools loop sees live facts
   * (e.g. site user tools, GetContent, WebSearch).
   *
   * @return fenced probe block for the planner user message, or empty when refine tools are off or probe fails
   */
  static String formatPlanDeferProbeBlock(
    String routerVisible,
    String catalogMarkdown,
    String apiKey,
    String model,
    String wireBaseUrl,
    Map toolsLoopSessionBundle,
    Map cfg
  ) {
    String visible = (routerVisible ?: '').toString().trim()
    String catalogMd = (catalogMarkdown ?: '').toString().trim()
    if (!visible) {
      return ''
    }
    if (!catalogMd) {
      catalogMd = '(no recipes configured)'
    }
    String userMsg =
      '## Author message (this turn)\n\n' +
        visible +
        '\n\n## Recipe catalog\n\n' +
        catalogMd
    String raw = completion(
      apiKey,
      model,
      ToolPrompts.getLlm_AUTHORING_INTENT_REFINE_PLAN_PROBE_SYSTEM(),
      userMsg,
      768,
      120_000,
      'AuthoringIntentPlanDeferProbe',
      wireBaseUrl,
      toolsLoopSessionBundle,
      cfg
    )
    if (!raw) {
      return ''
    }
    return '[Studio — refine tool probe (facts for planner)]\n' + raw + '\n\n'
  }

  /** Keeps only read/lookup callbacks on {@link #REFINE_WIRE_ALLOWLIST} (no MCP wildcard). */
  static List filterRefineTools(List tools) {
    if (!tools) {
      return []
    }
    List out = []
    tools.each { t ->
      if (!(t instanceof FunctionToolCallback)) {
        return
      }
      String n = ((FunctionToolCallback) t).getToolDefinition()?.name()
      if (!n) {
        return
      }
      if (REFINE_WIRE_ALLOWLIST.contains(n)) {
        out << t
      }
    }
    out
  }

  /** {@code tools} list from the tools-loop session bundle, or empty. */
  private static List allToolsFromBundle(Map bundle) {
    if (!(bundle instanceof Map)) {
      return []
    }
    Object t = bundle.get('tools')
    return t instanceof List ? (List) t : []
  }

  /**
   * Records per-phase refine-tool loop metadata on {@code bundle.refineToolsTelemetry} for SSE / session debug.
   */
  private static void recordRefineTelemetry(Map bundle, String phase, Map loopOut) {
    if (!(bundle instanceof Map)) {
      return
    }
    Map rt = bundle.refineToolsTelemetry instanceof Map ?
      new LinkedHashMap((Map) bundle.refineToolsTelemetry) :
      new LinkedHashMap()
    Map entry = new LinkedHashMap()
    entry.put('ran', Boolean.TRUE.equals(loopOut?.refineToolsRan))
    entry.put('maxRounds', loopOut?.maxToolRounds)
    rt.put((phase ?: 'refine').toString(), entry)
    bundle.refineToolsTelemetry = rt
  }
}
