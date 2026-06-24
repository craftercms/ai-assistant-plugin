package plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.List
import java.util.Map

/**
 * Runs catalog-configured {@code routingEngineSteps} on the Studio JVM (same execution model as recipe
 * {@code engineSteps}) at intent-routing phases: before the first deterministic check, after refine, and
 * as context for the optional JSON router — without changing match / defer rules.
 */
final class AuthoringIntentRoutingEngine {

  private static final Logger log = LoggerFactory.getLogger(AuthoringIntentRoutingEngine)

  /** {@link #runPass} when {@code routingPass} is omitted or includes this id. */
  static final String PASS_INITIAL = 'initial'

  /** {@link #runPass} after clarify/enrich, before the second deterministic check. */
  static final String PASS_AFTER_REFINE = 'after_refine'

  /** {@link #runPass} before the optional JSON whole-turn router (when enabled). */
  static final String PASS_BEFORE_ROUTER = 'before_router'

  /** Utility class; no instances. */
  private AuthoringIntentRoutingEngine() {}

  /**
   * Executes catalog {@code routingEngineSteps} for one routing phase via {@link AuthoringIntentRecipeEngine}.
   * Does not change deterministic match counts — only supplies prefetch markdown for refine/router/plan wiring.
   *
   * @param ops Studio tool executor (may be null — returns empty)
   * @param projectCfg merged site {@code tools.json} / assistant config
   * @param detCtx routing context ({@code cand}, {@code routerVisible}, {@code ops}, evaluate* closures)
   * @param passId {@link #PASS_INITIAL}, {@link #PASS_AFTER_REFINE}, or {@link #PASS_BEFORE_ROUTER}
   * @return same shape as {@link AuthoringIntentRecipeEngine#runPrefetchBlock} (may be empty)
   */
  static Map runPass(StudioToolOperations ops, Map projectCfg, Map detCtx, String passId) {
    Map empty = [
      markdown                : '',
      prefetchSteps           : [],
      prefetchEnvelopeTruncated: false
    ]
    if (ops == null || projectCfg == null || !(detCtx instanceof Map)) {
      return empty
    }
    if (!StudioAiAssistantProjectConfig.intentRecipeRoutingEngineEnabled(projectCfg)) {
      return empty
    }
    List<Map> steps = stepsForPass(
      AuthoringIntentRecipeCatalog.loadRoutingEngineSteps(ops, projectCfg),
      (passId ?: '').toString().trim(),
      detCtx
    )
    if (steps.isEmpty()) {
      return empty
    }
    String pid = (passId ?: PASS_INITIAL).toString().trim()
    Map synthetic = new LinkedHashMap()
    synthetic.put('id', 'intent_routing_' + pid)
    synthetic.put('engineSteps', steps)
    String blockLabel = 'intent routing prefetch (' + pid + ')'
    Map pfb = AuthoringIntentRecipeEngine.runPrefetchBlock(ops, synthetic, projectCfg, blockLabel)
    if (pfb?.markdown) {
      log.info(
        'AuthoringIntentRoutingEngine: pass={} steps={} markdownChars={}',
        pid,
        steps.size(),
        pfb.markdown.toString().length()
      )
    }
    return pfb instanceof Map ? pfb : empty
  }

  /**
   * Appends this pass's prefetch markdown to {@code toolsLoopSessionBundle.routingEngineWirePrefix} and records
   * per-pass telemetry under {@code routingEngineTelemetry}.
   *
   * @param toolsLoopSessionBundle mutable session bundle for the tools-loop turn (may be null — no-op)
   * @param prefetchResult {@link #runPass} return value
   * @param passId routing phase id (telemetry map key)
   */
  static void mergePassIntoSessionBundle(Map toolsLoopSessionBundle, Map prefetchResult, String passId) {
    if (!(toolsLoopSessionBundle instanceof Map) || !(prefetchResult instanceof Map)) {
      return
    }
    String md = prefetchResult.markdown?.toString()?.trim()
    if (md) {
      String existing = toolsLoopSessionBundle.routingEngineWirePrefix?.toString() ?: ''
      toolsLoopSessionBundle.routingEngineWirePrefix = existing + md + '\n'
    }
    Map rt = toolsLoopSessionBundle.routingEngineTelemetry instanceof Map ?
      new LinkedHashMap((Map) toolsLoopSessionBundle.routingEngineTelemetry) :
      new LinkedHashMap()
    Map entry = new LinkedHashMap()
    entry.put('ran', (boolean) md)
    entry.put('prefetchSteps', prefetchResult.prefetchSteps ?: [])
    entry.put('prefetchEnvelopeTruncated', Boolean.TRUE.equals(prefetchResult.prefetchEnvelopeTruncated))
    rt.put((passId ?: PASS_INITIAL).toString(), entry)
    toolsLoopSessionBundle.routingEngineTelemetry = rt
    accumulatePrefetchToolOutputs(toolsLoopSessionBundle, prefetchResult)
  }

