package plugins.org.craftercms.aiassistant.studio.engine.routing

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.engine.catalog.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.studio.engine.context.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRecipeBindings
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRecipeCatalog
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRecipeEngine
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRecipePlanCompiler
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRecipeRouter
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringDeliverablePolicy
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringTurnGoal
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentCard
import plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRoutingEngine
import plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration
import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.List
import java.util.Map

/**
 * <h2>Authoring intent routing (single entry point)</h2>
 * <p>Runs <strong>before</strong> the main tools-loop LLM on each chat turn when
 * {@code tools.json → intentRecipeRouting.enabled} is true. Orchestration calls only this class;
 * implementation details live in {@link plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting}.</p>
 *
 * <h3>Pipeline (one turn)</h3>
 * <ol>
 *   <li><strong>Eligibility</strong> — optional gate ({@link AuthoringPreviewContext#intentRecipeRouterEligibilitySkipReason}).</li>
 *   <li><strong>Prefetch engine</strong> — {@link AuthoringIntentRoutingEngine} runs catalog {@code routingEngineSteps}
 *       (read-only JVM tools) and prepends markdown to the router prompt.</li>
 *   <li><strong>LLM router</strong> — {@link #matchPass} sends recipe + tool catalogs to the classifier;
 *       {@link AuthoringIntentRecipeRouter} parses JSON {@code mode}: {@code chat_only} | {@code recipe} | {@code tool} | {@code plan},
 *       plus required {@code turnGoal} and optional {@code successCriteria}.</li>
 *   <li><strong>Turn goal</strong> — {@link AuthoringTurnGoal} resolves fallbacks and wires the goal into
 *       {@code userTextForToolsLoop}, session bundle, and SSE telemetry for every outcome branch.</li>
 *   <li><strong>Outcome</strong> — {@link #route} wires the tools loop:
 *     <ul>
 *       <li>{@code recipe} + confidence → {@link #attachMatchedRecipe} (recipe prefetch, prelude, policy on telemetry)</li>
 *       <li>{@code tool} → single-tool allowlist on telemetry</li>
 *       <li>{@code chat_only} → tools disabled for the turn</li>
 *       <li>{@code plan} → plan-defer hint + optional catalog block for the planner</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>Subrouting package</h3>
 * <ul>
 *   <li>{@link AuthoringIntentRecipeCatalog} — bundled + site {@code intent-recipes.json}, router markdown, plan defer</li>
 *   <li>{@link AuthoringIntentRecipeRouter} — parse router JSON ({@code turnGoal}, {@code successCriteria})</li>
 *   <li>{@link AuthoringTurnGoal} — resolve and propagate author turn goal through the tools loop</li>
 *   <li>{@link AuthoringIntentRoutingEngine} — catalog prefetch passes ({@code initial}, {@code before_router})</li>
 *   <li>{@link AuthoringIntentRecipeEngine} — recipe {@code engineSteps} prefetch + supplements</li>
 *   <li>{@link AuthoringIntentRecipePlanCompiler} — execution plan for confirmation steps</li>
 *   <li>{@link AuthoringIntentRecipeBindings} — binding templates for engine steps</li>
 *   <li>Other helpers: LLM refine, site tool hints, write verification, markdown sections, draft extract</li>
 * </ul>
 *
 * <p>Bundled defaults: {@code authoring-intent-recipes-default.json} in this package (parent of {@code subrouting/}).</p>
 * <p><strong>Call</strong> {@link #route} — the only method orchestration should invoke.</p>
 */
final class Router {

  private static final Logger log = LoggerFactory.getLogger(Router)

  /** Utility class; use static methods only. */
  private Router() {}

  /** {@link AuthoringIntentRoutingEngine#PASS_INITIAL} */
  static final String PASS_INITIAL = AuthoringIntentRoutingEngine.PASS_INITIAL

  /** {@link AuthoringIntentRoutingEngine#PASS_BEFORE_ROUTER} */
  static final String PASS_BEFORE_ROUTER = AuthoringIntentRoutingEngine.PASS_BEFORE_ROUTER

  /**
   * Routes one authoring chat turn <em>before</em> the main tools-loop LLM runs.
   * <p>Returns a map orchestration merges into the session: {@code userTextForToolsLoop} (often
   * prefixed with prefetch markdown / Studio hints) and {@code intentRecipeRoutingTelemetry}
   * ({@code outcome}, recipe id, confidence, tools-loop policy flags).</p>
   *
   * <p><strong>Pseudocode</strong> (read top to bottom; each {@code guard*} returns early when routing
   * cannot run):</p>
   * <pre>
   * result = empty shell(user message for tools loop)
   *
   * GUARD studio operations exist
   * GUARD intentRecipeRouting.enabled in site tools.json
   * GUARD non-empty wire prompt, API key, model, not cancelled
   *
   * catalog = load bundled + site intent-recipes.json
   * GUARD catalog has at least one recipe
   * GUARD optional eligibility gate (short / non-CMS messages may skip routing)
   *
   * author = strip Studio chrome from prompt; pick text the router LLM should classify
   * GUARD author-visible text is non-empty
   *
   * decision = LLM classifier(matchPass):
   *            optional JVM prefetch → recipe + tool catalogs → JSON { mode, recipeId, toolName, confidence }
   *
   * IF decision says recipe AND confidence high enough:
   *    prefetch recipe engineSteps, prepend recipe prelude → outcome matched
   * ELSE IF decision says chat_only:
   *    turn off tools for this turn → outcome chat_only
   * ELSE IF decision says tool:
   *    restrict tools loop to that one tool → outcome router_tool
   * ELSE:
   *    prepend plan-defer hint (or generic judgement hint) → outcome plan or no_match
   * </pre>
   *
   * @param bodyPrompt full wire prompt (Studio context + author message); used for guards and prefetch
   * @param userTextAfterGuard author text after policy guards; base for {@code userTextForToolsLoop}
   * @param llmCompleter {@code (projectCfg, systemPrompt, userMessage) -> rawJson} — orchestration supplies the LLM call
   */
  static Map route(
    String bodyPrompt,
    String userTextAfterGuard,
    String apiKey,
    String model,
    String wireBaseUrl,
    Map toolsLoopSessionBundle,
    StudioToolOperations ops,
    Closure recipePrefetchProgressListener = null,
    Closure<String> llmCompleter = null
  ) {
    // Mutable outcome passed back to AiOrchestration (tools loop reads userTextForToolsLoop + telemetry).
    Map result = emptyRouteResult(userTextAfterGuard)
    Map cfg = null
    try {
      // --- Preconditions: exit early with telemetry.outcome = skipped_* (tools loop unchanged) ---

      // IF ops == null → return skipped_ops_null
      Map skipped = guardOpsPresent(ops, result)
      if (skipped) {
        return skipped
      }

      cfg = StudioAiAssistantProjectConfig.load(ops)
      String cand = (bodyPrompt ?: '').toString()

      // IF intentRecipeRouting.enabled is false → return skipped_disabled
      skipped = guardRoutingEnabled(ops, cfg, result, cand)
      if (skipped) {
        return skipped
      }

      // IF empty prompt, API key, model, or pipeline cancelled → return skipped_*
      skipped = guardPromptAndLlmReady(ops, cfg, result, cand, apiKey, model)
      if (skipped) {
        return skipped
      }

      // --- Catalog: recipes list + routing config + baseline telemetry fields ---

      // catalog = { recipes, routingCfg, catalogTel }
      Map catalog = loadRouteCatalog(ops, cfg, toolsLoopSessionBundle)

      // IF no recipes after merge → return skipped_no_recipes (prepend empty-catalog author hint)
      skipped = guardCatalogHasRecipes(ops, cfg, result, cand, userTextAfterGuard, catalog)
      if (skipped) {
        return skipped
      }

      // IF eligibilityGateEnabled AND message fails eligibility → return skipped_eligibility
      skipped = guardEligibilityGate(ops, cfg, result, cand, catalog)
      if (skipped) {
        return skipped
      }

      // --- Author view: what the router LLM actually classifies (not Studio metadata blocks) ---

      // author = { cand, visible, routerVisible, authorFieldLabelEarly, routeCtx }
      Map author = resolveAuthorRoutingContext(cand, cfg, ops)

      // IF stripped author text is empty → return skipped_visible_empty
      skipped = guardAuthorVisible(ops, cfg, result, author)
      if (skipped) {
        return skipped
      }

      // --- Classifier: prefetch engine (optional) + LLM picks mode / recipe / tool ---

      // activePass = matchPass → { matched, routingMode, recipeId, confidence, toolsLoopAllowlist, … }
      String apiKeyTrim = (apiKey ?: '').toString().trim()
      String modelTrim = (model ?: '').toString().trim()
      Map activePass = runClassifierMatchPass(
        catalog,
        cfg,
        author,
        apiKeyTrim,
        modelTrim,
        wireBaseUrl,
        toolsLoopSessionBundle,
        llmCompleter
      )

      // --- Wire outcome into tools loop ---

      // IF mode == recipe AND confidence >= minConfidence:
      //    attachMatchedRecipe → prefetch, prelude, policy on telemetry → outcome matched
      if (Boolean.TRUE.equals(activePass.matched)) {
        return wireMatchedRecipeOutcome(
          ops,
          cfg,
          result,
          userTextAfterGuard,
          catalog,
          author,
          activePass,
          toolsLoopSessionBundle,
          recipePrefetchProgressListener
        )
      }

      // ELSE: chat_only | single tool | plan defer | generic no_match
      return wireClassifierOutcome(
        ops,
        cfg,
        result,
        userTextAfterGuard,
        catalog,
        author,
        activePass,
        toolsLoopSessionBundle,
        apiKey,
        model,
        wireBaseUrl
      )
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt()
      return attachTelemetry(ops, cfg, result, 'skipped_interrupted')
    } catch (Throwable t) {
      log.warn('Router.route skipped: {}', t.message)
      return attachTelemetry(ops, cfg, result, 'error', [errorMessage: (t.message ?: t.toString())])
    }
  }

  /** Initial tools-loop result shell before routing mutates {@code userTextForToolsLoop}. */
  private static Map emptyRouteResult(String userTextAfterGuard) {
    return [
      clarificationOnly      : false,
      userTextForToolsLoop   : (userTextAfterGuard ?: '').toString(),
      clarificationUserText: ''
    ]
  }

  /** @return telemetry result when {@code ops} is null; otherwise {@code null} to continue. */
  private static Map guardOpsPresent(StudioToolOperations ops, Map result) {
    if (ops == null) {
      return attachTelemetry(ops, null, result, 'skipped_ops_null')
    }
    return null
  }

  /** @return telemetry when intent routing is disabled in site config; otherwise {@code null}. */
  private static Map guardRoutingEnabled(
    StudioToolOperations ops,
    Map cfg,
    Map result,
    String cand
  ) {
    if (StudioAiAssistantProjectConfig.intentRecipeRoutingEnabled(cfg)) {
      return null
    }
    if (cand.trim() && AuthoringPreviewContext.intentRecipeRouterEligibilitySkipReason(cand) == null) {
      log.debug(
        'Intent recipe routing skipped: intentRecipeRouting.enabled is not true in site tools.json — enable under Project Tools → AI Assistant → Tools and MCP → Intent recipe routing.'
      )
    }
    return attachTelemetry(ops, cfg, result, 'skipped_disabled')
  }

