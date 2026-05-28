package plugins.org.craftercms.aiassistant.engine.routing.subrouting

import groovy.json.JsonOutput

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.List
import java.util.Map

/**
 * Compiles a matched intent recipe into an ordered execution plan: action phase hints become
 * {@code llm} steps (model-authored **## Plan**), confirmation {@code engineSteps} become server-run
 * {@code tool} steps after Action completes.
 */
final class AuthoringIntentRecipePlanCompiler {

  static final String KIND_LLM = 'llm'
  static final String KIND_TOOL = 'tool'
  static final String KIND_LLM_REFINE = 'llmRefine'

  /**
   * Private constructor; not for direct use.
   */
private AuthoringIntentRecipePlanCompiler() {}

  /**
   * Builds the execution plan for a matched recipe: prefetch tools, action {@code llm} steps from hints,
   * and confirmation {@code tool} steps marked {@code serverExecute}.
   *
   * @param recipe catalog row (site or bundled)
   * @return map with {@code version}, {@code recipeId}, {@code steps}, {@code confirmationEngineSteps}
   */
  static Map compile(Map recipe) {
    if (!(recipe instanceof Map) || recipe.isEmpty()) {
      return emptyPlan('')
    }

    String recipeId = recipe.get('id')?.toString()?.trim() ?: ''
    List<Map> steps = new ArrayList<>()
    int id = 1

    for (Map es : AuthoringIntentRecipeCatalog.collectPrefetchEngineSteps(recipe)) {
      String tool = es.get('tool')?.toString()?.trim()
      if (!tool) {
        continue
      }
      steps.add([
        id          : id++,
        kind        : KIND_TOOL,
        phase       : 'context',
        executed    : 'prefetch',
        tool        : tool,
        summary     : 'Prefetch: ' + tool,
        serverExecute: false
      ] as Map)
    }

    List<String> actionHints = AuthoringIntentRecipeCatalog.collectPhaseHintTextsForPhase(recipe, 'action')
    for (String hint : actionHints) {
      steps.add([
        id          : id++,
        kind        : KIND_LLM,
        phase       : 'action',
        summary     : hint,
        serverExecute: false
      ] as Map)
    }

    List<Map> confirmationEngineSteps = new ArrayList<>(
      AuthoringIntentRecipeCatalog.collectConfirmationEngineSteps(recipe)
    )
    for (Map es : confirmationEngineSteps) {
      String llmRefine = es.get('llmRefine')?.toString()?.trim()
      if (llmRefine) {
        steps.add([
          id            : id++,
          kind          : KIND_LLM_REFINE,
          phase         : 'confirmation',
          llmRefine     : llmRefine,
          summary       : 'Confirmation: LLM refine (' + llmRefine + ')',
          when          : 'afterAction',
          serverExecute : true
        ] as Map)
        continue
      }
      String tool = es.get('tool')?.toString()?.trim()
      if (!tool) {
        continue
      }
      steps.add([
        id            : id++,
        kind          : KIND_TOOL,
        phase         : 'confirmation',
        tool          : tool,
        summary       : 'Confirmation: ' + tool,
        when          : 'afterAction',
        serverExecute : true
      ] as Map)
    }

    return Collections.unmodifiableMap([
      version                  : 1,
      recipeId                 : recipeId,
      steps                    : Collections.unmodifiableList(steps),
      confirmationEngineSteps  : Collections.unmodifiableList(confirmationEngineSteps)
    ] as Map)
  }

  /**
   * Wire block for matched-recipe prelude: model formulates **## Plan** / {@code CRAFTERRQ_ORCH}; Studio runs
   * {@code serverExecute} confirmation tools after Action-phase chat work.
   */
  static String formatExecutionPlanWireBlock(Map plan) {
    if (!(plan instanceof Map) || plan.isEmpty()) {
      return ''
    }

    List<Map> steps = plan.steps instanceof List ? (List<Map>) plan.steps : []
    if (steps.isEmpty()) {
      return ''
    }

    List<Map> orchSteps = new ArrayList<>()
    for (Map st : steps) {
      if (!(st instanceof Map)) {
        continue
      }
      if ('prefetch'.equals(st.get('executed')?.toString())) {
        continue
      }
      if (Boolean.TRUE.equals(st.get('serverExecute'))) {
        continue
      }
      Map row = [id: st.id, summary: (st.summary ?: '').toString().trim()]
      if (KIND_TOOL.equals(st.get('kind')) && st.get('tool')) {
        row.tools = [st.get('tool').toString().trim()]
      }
      if (row.summary || row.tools) {
        orchSteps.add(row)
      }
    }

    StringBuilder sb = new StringBuilder()
    sb.append('[Studio — recipe execution plan]\n')
    sb.append(
      'Studio compiled this plan from the matched recipe **phases**. ' +
        'Formulate **## Plan** with **📋** lines that mirror the **action** steps below (plain language). ' +
        'In the same first assistant message that issues **`tool_calls`**, append **one** ' +
        '**CRAFTERRQ_ORCH** block listing **your** tool steps in execution order (function names must match **`tool_calls`**).\n'
    )
    boolean hasServer = steps.any { Boolean.TRUE.equals(((Map) it).get('serverExecute')) }
    if (hasServer) {
      sb.append(
        'Steps marked **serverExecute** in the JSON (typically **Confirmation**) run on Studio **after** you finish Action-phase work in chat — **do not** call those tools via **`tool_calls`** unless a later Studio message says otherwise.\n'
      )
    }
    sb.append('\n```json\n')
    sb.append(JsonOutput.prettyPrint(JsonOutput.toJson([
      version : plan.version ?: 1,
      recipeId: plan.recipeId,
      steps   : steps,
      planOrchestrationHint: orchSteps
    ])))
    sb.append('\n```\n\n')
    return sb.toString()
  }

  /**
   * Whether the compiled plan includes JVM confirmation tools (telemetry and tools-loop gating).
   *
   * @param plan output of {@link #compile}
   * @return {@code true} when {@code confirmationEngineSteps} is non-empty
   */
  static boolean hasConfirmationServerSteps(Map plan) {
    if (!(plan instanceof Map)) {
      return false
    }
    List<Map> ces = plan.confirmationEngineSteps instanceof List ? (List<Map>) plan.confirmationEngineSteps : []
    return !ces.isEmpty()
  }

  /**
   * Empty plan.
   * @param recipeId Identifier for the target resource.
   * @return Map payload for tools or orchestration.
   */
  private static Map emptyPlan(String recipeId) {
    return Collections.unmodifiableMap([
      version                 : 1,
      recipeId                : recipeId ?: '',
      steps                   : Collections.emptyList(),
      confirmationEngineSteps : Collections.emptyList()
    ] as Map)
  }
}