  private static void accumulatePrefetchToolOutputs(Map toolsLoopSessionBundle, Map prefetchResult) {
    if (!(toolsLoopSessionBundle instanceof Map) || !(prefetchResult instanceof Map)) {
      return
    }
    List<Map> acc = toolsLoopSessionBundle.routingPrefetchToolOutputs instanceof List ?
      new ArrayList<>((List) toolsLoopSessionBundle.routingPrefetchToolOutputs) :
      new ArrayList<>()
    List steps = prefetchResult.prefetchSteps instanceof List ? (List) prefetchResult.prefetchSteps : []
    for (Object o : steps) {
      if (!(o instanceof Map)) {
        continue
      }
      Map sum = (Map) o
      if (!Boolean.TRUE.equals(sum.get('ok'))) {
        continue
      }
      String tool = sum.get('tool')?.toString()?.trim()
      Object res = sum.get('result')
      if (tool && res instanceof Map && !((Map) res).isEmpty()) {
        acc.add([tool: tool, result: truncatePrefetchResult((Map) res)])
      }
    }
    if (!acc.isEmpty()) {
      toolsLoopSessionBundle.routingPrefetchToolOutputs = acc
    }
  }

  private static Map truncatePrefetchResult(Map input) {
    Map out = new LinkedHashMap()
    input.each { k, v ->
      if (v instanceof CharSequence) {
        String s = v.toString()
        out.put(k, s.length() > 8_000 ? s.substring(0, 8_000) + '…[truncated]' : s)
      } else {
        out.put(k, v)
      }
    }
    return out
  }

  /**
   * Accumulated {@code [Studio — intent routing prefetch (...)]} blocks from all routing passes this turn.
   *
   * @param toolsLoopSessionBundle session bundle (may be null)
   * @return markdown prefix or empty string
   */
  static String wirePrefixFromBundle(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return ''
    }
    return (toolsLoopSessionBundle.routingEngineWirePrefix ?: '').toString()
  }

  /**
   * Filters merged catalog steps by {@code routingPass} and optional {@code when} against {@code detCtx}.
   */
  private static List<Map> stepsForPass(List<Map> allSteps, String passId, Map detCtx) {
    List<Map> out = []
    if (!allSteps) {
      return out
    }
    String pid = (passId ?: PASS_INITIAL).toString().trim()
    for (Object o : allSteps) {
      if (!(o instanceof Map)) {
        continue
      }
      Map src = (Map) o
      if (!stepAppliesToPass(src, pid)) {
        continue
      }
      Object when = src.get('when')
      if (when != null && !AuthoringIntentRecipeWhen.evaluate(when, [:], detCtx)) {
        continue
      }
      Map execStep = new LinkedHashMap<>(src)
      execStep.remove('routingPass')
      out.add(execStep)
    }
    out
  }

  /**
   * When {@code routingPass} is omitted on a step, the step runs on every pass; otherwise must match {@code passId}
   * (hyphen/underscore normalized).
   */
  private static boolean stepAppliesToPass(Map step, String passId) {
    Object rp = step.get('routingPass')
    if (rp == null) {
      return true
    }
    if (rp instanceof String) {
      return passId.equalsIgnoreCase(rp.toString().trim()) ||
        passId.equalsIgnoreCase(rp.toString().trim().replace('-', '_'))
    }
    if (rp instanceof List) {
      for (Object o : (List) rp) {
        String s = o?.toString()?.trim()
        if (!s) {
          continue
        }
        if (passId.equalsIgnoreCase(s) || passId.equalsIgnoreCase(s.replace('-', '_'))) {
          return true
        }
      }
      return false
    }
    return true
  }
}