  /** @return telemetry when prompt / API key / model / cancel guard fails; otherwise {@code null}. */
  private static Map guardPromptAndLlmReady(
    StudioToolOperations ops,
    Map cfg,
    Map result,
    String cand,
    String apiKey,
    String model
  ) {
    if (!cand.trim()) {
      return attachTelemetry(ops, cfg, result, 'skipped_empty_prompt')
    }
    if (!(apiKey ?: '').toString().trim()) {
      log.warn('Intent recipe routing skipped: empty tools-loop API key (cannot call IntentRecipeRouter).')
      return attachTelemetry(ops, cfg, result, 'skipped_no_api_key')
    }
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return attachTelemetry(ops, cfg, result, 'skipped_cancelled')
    }
    if (!(model ?: '').toString().trim()) {
      log.warn('Intent recipe routing skipped: empty resolved chat model (cannot call IntentRecipeRouter).')
      return attachTelemetry(ops, cfg, result, 'skipped_no_model')
    }
    return null
  }

  /**
   * Loads recipes, merged routing config, and catalog telemetry fields for this turn.
   * Keys: {@code recipes}, {@code routingCfg}, {@code catalogTel}.
   */
  private static Map loadRouteCatalog(StudioToolOperations ops, Map cfg, Map toolsLoopSessionBundle) {
    List recipes = AuthoringIntentRecipeCatalog.loadRecipes(ops, cfg)
    Map routingCfg = AuthoringIntentRecipeCatalog.loadMergedCatalogRoutingConfig(ops, cfg)
    if (toolsLoopSessionBundle instanceof Map) {
      toolsLoopSessionBundle.intentCatalogRoutingCfg = routingCfg
    }
    boolean eligibilityGate = StudioAiAssistantProjectConfig.intentRecipeEligibilityGateEnabled(cfg)
    Map catalogTel = [
      eligibilityGateEnabled    : eligibilityGate,
      recipeCatalogSize         : recipes != null ? recipes.size() : 0,
      catalogHasOpenPageInquiry : AuthoringIntentRecipeCatalog.findRecipeById(recipes, 'open_page_inquiry') != null
    ]
    return [
      recipes    : recipes,
      routingCfg : routingCfg,
      catalogTel : catalogTel
    ]
  }

  /** @return telemetry when the merged recipe catalog is empty; otherwise {@code null}. */
  private static Map guardCatalogHasRecipes(
    StudioToolOperations ops,
    Map cfg,
    Map result,
    String cand,
    String userTextAfterGuard,
    Map catalog
  ) {
    List recipes = catalog.recipes as List
    if (recipes != null && !recipes.isEmpty()) {
      return null
    }
    log.warn('Intent recipe routing skipped: recipe catalog is empty after bundled + site custom merge.')
    Map workSiteCtx = AuthoringPreviewContext.parseWorkingSiteFromOrchestrationWire((cand ?: '').toString())
    StringBuilder emptyCatalog = new StringBuilder()
    emptyCatalog.append(
      '[Studio — intent recipe catalog is empty (bundled JSON not loaded, site override removed all recipes, or custom JSON invalid). **tools are still available** — use normal CMS judgement **with strict content-vs-code discipline**:\n'
    )
    if (Boolean.TRUE.equals(workSiteCtx.crossSiteWorking)) {
      String workId = (workSiteCtx.workingSiteId ?: '').toString().trim()
      emptyCatalog.append(
        '- **Working CMS site** for this turn is **"' + workId + '"** (Studio session site differs). **Do not** answer from prior conversation as repository truth — call **ResearchSiteContent** or **GetContent** with **siteId="' +
          workId + '"** on that site before summarizing or describing content.\n'
      )
    }
    emptyCatalog.append(
      '- When **Current content item repository path** or **Request anchor** is **`/site/.../*.xml`** and the author asks to change **copy, field values, or tone** without naming **FTL**, **template**, or **CSS**: **GetContent** then **WriteContent** (or **update_content** then **WriteContent**) on **that same repository .xml path** — preserve **`<page>` / `<component>`** structure and existing field tag names from the file you read; map labels to element ids via **GetContentTypeFormDefinition** when needed.\n' +
      '- **Do not** call **update_template** for that scenario; **do not** **WriteContent** a **`.ftl`** path with page/component XML bodies; **do not** invent **`/static-assets/styles.css`** or other asset paths unless the author explicitly asked for stylesheet/asset work **or** **GetContent** on the item you edit already referenced that exact path and the task requires editing that file.\n' +
      '- If copy still looks wrong after XML saves, **analyze_template** / **GetContent** on **display-template** is **read-only diagnosis** — explain findings; **do not** patch FTL for a **content-only** goal.\n\n'
    )
    result.userTextForToolsLoop = emptyCatalog.toString() + (userTextAfterGuard ?: '')
    Map catalogTel = catalog.catalogTel instanceof Map ? (Map) catalog.catalogTel : [:]
    return attachTelemetry(
      ops,
      cfg,
      result,
      'skipped_no_recipes',
      [recipeCatalogEmpty: true] + catalogTel
    )
  }

  /** @return telemetry when the eligibility gate blocks routing; otherwise {@code null}. */
  private static Map guardEligibilityGate(
    StudioToolOperations ops,
    Map cfg,
    Map result,
    String cand,
    Map catalog
  ) {
    Map catalogTel = catalog.catalogTel instanceof Map ? (Map) catalog.catalogTel : [:]
    if (!Boolean.TRUE.equals(catalogTel.eligibilityGateEnabled)) {
      log.debug('Intent recipe routing: eligibility gate disabled — match pass runs for non-empty prompt')
      return null
    }
    List recipes = catalog.recipes as List
    Map routingCfg = catalog.routingCfg instanceof Map ? (Map) catalog.routingCfg : [:]
    String eligibilitySkip =
      AuthoringPreviewContext.intentRecipeRouterEligibilitySkipReason(cand, recipes, routingCfg)
    if (eligibilitySkip == null) {
      return null
    }
    log.info(
      'Intent recipe routing skipped: eligibility gate (reason={}) — routing and prefetch do not run. Disable with intentRecipeRouting.eligibilityGateEnabled false in tools.json.',
      eligibilitySkip
    )
    Map skipTel = new LinkedHashMap(catalogTel)
    skipTel.eligibilitySkipReason = eligibilitySkip
    return attachTelemetry(ops, cfg, result, 'skipped_eligibility', skipTel)
  }

  /**
   * Author-visible text and match-pass context from the wire prompt.
   * Keys: {@code cand}, {@code routerVisible}, {@code authorFieldLabelEarly}, {@code routeCtx}.
   */
  private static Map resolveAuthorRoutingContext(String cand, Map cfg, StudioToolOperations ops) {
    String visible = cand
    try {
      visible = AuthoringPreviewContext.stripStudioInjectedPromptBlocks(cand)
    } catch (Throwable ignored) {
      visible = cand
    }
    visible = (visible ?: '').trim()
    String clientAuthorBlock = AuthoringPreviewContext.extractOrchestrationClientAuthorBlock(cand)?.trim()
    String currentAuthorVisible = AuthoringPreviewContext.extractAuthorCurrentRequestVisible(cand)?.trim()
    if (!currentAuthorVisible) {
      currentAuthorVisible = visible
    }
    String routerVisible = clientAuthorBlock ?: currentAuthorVisible
    String authorFieldLabelEarly = AiOrchestration.extractAuthorFieldLabelPhraseForRouting(cand)
    if (!authorFieldLabelEarly) {
      authorFieldLabelEarly = AiOrchestration.extractAuthorFieldLabelPhraseForRouting(routerVisible)
    }
    if (!authorFieldLabelEarly) {
      authorFieldLabelEarly = AuthoringPreviewContext.extractXbFocusedFieldLabelFromWire(cand)
    }
    if (!authorFieldLabelEarly) {
      authorFieldLabelEarly = AuthoringPreviewContext.extractXbFocusedFieldIdFromWire(cand)
    }
    Map routeCtx = [
      cand         : cand,
      routerVisible: routerVisible,
      projectCfg   : cfg,
      ops          : ops
    ]
    return [
      cand                  : cand,
      visible               : visible,
      routerVisible         : routerVisible,
      authorFieldLabelEarly : authorFieldLabelEarly,
      routeCtx              : routeCtx
    ]
  }

  /** @return telemetry when author-visible text is empty after strip; otherwise {@code null}. */
  private static Map guardAuthorVisible(StudioToolOperations ops, Map cfg, Map result, Map author) {
    if ((author.visible ?: '').toString().trim()) {
      return null
    }
    log.warn('Intent recipe routing skipped: author-visible text empty after strip (unexpected after eligibility pass).')
    return attachTelemetry(ops, cfg, result, 'skipped_visible_empty')
  }

  /** Prefetch engine passes + LLM classifier ({@link #matchPass}). */
  private static Map runClassifierMatchPass(
    Map catalog,
    Map cfg,
    Map author,
    String apiKey,
    String model,
    String wireBaseUrl,
    Map toolsLoopSessionBundle,
    Closure<String> llmCompleter
  ) {
    return matchPass(
      catalog.recipes as List,
      cfg,
      author.routeCtx as Map,
      author.routerVisible?.toString(),
      apiKey,
      model,
      wireBaseUrl,
      toolsLoopSessionBundle,
      author.authorFieldLabelEarly?.toString(),
      catalog.routingCfg as Map,
      llmCompleter
    )
  }

  /** Recipe match: prefetch, prelude, telemetry {@code outcome=matched}. */
  private static Map wireMatchedRecipeOutcome(
    StudioToolOperations ops,
    Map cfg,
    Map result,
    String userTextAfterGuard,
    Map catalog,
    Map author,
    Map activePass,
    Map toolsLoopSessionBundle,
    Closure recipePrefetchProgressListener
  ) {
    double minC = minConfidenceFromPass(activePass, cfg)
    log.info(
      'Intent recipe routing matched recipeId={} mode=recipe confidence={} matchPass={}',
      activePass.recipeId,
      activePass.confidence,
      activePass.matchPass
    )
    String cand = author.cand?.toString()
    String routerVisible = author.routerVisible?.toString()
    Map matchedRoute = attachMatchedRecipe(
      ops,
      cfg,
      result,
      userTextAfterGuard,
      activePass.recipe as Map,
      activePass.recipeId?.toString()?.trim(),
      activePass.confidence instanceof Number ? ((Number) activePass.confidence).doubleValue() : 1.0d,
      minC,
      activePass.routerReason?.toString(),
      cand,
      routerVisible,
      author.authorFieldLabelEarly?.toString(),
      Boolean.TRUE.equals(activePass.skipRecipePrefetch),
      activePass.toolsLoopPrefetchSupplement?.toString(),
      activePass.toolsLoopPrefetchSupplementConfig instanceof Map ?
        (Map) activePass.toolsLoopPrefetchSupplementConfig :
        null,
      activePass.toolsLoopRequireSuccessfulTools instanceof List ?
        (List) activePass.toolsLoopRequireSuccessfulTools :
        null,
      (activePass.matchedUserPreludeOverride ?: '').toString().trim() ?:
        AuthoringIntentRecipeCatalog.matchedUserPreludeFromMatch(activePass),
      recipePrefetchProgressListener
    )
    if (matchedRoute.intentRecipeRoutingTelemetry instanceof Map) {
      Map matchedTel = (Map) matchedRoute.intentRecipeRoutingTelemetry
      Map catalogTel = catalog.catalogTel instanceof Map ? (Map) catalog.catalogTel : [:]
      matchedTel.putAll(catalogTel)
      matchedTel.routingMode = 'recipe'
      AiOrchestration.putRefineToolsTelemetryIfPresentForRouting(matchedTel, toolsLoopSessionBundle)
      putRoutingEngineTelemetryIfPresent(matchedTel, toolsLoopSessionBundle)
    }
    wireAuthorTurnGoal(matchedRoute, toolsLoopSessionBundle, author, activePass)
    mergeAuthorTurnGoalFromBundle(matchedRoute, toolsLoopSessionBundle)
    return matchedRoute
  }

  /** Dispatches {@code chat_only}, single-tool, or plan / no-match tools-loop wiring after classifier. */
  private static Map wireClassifierOutcome(
    StudioToolOperations ops,
    Map cfg,
    Map result,
    String userTextAfterGuard,
    Map catalog,
    Map author,
    Map activePass,
    Map toolsLoopSessionBundle,
    String apiKey,
    String model,
    String wireBaseUrl
  ) {
    String routingMode = activePass.routingMode?.toString()?.trim()?.toLowerCase() ?: ''
    String routingEnginePrefix = AuthoringIntentRoutingEngine.wirePrefixFromBundle(toolsLoopSessionBundle)
    double minC = minConfidenceFromPass(activePass, cfg)
    double conf = activePass.routerConfidence instanceof Number ? ((Number) activePass.routerConfidence).doubleValue() : 0.0d
    Map catalogTel = catalog.catalogTel instanceof Map ? (Map) catalog.catalogTel : [:]
    String cand = author.cand?.toString()

    if ('chat_only'.equals(routingMode) || Boolean.TRUE.equals(activePass.toolsLoopDisable)) {
      return wireChatOnlyOutcome(
        ops, cfg, result, userTextAfterGuard, cand, author, activePass, catalogTel, routingEnginePrefix, conf, minC, toolsLoopSessionBundle
      )
    }

    if ('tool'.equals(routingMode) && activePass.toolsLoopAllowlist instanceof List) {
      return wireSingleToolOutcome(
        ops, cfg, result, userTextAfterGuard, cand, author, activePass, catalogTel, routingEnginePrefix, conf, minC, toolsLoopSessionBundle
      )
    }

    return wirePlanOrNoMatchOutcome(
      ops,
      cfg,
      result,
      userTextAfterGuard,
      catalog,
      author,
      activePass,
      catalogTel,
      routingEnginePrefix,
      routingMode,
      conf,
      minC,
      toolsLoopSessionBundle,
      apiKey,
      model,
      wireBaseUrl
    )
  }

  /** Tools off for the turn; telemetry {@code outcome=chat_only}. */
  private static Map wireChatOnlyOutcome(
    StudioToolOperations ops,
    Map cfg,
    Map result,
    String userTextAfterGuard,
    String cand,
    Map author,
    Map activePass,
    Map catalogTel,
    String routingEnginePrefix,
    double conf,
    double minC,
    Map toolsLoopSessionBundle
  ) {
    log.info('Intent recipe routing: chat_only (confidence={})', conf)
    result.userTextForToolsLoop = routingEnginePrefix + (userTextAfterGuard ?: '')
    result.intentRecipeRoutingWireCand = cand
    Map chatTel = [
      routingMode      : 'chat_only',
      toolsLoopDisable : Boolean.TRUE,
      routerReason     : activePass.routerReason?.toString()?.trim() ?: '',
      routerConfidence : conf,
      minConfidence    : minC,
      matchPass          : activePass.matchPass?.toString()
    ]
    chatTel.putAll(catalogTel)
    putRoutingEngineTelemetryIfPresent(chatTel, toolsLoopSessionBundle)
    wireAuthorTurnGoal(result, toolsLoopSessionBundle, author, activePass)
    chatTel.turnGoal = activePass.turnGoal?.toString()?.trim() ?: ''
    chatTel.successCriteria = activePass.successCriteria?.toString()?.trim() ?: ''
    putDeliverableTelemetryIfPresent(chatTel, activePass)
    attachTelemetry(ops, cfg, result, 'chat_only', chatTel)
    mergeAuthorTurnGoalFromBundle(result, toolsLoopSessionBundle)
    return result
  }

  /** Single-tool allowlist on telemetry; {@code outcome=router_tool}. */
  private static Map wireSingleToolOutcome(
    StudioToolOperations ops,
    Map cfg,
    Map result,
    String userTextAfterGuard,
    String cand,
    Map author,
    Map activePass,
    Map catalogTel,
    String routingEnginePrefix,
    double conf,
    double minC,
    Map toolsLoopSessionBundle
  ) {
    log.info(
      'Intent recipe routing: single-tool mode tool={} (confidence={})',
      activePass.toolsLoopAllowlist,
      conf
    )
    result.userTextForToolsLoop = routingEnginePrefix + (userTextAfterGuard ?: '')
    result.intentRecipeRoutingWireCand = cand
    Map toolTel = [
      routingMode        : 'tool',
      toolsLoopAllowlist : activePass.toolsLoopAllowlist,
      routerReason       : activePass.routerReason?.toString()?.trim() ?: '',
      routerConfidence   : conf,
      minConfidence      : minC,
      matchPass            : activePass.matchPass?.toString()
    ]
    toolTel.putAll(catalogTel)
    putRoutingEngineTelemetryIfPresent(toolTel, toolsLoopSessionBundle)
    wireAuthorTurnGoal(result, toolsLoopSessionBundle, author, activePass)
    toolTel.turnGoal = activePass.turnGoal?.toString()?.trim() ?: ''
    toolTel.successCriteria = activePass.successCriteria?.toString()?.trim() ?: ''
    attachTelemetry(ops, cfg, result, 'router_tool', toolTel)
    mergeAuthorTurnGoalFromBundle(result, toolsLoopSessionBundle)
    return result
  }

  /** Plan defer hint + optional catalog block, or generic no-match; {@code outcome=plan} or {@code no_match}. */
  private static Map wirePlanOrNoMatchOutcome(
    StudioToolOperations ops,
    Map cfg,
    Map result,
    String userTextAfterGuard,
    Map catalog,
    Map author,
    Map activePass,
    Map catalogTel,
    String routingEnginePrefix,
    String routingMode,
    double conf,
    double minC,
    Map toolsLoopSessionBundle,
    String apiKey,
    String model,
    String wireBaseUrl
  ) {
    List recipes = catalog.recipes as List
    Map decision = activePass.routerDecision instanceof Map ? (Map) activePass.routerDecision : [:]
    String rid = activePass.routerRecipeId?.toString()?.trim() ?: decision.recipeId?.toString()?.trim() ?: ''
    boolean recipeFound = rid ? AuthoringIntentRecipeCatalog.findRecipeById(recipes, rid) != null : false
    boolean deferPlan = Boolean.TRUE.equals(activePass.deferToPlanLoop) || 'plan'.equals(routingMode)
    String cand = author.cand?.toString()
    String routerVisible = author.routerVisible?.toString()

    log.info(
      'Intent recipe routing: plan mode — recipeId={} confidence={} minConfidence={} matchPass={}',
      rid ?: '(null)',
      conf,
      minC,
      activePass.matchPass
    )

    String noMatchHint = ''
    Map planDeferCatalogTel = [:]
    if (deferPlan) {
      noMatchHint = ToolPrompts.getLlm_AUTHORING_INTENT_ROUTING_DEFER_PLAN_HINT()
      if (AuthoringPreviewContext.authorGenerateImageRequiresPageContextFirst(cand, routerVisible)) {
        noMatchHint += '\n\n[Studio — generate image for anchored page]\n'
        noMatchHint += 'Use **Complex** plan: **GetContent** on the anchored path → identify what the page is about → craft the **GenerateImage** `prompt` from that content **plus** any style/subject instructions in the author message → **GenerateImage** once. Do not guess the subject before **GetContent**.\n'
      }
      String catalogForProbe = activePass.catalogMd?.toString()?.trim() ?:
        AuthoringIntentRecipeCatalog.toRouterCatalogMarkdown(recipes ?: [])
      String deferCatalogBlock =
        AuthoringIntentRecipeCatalog.formatPlanDeferOrchestrationContextBlock(
          recipes,
          routerVisible,
          ops,
          cfg,
          catalogForProbe,
          toolsLoopSessionBundle,
          apiKey,
          model,
          wireBaseUrl
        )
      planDeferCatalogTel =
        AiOrchestrationTools.planDeferCatalogTelemetry(ops, cfg, deferCatalogBlock ?: '', toolsLoopSessionBundle)
      if (deferCatalogBlock?.trim()) {
        noMatchHint += '\n\n' + deferCatalogBlock
      }
    } else {
      noMatchHint = '[Studio — intent router: proceed with normal judgement for this turn.]\n\n'
    }

    result.userTextForToolsLoop = routingEnginePrefix + noMatchHint + (userTextAfterGuard ?: '')
    result.intentRecipeRoutingWireCand = cand
    Map noMatchTel = [
      routingMode          : deferPlan ? 'plan' : routingMode,
      recipeId             : rid,
      confidence           : conf,
      minConfidence        : minC,
      recipeFoundInCatalog : recipeFound,
      routerReason         : activePass.routerReason?.toString()?.trim() ?: decision.reason?.toString()?.trim() ?: '',
      deferToPlanLoop      : deferPlan ? Boolean.TRUE : Boolean.FALSE,
      matchPass            : activePass.matchPass?.toString()
    ]
    noMatchTel.putAll(catalogTel)
    if (!planDeferCatalogTel.isEmpty()) {
      noMatchTel.putAll(planDeferCatalogTel)
    }
    putRoutingEngineTelemetryIfPresent(noMatchTel, toolsLoopSessionBundle)
    wireAuthorTurnGoal(result, toolsLoopSessionBundle, author, activePass)
    noMatchTel.turnGoal = activePass.turnGoal?.toString()?.trim() ?: ''
    noMatchTel.successCriteria = activePass.successCriteria?.toString()?.trim() ?: ''
    attachTelemetry(ops, cfg, result, deferPlan ? 'plan' : 'no_match', noMatchTel)
    mergeAuthorTurnGoalFromBundle(result, toolsLoopSessionBundle)
    return result
  }

  /** Min confidence from classifier pass output, or site config when absent. */
  private static double minConfidenceFromPass(Map activePass, Map cfg) {
    return activePass.minConfidence instanceof Number ?
      ((Number) activePass.minConfidence).doubleValue() :
      StudioAiAssistantProjectConfig.intentRecipeMinConfidence(cfg)
  }

  /** Runs one {@link AuthoringIntentRoutingEngine} phase and merges prefetch markdown into the session bundle. */
  private static void runPrefetchPass(
    StudioToolOperations ops,
    Map cfg,
    Map detCtx,
    Map toolsLoopSessionBundle,
    String passId
  ) {
    if (ops == null || !(cfg instanceof Map) || !(detCtx instanceof Map)) {
      return
    }
    Map pfb = AuthoringIntentRoutingEngine.runPass(ops, cfg, detCtx, passId)
    AuthoringIntentRoutingEngine.mergePassIntoSessionBundle(toolsLoopSessionBundle, pfb, passId)
  }

  /** Builds the LLM router user message (recipe + tool catalogs, prior conversation, author turn). */
  static String buildRouterUserMessage(
    String catalogMd,
    String toolsCatalogMd,
    String currentTurnVisible,
    String priorConversationBody,
    Map cfg,
    String orchestrationWireForSiteContext = null,
    String priorSessionObjective = null
  ) {
    StringBuilder sb = new StringBuilder()
    sb.append('## Recipe catalog\n\n').append((catalogMd ?: '').toString().trim())
    sb.append('\n\n## Tool catalog (weaker than recipes)\n\n').append((toolsCatalogMd ?: '').toString().trim())
    Map siteCtx = AuthoringPreviewContext.parseWorkingSiteFromOrchestrationWire(
      (orchestrationWireForSiteContext ?: '').toString()
    )
    if (Boolean.TRUE.equals(siteCtx.crossSiteWorking)) {
      String work = (siteCtx.workingSiteId ?: '').toString().trim()
      String session = (siteCtx.studioSessionSiteId ?: '').toString().trim()
      sb.append('\n\n## Studio site context\n\n')
      sb.append("Working CMS site id for this turn: \"${work}\". Studio UI session site: \"${session}\". ")
      sb.append('There is no open-preview repository anchor for the session site on this turn. ')
      sb.append('Prior conversation may describe the session site only — choose a recipe that reads the working site ')
      sb.append('(e.g. site content search with ResearchSiteContent), not read-only open-page inquiry, unless bindings include a path on the working site.\n')
    }
    String stickyObjective = (priorSessionObjective ?: '').toString().trim()
    if (stickyObjective) {
      sb.append('\n\n## Session objective (from prior turns in this chat)\n\n')
      sb.append(stickyObjective).append('\n')
    }
    String prior = (priorConversationBody ?: '').toString().trim()
    if (prior) {
      int maxPrior = StudioAiAssistantProjectConfig.intentRecipeEngineMaxTotalChars(cfg)
      maxPrior = maxPrior > 0 ? Math.min(48000, maxPrior) : 24000
      if (prior.length() > maxPrior) {
        prior = '…[prior conversation truncated for router]\n' + prior.substring(prior.length() - maxPrior)
      }
      sb.append('\n\n## Prior conversation\n\n').append(prior)
    }
    String anchor = AuthoringPreviewContext.extractAnchoredRepositoryPath(
      (orchestrationWireForSiteContext ?: '').toString()
    )?.trim()
    if (anchor) {
      sb.append('\n\n## Studio open item (this turn)\n\n')
      sb.append("Repository path: ${anchor}. ")
      sb.append('When the author asks to generate art **for this page** without naming a concrete image subject, ')
      sb.append('use **mode plan** (GetContent on the anchored item first, then GenerateImage). ')
      sb.append('Use **recipe generate_image** only when the author states a specific image subject in their own words.\n')
    }
    if (AuthoringPreviewContext.authoringScopeFieldEditActive(orchestrationWireForSiteContext ?: '')) {
      String fieldId = AuthoringPreviewContext.extractXbFocusedFieldIdFromWire(orchestrationWireForSiteContext ?: '')
      String fieldLabel = AuthoringPreviewContext.extractXbFocusedFieldLabelFromWire(orchestrationWireForSiteContext ?: '')
      String fieldPath = AuthoringPreviewContext.extractXbFocusedContentPathFromWire(orchestrationWireForSiteContext ?: '')
      sb.append('\n\n## Experience Builder field scope (this turn)\n\n')
      sb.append('The author selected **Field** scope in the AI Assistant. ')
      if (fieldLabel) {
        sb.append("Focused field label: ${fieldLabel}. ")
      }
      if (fieldId) {
        sb.append("Focused field id: ${fieldId}. ")
      }
      if (fieldPath) {
        sb.append("Focused content item: ${fieldPath}. ")
      }
      sb.append('When they say "this", "this copy", "this field", or "here" without pasting text, they mean **this focused field** — use **mode recipe** **`modify_page_content`** (GetContent → WriteContent on that item and field), **not** `chat_only`.\n')
    }
    sb.append('\n\n## Author message (this turn)\n\n').append((currentTurnVisible ?: '').toString().trim())
    return sb.toString().trim()
  }

  /**
   * When the author describes generated art and {@code GenerateImage} is configured, prefer
   * {@code generate_image} / {@code GenerateImage} — not {@code GeneratePlaceholderImage} (form sample only).
   */
  private static Map applyAuthorGeneratedImageRoutingCorrection(
    Map decision,
    List recipes,
    String authorVisible,
    String wirePrompt,
    StudioToolOperations ops,
    double minC
  ) {
    if (!(decision instanceof Map)) {
      return decision ?: [:]
    }
    String probe = (authorVisible ?: '').toString().trim() ?
      authorVisible.toString() :
      (wirePrompt ?: '').toString()
    if (AuthoringPreviewContext.authorVisibleWantsPriorGeneratedImageRestored(probe)) {
      return decision
    }
    if (AuthoringPreviewContext.authorVisibleSuggestsSelectiveVersionRestoreForAuthorText(wirePrompt, probe)) {
      return decision
    }
    if (AuthoringPreviewContext.authorGenerateImageRequiresPageContextFirst(wirePrompt, authorVisible ?: probe)) {
      return decision
    }
    if (!AuthoringPreviewContext.authorVisibleSuggestsIntentRecipeGenerateImage(probe)) {
      return decision
    }
    if (!AuthoringIntentRecipeCatalog.isBuiltInWireToolEnabled(ops, 'GenerateImage')) {
      return decision
    }
    double conf = decision.confidence instanceof Number ? ((Number) decision.confidence).doubleValue() : 0.0d
    double boosted = Math.max(conf, 0.85d)
    Map recipe = AuthoringIntentRecipeCatalog.findRecipeById(recipes ?: [], 'generate_image')
    if (recipe != null && boosted >= minC) {
      log.info(
        'Intent recipe routing: author requested generated bitmap — using recipe generate_image (was mode={} toolName={})',
        decision.mode,
        decision.toolName
      )
      return [
        mode      : 'recipe',
        recipeId  : 'generate_image',
        toolName  : null,
        confidence: boosted,
        reason    : 'Author requested a generated image; image model is available.'
      ]
    }
    String toolName = decision.toolName?.toString()?.trim()
    if ('GeneratePlaceholderImage'.equals(toolName) ||
      ('tool'.equals(decision.mode?.toString()?.trim()?.toLowerCase()) && toolName && !'GenerateImage'.equals(toolName))) {
      log.info(
        'Intent recipe routing: author requested generated bitmap — using tool GenerateImage (was toolName={})',
        toolName ?: '(null)'
      )
      return [
        mode      : 'tool',
        recipeId  : null,
        toolName  : 'GenerateImage',
        confidence: Math.max(conf, 0.8d),
        reason    : 'Author requested generated art; use GenerateImage, not a form placeholder.'
      ]
    }
    return decision
  }

  /**
   * Anchored “generate image for this page” without an explicit subject → plan defer (GetContent before GenerateImage).
   */
  private static Map applyAnchoredGenerateImagePlanDefer(
    Map out,
    String wirePrompt,
    String authorVisible,
    String rid,
    String mode,
    String toolName,
    double conf,
    double minC,
    String reason
  ) {
    if (!(out instanceof Map)) {
      return out ?: [:]
    }
    if (!AuthoringPreviewContext.authorGenerateImageRequiresPageContextFirst(wirePrompt, authorVisible)) {
      return out
    }
    boolean generateImageRecipe = 'recipe'.equals(mode) && 'generate_image'.equals(rid)
    boolean generateImageTool = 'tool'.equals(mode) && 'GenerateImage'.equals(toolName)
    if (!generateImageRecipe && !generateImageTool) {
      return out
    }
    log.info(
      'Intent recipe routing: anchored generate-image without explicit subject — plan defer (was mode={} recipeId={} toolName={})',
      mode,
      rid ?: '(null)',
      toolName ?: '(null)'
    )
    out.matched = false
    out.deferToPlanLoop = Boolean.TRUE
    out.matchPass = 'router_plan_anchored_generate_image'
    out.routingMode = 'plan'
    out.routerRecipeId = rid ?: 'generate_image'
    out.remove('recipe')
    out.remove('recipeId')
    out.routerReason = reason?.trim() ?:
      'Author wants generated art for the anchored page; read page content before GenerateImage.'
    out.routerConfidence = conf
    out.minConfidence = minC
    return out
  }

  /** When catalog {@code multiGoalDefer} signals multiple goal groups, prefer plan over a single recipe match. */
  private static Map applyMultiGoalPlanDeferIfSignaled(
    Map out,
    List recipes,
    Map routeCtx,
    Map routingCfg,
    String rid,
    double conf,
    double minC,
    String reason
  ) {
    if (!(out instanceof Map) || !Boolean.TRUE.equals(out.matched)) {
      return out
    }
    if (!AuthoringIntentRecipeCatalog.authorSuggestsMultiGoalDefer(recipes, routeCtx, routingCfg)) {
      return out
    }
    log.info(
      'Intent recipe routing: multiGoalDefer signaled — plan mode (was recipeId={} confidence={})',
      rid ?: '(null)',
      conf
    )
    out.matched = false
    out.deferToPlanLoop = Boolean.TRUE
    out.matchPass = 'router_plan_multi_goal'
    out.routingMode = 'plan'
    out.routerRecipeId = rid
    out.remove('recipe')
    out.remove('recipeId')
    out.routerReason = reason?.trim() ?: 'Multiple intent groups signaled; use plan mode.'
    out.routerConfidence = conf
    out.minConfidence = minC
    return out
  }

  /**
   * Anchored “summarize / describe this page” → {@code open_page_inquiry} (read-only), not
   * {@code modify_page_content} or repository writes.
   */
  private static Map applyAuthorOpenPageInquiryRoutingCorrection(
    Map decision,
    List recipes,
    String authorVisible,
    String wirePrompt,
    double minC
  ) {
    if (!(decision instanceof Map)) {
      return decision ?: [:]
    }
    String anchorCarrier = (wirePrompt ?: '').toString()
    String authorText = (authorVisible ?: '').toString().trim() ?
      authorVisible.toString() :
      anchorCarrier
    if (!AuthoringPreviewContext.authorVisibleSuggestsOpenPageInquiryForAuthorText(anchorCarrier, authorText)) {
      return decision
    }
    Map recipe = AuthoringIntentRecipeCatalog.findRecipeById(recipes ?: [], 'open_page_inquiry')
    if (recipe == null) {
      return decision
    }
    String rid = decision.recipeId?.toString()?.trim()
    String mode = decision.mode?.toString()?.trim()?.toLowerCase() ?: 'plan'
    if ('recipe'.equals(mode) && 'open_page_inquiry'.equals(rid)) {
      return decision
    }
    double conf = decision.confidence instanceof Number ? ((Number) decision.confidence).doubleValue() : 0.0d
    double boosted = Math.max(conf, 0.85d)
    if (boosted < minC) {
      return decision
    }
    log.info(
      'Intent recipe routing: read-only page inquiry — using recipe open_page_inquiry (was mode={} recipeId={} toolName={})',
      mode,
      rid ?: '(null)',
      decision.toolName ?: '(null)'
    )
    return [
      mode      : 'recipe',
      recipeId  : 'open_page_inquiry',
      toolName  : null,
      confidence: boosted,
      reason    : 'Author wants a read-only summary of the anchored page; not a content edit.'
    ]
  }

  /**
   * Selective field restore from version history — not full-page revert or new GenerateImage.
   */
  private static Map applyAuthorSelectiveVersionRestoreRoutingCorrection(
    Map decision,
    List recipes,
    String authorVisible,
    String wirePrompt,
    double minC
  ) {
    if (!(decision instanceof Map)) {
      return decision ?: [:]
    }
    String authorText = (authorVisible ?: '').toString().trim() ?
      authorVisible.toString() :
      (wirePrompt ?: '').toString()
    if (!AuthoringPreviewContext.authorVisibleSuggestsSelectiveVersionRestoreForAuthorText(
      wirePrompt,
      authorText
    )) {
      return decision
    }
    Map recipe = AuthoringIntentRecipeCatalog.findRecipeById(recipes ?: [], 'restore_fields_from_version')
    if (recipe == null) {
      return decision
    }
    String rid = decision.recipeId?.toString()?.trim()
    if ('recipe'.equals(decision.mode?.toString()?.trim()?.toLowerCase()) &&
      'restore_fields_from_version'.equals(rid)) {
      return decision
    }
    double conf = decision.confidence instanceof Number ? ((Number) decision.confidence).doubleValue() : 0.0d
    double boosted = Math.max(conf, 0.9d)
    if (boosted < minC) {
      return decision
    }
    log.info(
      'Intent recipe routing: selective version restore — using recipe restore_fields_from_version (was mode={} recipeId={})',
      decision.mode,
      rid ?: '(null)'
    )
    return [
      mode      : 'recipe',
      recipeId  : 'restore_fields_from_version',
      toolName  : null,
      confidence: boosted,
      reason    : 'Author wants specific fields restored from version history — not a full-page revert or new image generation.'
    ]
  }

  /**
   * Author complained about unwanted copy changes — restore from history instead of rewriting.
   */
  private static Map applyAuthorContentModificationComplaintRoutingCorrection(
    Map decision,
    List recipes,
    String authorVisible,
    String wirePrompt,
    double minC
  ) {
    if (!(decision instanceof Map)) {
      return decision ?: [:]
    }
    String authorText = (authorVisible ?: '').toString().trim() ?
      authorVisible.toString() :
      (wirePrompt ?: '').toString()
    if (!AuthoringPreviewContext.authorVisibleIsContentModificationComplaint(authorText)) {
      return decision
    }
    Map recipe = AuthoringIntentRecipeCatalog.findRecipeById(recipes ?: [], 'restore_fields_from_version')
    if (recipe == null) {
      return decision
    }
    String rid = decision.recipeId?.toString()?.trim()
    String mode = decision.mode?.toString()?.trim()?.toLowerCase() ?: ''
    if ('restore_fields_from_version'.equals(rid)) {
      return decision
    }
    if (!'modify_page_content'.equals(rid) && !'plan'.equals(mode)) {
      return decision
    }
    double conf = decision.confidence instanceof Number ? ((Number) decision.confidence).doubleValue() : 0.0d
    double boosted = Math.max(conf, 0.88d)
    if (boosted < minC) {
      return decision
    }
    log.info(
      'Intent recipe routing: content modification complaint — using restore_fields_from_version (was mode={} recipeId={})',
      mode,
      rid ?: '(null)'
    )
    return [
      mode      : 'recipe',
      recipeId  : 'restore_fields_from_version',
      toolName  : null,
      confidence: boosted,
      reason    : 'Author complained about unwanted copy changes — restore prior field values from version history instead of rewriting.'
    ]
  }

  /**
   * {@code revert_content_version} only when the author wants a full-item rollback — otherwise selective restore.
   */
  private static Map applyAuthorFullPageRevertOnlyCorrection(
    Map decision,
    List recipes,
    String authorVisible,
    String wirePrompt,
    double minC
  ) {
    if (!(decision instanceof Map)) {
      return decision ?: [:]
    }
    String rid = decision.recipeId?.toString()?.trim()
    if (!'revert_content_version'.equals(rid)) {
      return decision
    }
    String authorText = (authorVisible ?: '').toString().trim() ?
      authorVisible.toString() :
      (wirePrompt ?: '').toString()
    if (AuthoringPreviewContext.authorVisibleSuggestsFullPageRevertIntent(authorText)) {
      return decision
    }
    Map recipe = AuthoringIntentRecipeCatalog.findRecipeById(recipes ?: [], 'restore_fields_from_version')
    if (recipe == null) {
      return decision
    }
    double conf = decision.confidence instanceof Number ? ((Number) decision.confidence).doubleValue() : 0.0d
    double boosted = Math.max(conf, 0.88d)
    if (boosted < minC) {
      return decision
    }
    log.info(
      'Intent recipe routing: revert wording is field-level — using restore_fields_from_version (was revert_content_version)'
    )
    return [
      mode      : 'recipe',
      recipeId  : 'restore_fields_from_version',
      toolName  : null,
      confidence: boosted,
      reason    : 'Revert wording targets copy or fields only — use selective version restore, not revert_change on the whole item.'
    ]
  }

  /**
   * Anchored topical rewrite (“redo / make the page about …”) → {@code modify_page_content}, not
   * {@code open_page_inquiry} or chat-only. Never overrides LLM presentation-layer recipes or styling asks.
   */
  private static Map applyAuthorPageContentModificationRoutingCorrection(
    Map decision,
    List recipes,
    String authorVisible,
    String wirePrompt,
    double minC
  ) {
    if (!(decision instanceof Map)) {
      return decision ?: [:]
    }
    if (AuthoringDeliverablePolicy.shouldSuppressRepoRoutingCorrections(authorVisible, decision, wirePrompt)) {
      return decision
    }
    String authorText = (authorVisible ?: '').toString().trim() ?
      authorVisible.toString() :
      (wirePrompt ?: '').toString()
    if (AuthoringPreviewContext.authorVisibleSuggestsPresentationLayerWork(authorText)) {
      return decision
    }
    if (!AuthoringPreviewContext.authorVisibleSuggestsAnchoredPageContentModificationForAuthorText(
      wirePrompt,
      authorText
    )) {
      return decision
    }
    Map recipe = AuthoringIntentRecipeCatalog.findRecipeById(recipes ?: [], 'modify_page_content')
    if (recipe == null) {
      return decision
    }
    String rid = decision.recipeId?.toString()?.trim()
    String mode = decision.mode?.toString()?.trim()?.toLowerCase() ?: ''
    if ('modify_page_content'.equals(rid)) {
      return decision
    }
    if (['stylesheet_change', 'template_display_change', 'build_page_feature'].contains(rid)) {
      return decision
    }
    boolean eligibleMisroute =
      'open_page_inquiry'.equals(rid) ||
      'chat_only'.equals(mode) ||
      Boolean.TRUE.equals(decision.toolsLoopDisable)
    if (!eligibleMisroute) {
      return decision
    }
    double conf = decision.confidence instanceof Number ? ((Number) decision.confidence).doubleValue() : 0.0d
    double boosted = Math.max(conf, 0.9d)
    if (boosted < minC) {
      return decision
    }
    log.info(
      'Intent recipe routing: anchored page content modification — using recipe modify_page_content (was mode={} recipeId={})',
      decision.mode,
      rid ?: '(null)'
    )
    Map replacement = [
      mode      : 'recipe',
      recipeId  : 'modify_page_content',
      toolName  : null,
      confidence: boosted,
      reason    : 'Author wants to rewrite topical copy on the anchored page; not a read-only inquiry.'
    ]
    for (String key : ['sessionObjective', 'authorUnderstanding', 'turnGoal', 'turnRelation', 'deliverable', 'successCriteria']) {
      def val = decision[key]
      if (val?.toString()?.trim()) {
        replacement[key] = val
      }
    }
    return replacement
  }

  /**
   * Experience Builder Field scope + deictic copy edit → {@code modify_page_content} (author should not paste field text).
   */
  private static Map applyXbFieldScopeFieldEditRoutingCorrection(
    Map decision,
    List recipes,
    String authorVisible,
    String wirePrompt,
    double minC
  ) {
    if (!(decision instanceof Map)) {
      return decision ?: [:]
    }
    if (!AuthoringPreviewContext.authorVisibleSuggestsXbScopedFieldCopyEdit(wirePrompt, authorVisible)) {
      return decision
    }
    Map recipe = AuthoringIntentRecipeCatalog.findRecipeById(recipes ?: [], 'modify_page_content')
    if (recipe == null) {
      return decision
    }
    String rid = decision.recipeId?.toString()?.trim()
    String mode = decision.mode?.toString()?.trim()?.toLowerCase() ?: ''
    if ('modify_page_content'.equals(rid) && 'recipe'.equals(mode)) {
      return decision
    }
    double conf = decision.confidence instanceof Number ? ((Number) decision.confidence).doubleValue() : 0.0d
    double boosted = Math.max(conf, 0.92d)
    if (boosted < minC) {
      return decision
    }
    log.info(
      'Intent recipe routing: XB field scope copy edit — using recipe modify_page_content (was mode={} recipeId={})',
      mode,
      rid ?: '(null)'
    )
    Map replacement = [
      mode        : 'recipe',
      recipeId    : 'modify_page_content',
      toolName    : null,
      confidence  : boosted,
      deliverable : 'repo_write',
      reason      : 'Author selected an Experience Builder field and asked to edit its copy; persist the focused field via WriteContent.'
    ]
    for (String key : ['sessionObjective', 'authorUnderstanding', 'turnGoal', 'turnRelation', 'successCriteria']) {
      def val = decision[key]
      if (val?.toString()?.trim()) {
        replacement[key] = val
      }
    }
    return replacement
  }

  /**
   * Broken preview / HTTP 500 repair — force tools on ({@code modify_page_content} or plan), never chat-only.
   */
  private static Map applyAuthorBrokenPreviewRepairRoutingCorrection(
    Map decision,
    List recipes,
    String authorVisible,
    String wirePrompt,
    double minC
  ) {
    if (!(decision instanceof Map)) {
      return decision ?: [:]
    }
    if (AuthoringDeliverablePolicy.shouldSuppressRepoRoutingCorrections(authorVisible, decision, wirePrompt)) {
      return decision
    }
    String authorText = (authorVisible ?: '').toString().trim() ?
      authorVisible.toString() :
      (wirePrompt ?: '').toString()
    if (!AuthoringPreviewContext.authorVisibleReportsBrokenPreviewRepair(authorText)) {
      return decision
    }
    String mode = decision.mode?.toString()?.trim()?.toLowerCase() ?: 'plan'
    if (!'chat_only'.equals(mode) && !Boolean.TRUE.equals(decision.toolsLoopDisable)) {
      return decision
    }
    Map recipe = AuthoringIntentRecipeCatalog.findRecipeById(recipes ?: [], 'modify_page_content')
    if (recipe == null) {
      return [
        mode      : 'plan',
        recipeId  : null,
        toolName  : null,
        confidence: Math.max(
          decision.confidence instanceof Number ? ((Number) decision.confidence).doubleValue() : 0.85d,
          minC
        ),
        reason    : 'Author reports a broken preview; plan mode with tools to repair content and verify render.'
      ]
    }
    double conf = decision.confidence instanceof Number ? ((Number) decision.confidence).doubleValue() : 0.85d
    double boosted = Math.max(conf, 0.9d)
    log.info(
      'Intent recipe routing: broken preview repair — overriding chat_only to modify_page_content (was mode={})',
      mode
    )
    return [
      mode      : 'recipe',
      recipeId  : 'modify_page_content',
      toolName  : null,
      confidence: boosted,
      reason    : 'Author reports preview/render failure; repair anchored page content and re-verify preview.'
    ]
  }

  /**
   * LLM classifier pass: optional prefetch, then {@code llmCompleter} with router system prompt.
   * Resolves {@code turnGoal} and {@code successCriteria} via {@link AuthoringTurnGoal#resolveFromRouterDecision}.
   *
   * @param llmCompleter {@code (projectCfg, systemPrompt, userMessage) -> rawJson} — must return JSON-only text
   *        ({@link plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration#intentRouterJsonCompletionOnly})
   */
  static Map matchPass(
    List recipes,
    Map cfg,
    Map detCtx,
    String routerVisible,
    String apiKey,
    String model,
    String wireBaseUrl,
    Map toolsLoopSessionBundle,
    String authorFieldLabelEarly,
    Map routingCfg,
    Closure<String> llmCompleter
  ) {
    Map out = [matched: false]
    String visible = (routerVisible ?: '').toString().trim()
    Map routeCtx = detCtx instanceof Map ? new LinkedHashMap(detCtx) : [:]
    routeCtx.routerVisible = visible
    String wireForMemory = (routeCtx.cand ?: '').toString()
    StudioToolOperations routeOps = routeCtx.ops instanceof StudioToolOperations ?
      (StudioToolOperations) routeCtx.ops :
      null

    runPrefetchPass(
      routeOps,
      cfg,
      routeCtx,
      toolsLoopSessionBundle,
      AuthoringIntentRoutingEngine.PASS_INITIAL
    )

    String catalogMd = AuthoringIntentRecipeCatalog.toRouterCatalogMarkdown(recipes ?: [])
    String toolsCatalogMd = AuthoringIntentRecipeCatalog.toRouterToolsCatalogMarkdown(routeOps, cfg)
    double minC = StudioAiAssistantProjectConfig.intentRecipeMinConfidence(cfg)

    runPrefetchPass(
      routeOps,
      cfg,
      routeCtx,
      toolsLoopSessionBundle,
      AuthoringIntentRoutingEngine.PASS_BEFORE_ROUTER
    )
    String routingPrefixForRouter = AuthoringIntentRoutingEngine.wirePrefixFromBundle(toolsLoopSessionBundle)
    String priorForRouter = AuthoringPreviewContext.extractPriorConversationBody(wireForMemory)?.trim()
    String priorSessionObjective = toolsLoopSessionBundle instanceof Map ?
      toolsLoopSessionBundle.authorSessionObjective?.toString()?.trim() ?: '' :
      ''
    String userRouter = routingPrefixForRouter + buildRouterUserMessage(
      catalogMd,
      toolsCatalogMd,
      visible,
      priorForRouter,
      cfg,
      wireForMemory,
      priorSessionObjective
    )
    if (!(llmCompleter instanceof Closure)) {
      throw new IllegalArgumentException('Router.matchPass: llmCompleter is required')
    }
    String rawJson = llmCompleter.call(
      cfg,
      ToolPrompts.getLlm_AUTHORING_INTENT_RECIPE_ROUTER_SYSTEM(),
      userRouter
    )
    Map decision = AuthoringIntentRecipeRouter.parseRouterJson(rawJson)
    decision = AuthoringDeliverablePolicy.apply(
      decision,
      visible,
      wireForMemory,
      priorSessionObjective
    )
    decision = AuthoringDeliverablePolicy.bindApprovalToPersistRecipeMode(
      decision,
      visible,
      wireForMemory,
      priorSessionObjective
    )
    if (toolsLoopSessionBundle instanceof Map && decision.sessionObjective?.toString()?.trim()) {
      toolsLoopSessionBundle.authorSessionObjective = decision.sessionObjective.toString().trim()
    }
    decision = applyAuthorSelectiveVersionRestoreRoutingCorrection(
      decision,
      recipes,
      visible,
      wireForMemory,
      minC
    )
    decision = applyAuthorContentModificationComplaintRoutingCorrection(
      decision,
      recipes,
      visible,
      wireForMemory,
      minC
    )
    decision = applyAuthorFullPageRevertOnlyCorrection(
      decision,
      recipes,
      visible,
      wireForMemory,
      minC
    )
    decision = applyAuthorGeneratedImageRoutingCorrection(
      decision,
      recipes,
      visible,
      wireForMemory,
      routeOps,
      minC
    )
    decision = applyAuthorOpenPageInquiryRoutingCorrection(
      decision,
      recipes,
      visible,
      wireForMemory,
      minC
    )
    decision = applyAuthorPageContentModificationRoutingCorrection(
      decision,
      recipes,
      visible,
      wireForMemory,
      minC
    )
    decision = applyXbFieldScopeFieldEditRoutingCorrection(
      decision,
      recipes,
      visible,
      wireForMemory,
      minC
    )
    decision = applyAuthorBrokenPreviewRepairRoutingCorrection(
      decision,
      recipes,
      visible,
      wireForMemory,
      minC
    )
    decision = AuthoringDeliverablePolicy.finalizeAfterCorrections(decision, visible, wireForMemory)
    if (toolsLoopSessionBundle instanceof Map && decision.sessionObjective?.toString()?.trim()) {
      toolsLoopSessionBundle.authorSessionObjective = decision.sessionObjective.toString().trim()
    }
    String mode = decision.mode?.toString()?.trim()?.toLowerCase() ?: 'plan'
    double conf = decision.confidence instanceof Number ? ((Number) decision.confidence).doubleValue() : 0.0d
    String rid = decision.recipeId?.toString()?.trim()
    String toolName = decision.toolName?.toString()?.trim()
    String reason = decision.reason?.toString()?.trim() ?: ''

    out.catalogMd = catalogMd
    out.routerDecision = decision
    out.routerConfidence = conf
    out.minConfidence = minC
    out.routingMode = mode
    out.routerReason = reason
    out.routerDeliverable = decision.deliverable?.toString()?.trim() ?: ''
    out.routerTurnRelation = decision.turnRelation?.toString()?.trim() ?: ''
    out.routerSessionObjective = decision.sessionObjective?.toString()?.trim() ?: ''
    out.routerAuthorUnderstanding = decision.authorUnderstanding?.toString()?.trim() ?: ''

    String anchorPath = AuthoringPreviewContext.resolveAnchoredRepositoryPath(wireForMemory)?.trim() ?: ''

    log.info(
      'Intent recipe routing: LLM router mode={} recipeId={} toolName={} confidence={} reason={}',
      mode,
      rid ?: '(null)',
      toolName ?: '(null)',
      conf,
      reason
    )

    if ('chat_only'.equals(mode)) {
      out.matchPass = 'router_chat_only'
      out.toolsLoopDisable = Boolean.TRUE
      assignMatchPassTurnGoal(out, decision, visible, anchorPath)
      return out
    }

    if ('tool'.equals(mode) && toolName) {
      out = applyAnchoredGenerateImagePlanDefer(
        out,
        wireForMemory,
        visible,
        rid,
        mode,
        toolName,
        conf,
        minC,
        reason
      )
      if (Boolean.TRUE.equals(out.deferToPlanLoop)) {
        out.routerRecipeFound = rid ? AuthoringIntentRecipeCatalog.findRecipeById(recipes, rid) != null : false
        assignMatchPassTurnGoal(out, decision, visible, anchorPath)
        return out
      }
      out.matchPass = 'router_tool'
      out.toolsLoopAllowlist = [toolName]
      out.deferToPlanLoop = false
      assignMatchPassTurnGoal(out, decision, visible, anchorPath)
      return out
    }

    if ('recipe'.equals(mode) && rid) {
      Map recipe = AuthoringIntentRecipeCatalog.findRecipeById(recipes, rid)
      if (recipe != null && conf >= minC) {
        out.matched = true
        out.recipe = recipe
        out.recipeId = rid
        out.confidence = conf
        out.skipRecipePrefetch = false
        out.matchPass = 'router'
        AuthoringIntentRecipeCatalog.copyRecipeToolsLoopPolicyToRoutingPass(out, recipe)
        out = applyAnchoredGenerateImagePlanDefer(
          out,
          wireForMemory,
          visible,
          rid,
          mode,
          toolName,
          conf,
          minC,
          reason
        )
        if (Boolean.TRUE.equals(out.deferToPlanLoop)) {
          out.routerRecipeFound = true
          assignMatchPassTurnGoal(out, decision, visible, anchorPath)
          return out
        }
        out = applyMultiGoalPlanDeferIfSignaled(out, recipes, routeCtx, routingCfg, rid, conf, minC, reason)
        if (Boolean.TRUE.equals(out.deferToPlanLoop)) {
          out.routerRecipeFound = true
          assignMatchPassTurnGoal(out, decision, visible, anchorPath)
          return out
        }
        assignMatchPassTurnGoal(out, decision, visible, anchorPath)
        return out
      }
      log.info(
        'Intent recipe routing: router recipe {} confidence {} below min {} or missing — plan mode',
        rid,
        conf,
        minC
      )
    }

    out.deferToPlanLoop = true
    out.matchPass = 'router_plan'
    out.routerRecipeId = rid
    out.routerRecipeFound = rid ? AuthoringIntentRecipeCatalog.findRecipeById(recipes, rid) != null : false
    assignMatchPassTurnGoal(out, decision, visible, anchorPath)
    return out
  }

  /**
   * Resolves {@code turnGoal} / {@code successCriteria} from final pass state (after plan-defer rewrites).
   */
  private static void assignMatchPassTurnGoal(Map out, Map decision, String visible, String anchorPath) {
    if (!(out instanceof Map)) {
      return
    }
    Map goalDecision = decision instanceof Map ? new LinkedHashMap(decision) : [:]
    String mode = out.routingMode?.toString()?.trim()?.toLowerCase() ?:
      goalDecision.mode?.toString()?.trim()?.toLowerCase() ?: 'plan'
    String rid = out.recipeId?.toString()?.trim() ?:
      out.routerRecipeId?.toString()?.trim() ?:
      goalDecision.recipeId?.toString()?.trim()
    if (Boolean.TRUE.equals(out.deferToPlanLoop)) {
      goalDecision.mode = 'plan'
      if (AuthoringIntentCard.isWeakTurnGoal(goalDecision.turnGoal?.toString())) {
        goalDecision.remove('turnGoal')
      }
      if (AuthoringIntentCard.isWeakSuccessCriteria(goalDecision.successCriteria?.toString())) {
        goalDecision.remove('successCriteria')
      }
      String rr = out.routerReason?.toString()?.trim()
      if (rr && AuthoringIntentCard.isWeakTurnGoal(rr)) {
        goalDecision.remove('reason')
      } else if (rr) {
        goalDecision.reason = rr
      }
      mode = 'plan'
    }
    Map goalResolved = AuthoringTurnGoal.resolveFromRouterDecision(goalDecision, visible, anchorPath, rid, mode)
    out.turnGoal = goalResolved.turnGoal?.toString() ?: ''
    out.successCriteria = goalResolved.successCriteria?.toString() ?: ''
  }

  /** Attaches {@code intentRecipeRoutingTelemetry} to {@code result} for SSE and session logs. */
  static Map attachTelemetry(StudioToolOperations ops, Map cfg, Map result, String outcome, Map extra = null) {
    Map tel = new LinkedHashMap()
    boolean routing = false
    boolean engine = false
    if (cfg != null) {
      routing = StudioAiAssistantProjectConfig.intentRecipeRoutingEnabled(cfg)
      engine = StudioAiAssistantProjectConfig.intentRecipeEngineEnabled(cfg)
    }
    tel.put('intentRecipeRoutingEnabled', routing)
    tel.put('intentRecipeEngineEnabled', engine)
    tel.put('outcome', (outcome ?: 'unknown').toString())
    if (extra != null && !extra.isEmpty()) {
      extra.each { k, v ->
        if (v != null) {
          tel.put(k.toString(), v)
        }
      }
    }
    tel.put('intentMatched', 'matched'.equals((outcome ?: '').toString()))
    if (!tel.containsKey('prefetchSteps')) {
      tel.put('prefetchSteps', [])
    }
    if (!tel.containsKey('prefetchEnvelopeTruncated')) {
      tel.put('prefetchEnvelopeTruncated', false)
    }
    if (!tel.containsKey('prefetchRan')) {
      tel.put('prefetchRan', false)
    }
    result.put('intentRecipeRoutingTelemetry', tel)
    return result
  }

  /** Binds a matched recipe: JVM prefetch, hotpath directives, prelude on {@code userTextForToolsLoop}. */
  static Map attachMatchedRecipe(
    StudioToolOperations ops,
    Map cfg,
    Map result,
    String userTextAfterGuard,
    Map recipe,
    String rid,
    double conf,
    double minC,
    String routerReason,
    String fullWirePrompt,
    String visible,
    String authorFieldLabelOverride = null,
    boolean skipRecipePrefetch = false,
    String toolsLoopPrefetchSupplement = '',
    Map toolsLoopPrefetchSupplementConfig = null,
    List toolsLoopRequireSuccessfulTools = null,
    String matchedUserPreludeOverride = null,
    Closure recipePrefetchProgressListener = null
  ) {
    Map pfb = skipRecipePrefetch ?
      [
        markdown                : '',
        prefetchSteps           : [],
        prefetchEnvelopeTruncated: false,
        initialBindings         : [:]
      ] :
      AuthoringIntentRecipeEngine.runPrefetchBlock(ops, recipe, cfg, null, recipePrefetchProgressListener)
    String prefetch = (pfb.markdown ?: '').toString()
    List pfbSteps = pfb.prefetchSteps instanceof List ? (List) pfb.prefetchSteps : []
    boolean prefetchEnvTrunc = Boolean.TRUE.equals(pfb.prefetchEnvelopeTruncated)
    boolean prefetchRan = prefetch.trim().length() > 0
    Map hotpathMeta = AuthoringIntentRecipeEngine.buildPrefetchHotpathDirective(ops, prefetch)
    String hotpathDirective = (hotpathMeta?.directive ?: '').toString()
    boolean prefetchSkipRedundantGetForListedPath = Boolean.TRUE.equals(hotpathMeta?.duplicateGetContentBanned)
    String authorFieldLabel = (authorFieldLabelOverride ?: AiOrchestration.extractAuthorFieldLabelPhraseForRouting(visible ?: '')).toString()
    Map fieldHot = 'open_page_inquiry'.equals(rid) ?
      [directive: '', resolvedFieldId: '', resolvedFieldLabel: ''] :
      AuthoringIntentRecipeEngine.buildSimpleFieldEditHotpathExtras(prefetch, authorFieldLabel)
    hotpathDirective = hotpathDirective + (fieldHot?.directive ?: '').toString()
    if ('open_page_inquiry'.equals(rid)) {
      if (prefetchSkipRedundantGetForListedPath) {
        hotpathDirective =
          '[Studio — read-only page inquiry: Recipe-engine prefetch already includes successful **GetContent** with full **contentXml** for the anchored path. ' +
          'Answer in prose from that XML. Do **not** call **GetContent** again on this path. Do **not** WriteContent unless the author explicitly asks to edit.\n\n'
      } else {
        hotpathDirective = ''
      }
    }
    String prefetchSupplementId = (toolsLoopPrefetchSupplement ?: '').toString().trim()
    List<String> requireSuccessfulTools = []
    if (toolsLoopRequireSuccessfulTools instanceof List) {
      for (Object o : (List) toolsLoopRequireSuccessfulTools) {
        String n = o?.toString()?.trim()
        if (n) {
          requireSuccessfulTools.add(n)
        }
      }
    }
    Map recipeForToolsLoopPolicy = recipe instanceof Map ? (Map) recipe : [:]
    if (!prefetchSupplementId && recipeForToolsLoopPolicy) {
      prefetchSupplementId = AuthoringIntentRecipeCatalog.toolsLoopPrefetchSupplementFromMatch([recipe: recipeForToolsLoopPolicy])
    }
    if (requireSuccessfulTools.isEmpty() && recipeForToolsLoopPolicy) {
      requireSuccessfulTools.addAll(
        AuthoringIntentRecipeCatalog.toolsLoopRequireSuccessfulToolsFromMatch([recipe: recipeForToolsLoopPolicy])
      )
    }
    Map supplementResult = [:]
    Map supplementConfig = toolsLoopPrefetchSupplementConfig instanceof Map ?
      new LinkedHashMap<>(toolsLoopPrefetchSupplementConfig) :
      [:]
    if (supplementConfig.isEmpty() && recipeForToolsLoopPolicy) {
      supplementConfig.putAll(
        AuthoringIntentRecipeCatalog.toolsLoopPrefetchSupplementConfigFromMatch([recipe: recipeForToolsLoopPolicy])
      )
    }
    if (prefetchSupplementId &&
      !AuthoringIntentRecipeCatalog.collectPrefetchEngineSteps(recipe).isEmpty() &&
      prefetch.trim()) {
      supplementConfig.put('recipeContextPrefetchMarkdown', prefetch)
    }
    if (pfb.initialBindings instanceof Map && !((Map) pfb.initialBindings).isEmpty()) {
      supplementConfig.put('prefetchInitialBindings', pfb.initialBindings)
    }
    if (recipePrefetchProgressListener instanceof Closure) {
      supplementConfig.put('recipePrefetchProgressListener', recipePrefetchProgressListener)
    }
    if (prefetchSupplementId) {
      String prefetchWirePrompt = (fullWirePrompt ?: visible ?: '').toString()
      if (!prefetchWirePrompt.trim() && visible?.trim()) {
        prefetchWirePrompt = visible.toString()
      }
      supplementResult = AuthoringIntentRecipeEngine.runPrefetchSupplement(
        prefetchSupplementId,
        ops,
        cfg,
        prefetchWirePrompt,
        supplementConfig
      )
      String supplementMarkdown = (supplementResult?.markdown ?: '').toString()
      if (supplementMarkdown.trim()) {
        prefetch = prefetch + supplementMarkdown
        prefetchRan = true
      }
      if (supplementResult?.prefetchSteps instanceof List) {
        List mergedSteps = new ArrayList<>(pfbSteps)
        mergedSteps.addAll((List) supplementResult.prefetchSteps)
        pfbSteps = mergedSteps
      }
      hotpathDirective = hotpathDirective +
        AuthoringIntentRecipeEngine.buildPrefetchSupplementHotpath(prefetchSupplementId, prefetchWirePrompt, supplementResult)
      if ('createFromChatDraft'.equals(prefetchSupplementId) && supplementResult?.suggestedNewItemPath) {
        String priorForPrefill = AuthoringPreviewContext.extractPriorConversationBody(prefetchWirePrompt)
        result.createFromChatDraftPrefill = [
          path                     : (supplementResult.suggestedNewItemPath ?: '').toString().trim(),
          siblingPath              : (supplementResult.siblingPath ?: '').toString().trim(),
          resolvedContentTypeId    : (supplementResult.resolvedContentTypeId ?: '').toString().trim(),
          draftExtractReady        : Boolean.TRUE.equals(supplementResult.draftExtractReady),
          priorConversationChars   : priorForPrefill?.length() ?: 0,
          prefetchSupplementConfig : supplementResult.prefetchSupplementConfig instanceof Map ?
            supplementResult.prefetchSupplementConfig :
            supplementConfig
        ]
      }
    }
    boolean newItemNeedsSiblingShape = 'createFromChatDraft'.equals(prefetchSupplementId)
    Map newContentItemSiblingHot = newItemNeedsSiblingShape ?
      AuthoringIntentRecipeEngine.buildNewContentItemSiblingReadDirective(prefetch) :
      [directive: '', siblingGetContentPresent: Boolean.TRUE]
    if (newItemNeedsSiblingShape) {
      hotpathDirective = hotpathDirective + (newContentItemSiblingHot?.directive ?: '').toString()
    }
    String prefetchResolvedFieldId = (fieldHot?.resolvedFieldId ?: '').toString().trim()
    String prefetchResolvedFieldLabel = (fieldHot?.resolvedFieldLabel ?: '').toString().trim()
    Map<String, Map> recipeInitialBindings = pfb.initialBindings instanceof Map ?
      (Map<String, Map>) pfb.initialBindings :
      [:]
    Map<String, Map> recipeCurrentBindings = AuthoringIntentRecipeBindings.deepCopyBindingMap(recipeInitialBindings)
    String catalogSiteId = ''
    try {
      catalogSiteId = ops?.resolveEffectiveSiteId('')?.toString()?.trim() ?: ''
    } catch (Throwable ignoredSite) {
    }
    String prelude =
      AuthoringIntentRecipeCatalog.formatMatchedRecipePrelude(
        recipe,
        rid,
        conf,
        routerReason,
        recipeInitialBindings,
        recipeCurrentBindings,
        visible
      )
    String orchPrelude = AuthoringIntentRecipeCatalog.matchedUserPrelude(recipe, ops, catalogSiteId)
    String preludeOverride = (matchedUserPreludeOverride ?: '').toString().trim()
    if (preludeOverride) {
      orchPrelude = preludeOverride
    }
    if (orchPrelude) {
      prelude = orchPrelude + '\n\n' + prelude
    }
    Map execPlan = AuthoringIntentRecipePlanCompiler.compile(recipe)
    Map matchedTelExtra = new LinkedHashMap<>()
    matchedTelExtra.putAll(AuthoringIntentRecipeCatalog.orchestrationTelemetryExtras(recipe))
    matchedTelExtra.putAll(AuthoringIntentRecipeCatalog.authorUrlExclusiveTelemetryOverlay(recipe, visible ?: ''))
    matchedTelExtra.putAll(
      AuthoringIntentRecipeCatalog.prefetchSupplementTelemetryOverlay(
        prefetchSupplementId,
        supplementResult,
        requireSuccessfulTools
      )
    )
    matchedTelExtra.executionPlanStepCount = execPlan.steps instanceof List ? ((List) execPlan.steps).size() : 0
    matchedTelExtra.confirmationServerStepsPending =
      AuthoringIntentRecipePlanCompiler.hasConfirmationServerSteps(execPlan)
    matchedTelExtra.putAll([
      recipeId                                     : rid,
      recipeTitle                                  : (recipe?.title?.toString()?.trim() ?: rid),
      confidence                                   : conf,
      minConfidence                                : minC,
      recipeFoundInCatalog                         : true,
      prefetchRan                                  : prefetchRan,
      prefetchSteps                                : pfbSteps,
      prefetchEnvelopeTruncated                    : prefetchEnvTrunc,
      prefetchSkipRedundantGetContentForListedPath : prefetchSkipRedundantGetForListedPath,
      prefetchResolvedFieldId                      : prefetchResolvedFieldId,
      prefetchResolvedFieldLabel                   : prefetchResolvedFieldLabel,
      routerReason                                 : (routerReason ?: '').toString().trim(),
      siteId                                       : catalogSiteId,
      recipeChatLine                               : AuthoringIntentRecipeCatalog.formatIntentRecipeChatLine(recipe, catalogSiteId)
    ])
    if ('open_page_inquiry'.equals(rid) && prefetchSkipRedundantGetForListedPath && !prefetchEnvTrunc) {
      matchedTelExtra.toolsLoopDisable = Boolean.TRUE
    }
    if (newItemNeedsSiblingShape &&
      !Boolean.TRUE.equals(newContentItemSiblingHot?.siblingGetContentPresent) &&
      !Boolean.TRUE.equals(supplementResult?.siblingGetContentPresent) &&
      !Boolean.TRUE.equals(matchedTelExtra.toolsLoopSiblingGetContentRequired)) {
      matchedTelExtra.newContentItemSiblingGetContentRequired = Boolean.TRUE
    }
    String writeVerificationId =
      AuthoringIntentRecipeCatalog.toolsLoopWriteVerificationFromMatch([recipe: recipeForToolsLoopPolicy])
    Map writeVerificationConfig =
      AuthoringIntentRecipeCatalog.writeVerificationConfigFromMatch([recipe: recipeForToolsLoopPolicy])
    if (writeVerificationId) {
      matchedTelExtra.toolsLoopWriteVerification = writeVerificationId
    }
    if (!writeVerificationConfig.isEmpty()) {
      matchedTelExtra.toolsLoopWriteVerificationConfig = writeVerificationConfig
    } else if (writeVerificationId) {
      log.warn(
        'Tools-loop: recipe {} has toolsLoopWriteVerification={} but empty writeVerification map — verification will only apply generic repairs',
        rid ?: '(unknown)',
        writeVerificationId
      )
    }
    if (AuthoringIntentRecipeEngine.prefetchIncludesFormDefinitions(prefetch)) {
      matchedTelExtra.toolsLoopFormDefsPrefetched = Boolean.TRUE
    }
    if (Boolean.TRUE.equals(supplementResult?.toolsLoopFastPath) ||
      Boolean.TRUE.equals(supplementConfig?.toolsLoopFastPath) ||
      (Boolean.TRUE.equals(matchedTelExtra.toolsLoopFormDefsPrefetched) &&
        !AuthoringIntentRecipeCatalog.collectPrefetchEngineSteps(recipe).isEmpty() &&
        prefetchRan)) {
      matchedTelExtra.toolsLoopFastPath = Boolean.TRUE
    }
    result.recipeExecutionPlan = execPlan
    String inquiryHint = ''
    String rr = (routerReason ?: '').toString()
    if ('open_page_inquiry'.equals(rid) || rr.contains('open_page_inquiry')) {
      inquiryHint =
        '[Studio — open page inquiry (read-only): Answer what this anchored page is about using prefetch/GetContent XML. ' +
        'Summarize for the author in plain prose. Do **not** WriteContent, update_template, or read CSS/FTL unless they ask to change something.]\n\n'
    }
    String externalHint = ''
    if (rr.contains('external_content') ||
      AiOrchestration.authorNeedsExternalContentForRouting(userTextAfterGuard ?: '') ||
      AiOrchestration.authorNeedsExternalContentForRouting(visible ?: '')) {
      externalHint =
        '[Studio — the author asked to **look up / fetch** text (e.g. song lyrics) and place it in a CMS field. ' +
        'Resolve the full lyrics or requested copy first (model knowledge or FetchHttpUrl). ' +
        'Do **not** write the instruction sentence, song title alone, or “lyrics of …” meta-text as the field value. ' +
        'Then GetContent → WriteContent the full resolved HTML/text on the anchored path.]\n\n'
    }
    result.userTextForToolsLoop = inquiryHint + prefetch + hotpathDirective + externalHint + prelude + (userTextAfterGuard ?: '')
    return attachTelemetry(ops, cfg, result, 'matched', matchedTelExtra)
  }

  /** Copies {@code routingEngineTelemetry} from the session bundle into recipe routing telemetry. */
  private static void putRoutingEngineTelemetryIfPresent(Map tel, Map toolsLoopSessionBundle) {
    if (!(tel instanceof Map) || !(toolsLoopSessionBundle instanceof Map)) {
      return
    }
    Object rt = toolsLoopSessionBundle.routingEngineTelemetry
    if (rt instanceof Map && !((Map) rt).isEmpty()) {
      tel.put('routingEngineTelemetry', rt)
    }
  }

  /** Loads merged bundled + site intent recipes. */
  static List<Map> loadRecipes(StudioToolOperations ops, Map cfg) {
    return AuthoringIntentRecipeCatalog.loadRecipes(ops, cfg)
  }

  /** Finds a recipe by {@code id} in the catalog list. */
  static Map findRecipeById(List recipes, String recipeId) {
    return AuthoringIntentRecipeCatalog.findRecipeById(recipes, recipeId)
  }

  /**
   * Prepends turn-goal block to tools-loop input and stores goal on the session bundle and route telemetry.
   *
   * @param result route outcome map (mutated by {@link AuthoringTurnGoal#wireIntoRouteResult})
   * @param toolsLoopSessionBundle session bundle for the tools loop
   * @param author match-pass context ({@code cand}, {@code routerVisible})
   * @param activePass classifier pass result ({@code turnGoal}, {@code routerDecision}, etc.)
   */
  private static void wireAuthorTurnGoal(Map result, Map toolsLoopSessionBundle, Map author, Map activePass) {
    if (!(result instanceof Map) || !(author instanceof Map)) {
      return
    }
    String anchor = AuthoringPreviewContext.extractAnchoredRepositoryPath(author.cand?.toString() ?: '')?.trim() ?: ''
    String authorVisible = author.routerVisible?.toString() ?: ''
    Map decision = activePass?.routerDecision instanceof Map ?
      new LinkedHashMap((Map) activePass.routerDecision) :
      [:]
    if (activePass?.turnGoal) {
      decision.turnGoal = activePass.turnGoal
    }
    if (activePass?.successCriteria) {
      decision.successCriteria = activePass.successCriteria
    }
    Map resolved = AuthoringTurnGoal.resolveFromRouterDecision(
      decision,
      authorVisible,
      anchor,
      activePass?.recipeId?.toString() ?: activePass?.routerRecipeId?.toString(),
      activePass?.routingMode?.toString()
    )
    String turnGoal = resolved.turnGoal?.toString()?.trim() ?: ''
    String successCriteria = resolved.successCriteria?.toString()?.trim() ?: ''
    if (toolsLoopSessionBundle instanceof Map) {
      if (resolved.authorUnderstanding?.toString()?.trim()) {
        toolsLoopSessionBundle.routerAuthorUnderstanding = resolved.authorUnderstanding.toString().trim()
      }
      if (resolved.sessionObjective?.toString()?.trim()) {
        toolsLoopSessionBundle.authorSessionObjective = resolved.sessionObjective.toString().trim()
      }
      if (resolved.turnRelation?.toString()?.trim()) {
        toolsLoopSessionBundle.routerTurnRelation = resolved.turnRelation.toString().trim()
      }
      if (resolved.deliverable?.toString()?.trim()) {
        toolsLoopSessionBundle.routerDeliverable = resolved.deliverable.toString().trim()
      }
    }
    if (activePass instanceof Map) {
      activePass.turnGoal = turnGoal
      activePass.successCriteria = successCriteria
    }
    AuthoringTurnGoal.wireIntoRouteResult(
      result,
      toolsLoopSessionBundle,
      turnGoal,
      successCriteria,
      anchor,
      authorVisible,
      activePass?.routingMode?.toString()?.trim() ?: '',
      activePass?.routerReason?.toString()?.trim() ?: decision.reason?.toString()?.trim() ?: ''
    )
  }

  /**
   * Copies author intent card + turn goal from the session bundle onto {@code intentRecipeRoutingTelemetry}
   * after {@link #attachTelemetry} creates the telemetry map.
   */
  private static void mergeAuthorTurnGoalFromBundle(Map result, Map toolsLoopSessionBundle) {
    if (!(result instanceof Map) || !(toolsLoopSessionBundle instanceof Map)) {
      return
    }
    if (!(result.intentRecipeRoutingTelemetry instanceof Map)) {
      return
    }
    Map tel = (Map) result.intentRecipeRoutingTelemetry
    String routingMode = tel.routingMode?.toString()?.trim() ?: ''
    String routerReason = tel.routerReason?.toString()?.trim() ?: ''
    String card = toolsLoopSessionBundle.authorIntentCardMarkdown?.toString()?.trim()
    if ('chat_only'.equals(routingMode) && card?.contains('Proceeding with tools')) {
      String goal = toolsLoopSessionBundle.authorTurnGoal?.toString()?.trim() ?:
        tel.turnGoal?.toString()?.trim() ?: ''
      String criteria = toolsLoopSessionBundle.authorTurnSuccessCriteria?.toString()?.trim() ?:
        tel.successCriteria?.toString()?.trim() ?: ''
      String anchor = toolsLoopSessionBundle.authorTurnAnchorPath?.toString()?.trim() ?: ''
      String authorVisible = toolsLoopSessionBundle.authorIntentCardAuthorVisible?.toString()?.trim() ?: ''
      card = AuthoringIntentCard.formatCardMarkdown(
        goal,
        criteria,
        anchor,
        authorVisible,
        tel.recipeId?.toString()?.trim() ?: '',
        routingMode,
        routerReason,
        toolsLoopSessionBundle.routerAuthorUnderstanding?.toString()?.trim() ?: ''
      )?.trim() ?: card
    }
    if (card) {
      tel.intentCardMarkdown = card
    }
    String goal = toolsLoopSessionBundle.authorTurnGoal?.toString()?.trim()
    if (goal) {
      tel.turnGoal = goal
    }
    String crit = toolsLoopSessionBundle.authorTurnSuccessCriteria?.toString()?.trim()
    if (crit) {
      tel.successCriteria = crit
    }
    String ar = toolsLoopSessionBundle.authorIntentCardAuthorVisible?.toString()?.trim()
    if (ar) {
      tel.authorRequestText = ar
    }
    putDeliverableTelemetryIfPresent(tel, [
      routerDeliverable        : toolsLoopSessionBundle.routerDeliverable,
      routerTurnRelation       : toolsLoopSessionBundle.routerTurnRelation,
      routerSessionObjective   : toolsLoopSessionBundle.authorSessionObjective,
      routerAuthorUnderstanding: toolsLoopSessionBundle.routerAuthorUnderstanding
    ])
  }

  /** Copies deliverable interpretation fields onto routing telemetry when present. */
  private static void putDeliverableTelemetryIfPresent(Map tel, Map source) {
    if (!(tel instanceof Map) || !(source instanceof Map)) {
      return
    }
    String deliverable = source.routerDeliverable?.toString()?.trim() ?:
      source.deliverable?.toString()?.trim() ?: ''
    String relation = source.routerTurnRelation?.toString()?.trim() ?:
      source.turnRelation?.toString()?.trim() ?: ''
    String objective = source.routerSessionObjective?.toString()?.trim() ?:
      source.sessionObjective?.toString()?.trim() ?: ''
    String understanding = source.routerAuthorUnderstanding?.toString()?.trim() ?:
      source.authorUnderstanding?.toString()?.trim() ?: ''
    if (deliverable) {
      tel.deliverable = deliverable
    }
    if (relation) {
      tel.turnRelation = relation
    }
    if (objective) {
      tel.sessionObjective = objective
    }
    if (understanding) {
      tel.authorUnderstanding = understanding
    }
  }
}
