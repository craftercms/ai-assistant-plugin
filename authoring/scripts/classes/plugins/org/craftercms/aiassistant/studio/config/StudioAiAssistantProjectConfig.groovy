package plugins.org.craftercms.aiassistant.studio.config

import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import plugins.org.craftercms.aiassistant.engine.routing.AuthoringIntentRecipeEngine

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.Set

/**
 * Optional site project policy for built-in tools: {@code config/studio/scripts/aiassistant/config/tools.json}
 * (Studio module path {@link #TOOLS_JSON_PATH}).
 * <p>
 * JSON shape (all keys optional):
 * <pre>{@code
 * {
 *   "disabledBuiltInTools": ["GenerateImage", "FetchHttpUrl"],
 *   "enabledBuiltInTools": ["GetContent", "WriteContent"],
 *   "mcpEnabled": true,
 *   "mcpServers": [
 *     { "id": "docs", "url": "https://mcp.example.com/mcp", "headers": { "Authorization": "Bearer ${env:GITHUB_MCP_TOKEN}" }, "readTimeoutMs": 120000 }
 *   ],
 *   "disabledMcpTools": ["mcp_docs_search"],
 *   "builtInToolSettings": {
 *     "SerpApiWebSearch": {
 *       "defaults": { "engine": "google", "googleDomain": "google.com", "gl": "us", "hl": "en", "num": 10 }
 *     }
 *   },
 *   "intentRecipeRouting": { ... },
 *   "pluginRag": {
 *     "mode": "off",
 *     "kernelMaxChars": 5200,
 *     "topK": 8,
 *     "maxAppendChars": 14000,
 *     "maxChunkChars": 1800,
 *     "maxChunks": 400,
 *     "embedBatchSize": 64
 *   },
 *   "agentSkillsRag": {
 *     "maxSkills": 12,
 *     "embeddingModel": "text-embedding-3-small",
 *     "maxChunks": 400,
 *     "maxChunkChars": 1800
 *   }
 * }
 * }</pre>
 * <strong>MCP client:</strong> {@code mcpServers} is ignored unless {@code mcpEnabled} is JSON boolean {@code true} (default is <strong>off</strong> when omitted).
 * When {@code enabledBuiltInTools} is a <strong>non-empty</strong> array, it acts as a <strong>whitelist</strong> of
 * built-in tool names to keep (site {@code InvokeSiteUserTool} is still added when {@code user-tools/registry.json}
 * has entries). <strong>MCP tools</strong> ({@code mcp_<serverId>_<toolName>}) and {@code InvokeSiteUserTool} are
 * <strong>not</strong> removed by that whitelist. When omitted or empty, all built-in tools ship minus any names listed in {@code disabledBuiltInTools}.
 * </p>
 */
final class StudioAiAssistantProjectConfig {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiAssistantProjectConfig.class)

  /** Studio {@code studio} module path (same prefix as other aiassistant site scripts). */
  static final String TOOLS_JSON_PATH = '/scripts/aiassistant/config/tools.json'

  /**
   * Private constructor; not for direct use.
   */
private StudioAiAssistantProjectConfig() {}

  /**
   * Loads {@link #TOOLS_JSON_PATH} for the POST-body working site first; when empty and working site
   * differs from the Studio session site (cross-site chat), falls back to the session site sandbox.
   */
  static Map load(StudioToolOperations ops) {
    if (ops == null) {
      return Collections.emptyMap()
    }
    String workingSite = ops.resolveEffectiveSiteId('')?.toString()?.trim() ?: ''
    Map cfg = loadFromSite(ops, workingSite)
    if (!cfg.isEmpty()) {
      return cfg
    }
    String sessionSite = ops.resolveStudioSessionSiteId()?.toString()?.trim() ?: ''
    if (sessionSite && !sessionSite.equalsIgnoreCase(workingSite)) {
      return loadFromSite(ops, sessionSite)
    }
    return cfg
  }

  /**
   * Loads from site from configuration or input.
   * @param ops Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @return Map payload for tools or orchestration.
   */
  private static Map loadFromSite(StudioToolOperations ops, String siteId) {
    if (ops == null || !siteId?.trim()) {
      return Collections.emptyMap()
    }
    String raw = null
    try {
      raw = ops.readStudioConfigurationUtf8(siteId.trim(), TOOLS_JSON_PATH)
    } catch (Throwable t) {
      LOG.debug('StudioAiAssistantProjectConfig: read failed siteId={}: {}', siteId, t.message)
      return Collections.emptyMap()
    }
    if (raw == null || !raw.toString().trim()) {
      return Collections.emptyMap()
    }
    try {
      Object parsed = new JsonSlurper().parseText(raw.toString().trim())
      if (parsed instanceof Map) {
        return (Map) parsed
      }
    } catch (Throwable t) {
      LOG.warn('StudioAiAssistantProjectConfig: invalid JSON at {} siteId={}: {}', TOOLS_JSON_PATH, siteId, t.message)
    }
    return Collections.emptyMap()
  }

  /** Non-empty whitelist of tool callback names to retain; {@code null} = use full built-in set minus disabled. */
  static Set<String> enabledBuiltInWhitelist(Map cfg) {
    if (!(cfg instanceof Map)) {
      return null
    }
    Object raw = cfg.get('enabledBuiltInTools')
    if (!(raw instanceof List) || ((List) raw).isEmpty()) {
      return null
    }
    Set<String> out = new LinkedHashSet<>()
    for (Object o : (List) raw) {
      String n = o != null ? o.toString().trim() : ''
      if (n) {
        out.add(n)
      }
    }
    return out.isEmpty() ? null : out
  }

  /**
   * Disabled built in set.
   * @param cfg Caller-supplied input.
   * @return Set<String> result.
   */
  static Set<String> disabledBuiltInSet(Map cfg) {
    if (!(cfg instanceof Map)) {
      return Collections.emptySet()
    }
    Object raw = cfg.get('disabledBuiltInTools')
    if (!(raw instanceof List)) {
      return Collections.emptySet()
    }
    Set<String> out = new LinkedHashSet<>()
    for (Object o : (List) raw) {
      String n = o != null ? o.toString().trim() : ''
      if (n) {
        out.add(n.toLowerCase(Locale.ROOT))
      }
    }
    return out
  }

  /**
   * True when tool name disabled.
   * @param toolName Caller-supplied input.
   * @param disabledLower Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean isToolNameDisabled(String toolName, Set<String> disabledLower) {
    if (toolName == null || disabledLower == null || disabledLower.isEmpty()) {
      return false
    }
    return disabledLower.contains(toolName.toString().trim().toLowerCase(Locale.ROOT))
  }

  /** Lowercase site user tool ids listed in {@code disabledUserTools}. */
  static Set<String> disabledUserToolsSet(Map cfg) {
    if (!(cfg instanceof Map)) {
      return Collections.emptySet()
    }
    Object raw = cfg.get('disabledUserTools')
    if (!(raw instanceof List)) {
      return Collections.emptySet()
    }
    Set<String> out = new LinkedHashSet<>()
    for (Object o : (List) raw) {
      String n = o != null ? o.toString().trim().toLowerCase(Locale.ROOT) : ''
      if (n) {
        out.add(n)
      }
    }
    return out
  }

  /**
   * True when user tool disabled.
   * @param toolId Identifier for the target resource.
   * @param disabledLower Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean isUserToolDisabled(String toolId, Set<String> disabledLower) {
    if (toolId == null || disabledLower == null || disabledLower.isEmpty()) {
      return false
    }
    return disabledLower.contains(toolId.toString().trim().toLowerCase(Locale.ROOT))
  }

  /**
   * When {@code true}, {@code mcpServers} in {@code tools.json} is processed. Omitted or any other value = MCP off.
   * <p>Site configuration only (not a JVM env var).</p>
   */
  static boolean mcpClientEnabled(Map cfg) {
    if (!(cfg instanceof Map)) {
      return false
    }
    return Boolean.TRUE.equals(cfg.get('mcpEnabled'))
  }

  /** Optional MCP Streamable HTTP servers from {@code mcpServers} on {@code tools.json} (only when {@link #mcpClientEnabled}). */
  static List<Map> mcpServers(Map cfg) {
    if (!(cfg instanceof Map) || !mcpClientEnabled(cfg)) {
      return Collections.emptyList()
    }
    Object raw = cfg.get('mcpServers')
    if (!(raw instanceof List)) {
      return Collections.emptyList()
    }
    List<Map> out = new ArrayList<>()
    for (Object o : (List) raw) {
      if (o instanceof Map) {
        out.add((Map) o)
      }
    }
    return out
  }

  /** Lowercase wire names (e.g. {@code mcp_docs_search}) listed in {@code disabledMcpTools}. */
  static Set<String> disabledMcpToolsLower(Map cfg) {
    if (!(cfg instanceof Map)) {
      return Collections.emptySet()
    }
    Object raw = cfg.get('disabledMcpTools')
    if (!(raw instanceof List)) {
      return Collections.emptySet()
    }
    Set<String> out = new LinkedHashSet<>()
    for (Object o : (List) raw) {
      String n = o != null ? o.toString().trim().toLowerCase(Locale.ROOT) : ''
      if (n) {
        out.add(n)
      }
    }
    return out
  }

  /**
   * True when mcp wire tool disabled.
   * @param disabledLower Caller-supplied input.
   * @param wireName Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean isMcpWireToolDisabled(Set<String> disabledLower, String wireName) {
    if (wireName == null || disabledLower == null || disabledLower.isEmpty()) {
      return false
    }
    return disabledLower.contains(wireName.toString().trim().toLowerCase(Locale.ROOT))
  }

  /** Optional {@code intentRecipeRouting} object from {@code tools.json}. */
  static Map intentRecipeRoutingSection(Map cfg) {
    if (!(cfg instanceof Map)) {
      return Collections.emptyMap()
    }
    Object o = cfg.get('intentRecipeRouting')
    return o instanceof Map ? (Map) o : Collections.emptyMap()
  }

  /**
   * Intent recipe routing enabled.
   * @param cfg Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean intentRecipeRoutingEnabled(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    if (!m.containsKey('enabled')) {
      return true
    }
    Boolean.TRUE.equals(m.get('enabled'))
  }

  /**
   * When {@code true}: apply {@link plugins.org.craftercms.aiassistant.engine.context.AuthoringPreviewContext#intentRecipeRouterEligibilitySkipReason}
   * before recipe match (short-message / no-CMS-signal / long-paste gates). When {@code false} (default when omitted): every
   * non-empty turn runs intent recipe routing so custom recipes and multi-intent requests are not blocked early.
   */
  static boolean intentRecipeEligibilityGateEnabled(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    if (!m.containsKey('eligibilityGateEnabled')) {
      return false
    }
    Boolean.TRUE.equals(m.get('eligibilityGateEnabled'))
  }

  /**
   * Intent recipe request clarification on unmatched.
   * @param cfg Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean intentRecipeRequestClarificationOnUnmatched(Map cfg) {
    Boolean.TRUE.equals(intentRecipeRoutingSection(cfg).get('requestClarificationOnUnmatched'))
  }

  /**
   * When {@code true}: after clarify/enrich, zero deterministic matches may use the JSON whole-turn recipe router LLM.
   * Default {@code false} — unmatched turns defer to the tools loop **## Plan** (multi-intent / ambiguous whole-turn).
   */
  static boolean intentRecipeWholeTurnJsonRouterEnabled(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    if (!m.containsKey('wholeTurnJsonRouterEnabled')) {
      return false
    }
    Boolean.TRUE.equals(m.get('wholeTurnJsonRouterEnabled'))
  }

  /**
   * When {@code true} (default when omitted): after clarify/enrich with zero deterministic matches, run the JSON
   * recipe router whenever the wire includes a {@code [Prior conversation …]} block — classifier reads the
   * recipe catalog + prior turns + current message (no client phrase gates).
   */
  static boolean intentRecipeLlmRouterWhenPriorConversation(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    if (!m.containsKey('llmRouterWhenPriorConversation')) {
      return true
    }
    Boolean.TRUE.equals(m.get('llmRouterWhenPriorConversation'))
  }

  /**
   * When {@code true} (default when omitted): clarify/enrich, expansion rematch, JSON router, and plan-defer probe may
   * run a bounded native-tool loop before their final formatted answer.
   */
  static boolean intentRecipeRefineToolsEnabled(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    if (!m.containsKey('refineToolsEnabled')) {
      return true
    }
    Boolean.TRUE.equals(m.get('refineToolsEnabled'))
  }

  /** Max tool rounds per routing refine step (clamped {@code 0–4}, default {@code 2}; {@code 0} = prose-only). */
  static int intentRecipeRefineMaxToolRounds(Map cfg) {
    return intentRecipeRoutingInt(cfg, 'refineMaxToolRounds', 2, 0, 4)
  }

  /**
   * Intent recipe min confidence.
   * @param cfg Caller-supplied input.
   * @return double result.
   */
  static double intentRecipeMinConfidence(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    Object v = m.get('minConfidence')
    if (v == null) {
      return 0.55d
    }
    try {
      double conf
      if (v instanceof Number) {
        conf = ((Number) v).doubleValue()
      } else {
        conf = Double.parseDouble(v.toString().trim())
      }
      if (conf < 0.0d) {
        return 0.0d
      }
      if (conf > 1.0d) {
        return 1.0d
      }
      return conf
    } catch (Throwable ignored) {
      return 0.55d
    }
  }

  /** Optional Studio module path to site recipe JSON (merged over bundled defaults by recipe {@code id}). */
  static String intentRecipeCustomRecipesPath(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    String p = m.get('customRecipesPath')?.toString()?.trim()
    return p ?: ''
  }

  /**
   * When {@code true} (default when omitted): evaluate site {@code registry.json} tool {@code matchHints} in the same
   * intent-routing passes as recipe hints (competition → defer to plan; no JVM tool execution).
   */
  static boolean intentRecipeSiteToolRoutingEnabled(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    if (!m.containsKey('siteToolRoutingEnabled')) {
      return intentRecipeRoutingEnabled(cfg)
    }
    Boolean.TRUE.equals(m.get('siteToolRoutingEnabled'))
  }

  /**
   * When {@code true} (default when omitted): run catalog {@code routingEngineSteps} at routing passes (initial,
   * after refine, before router) using the same JVM tool model as recipe {@code engineSteps}.
   */
  static boolean intentRecipeRoutingEngineEnabled(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    if (!m.containsKey('routingEngineEnabled')) {
      return intentRecipeEngineEnabled(cfg)
    }
    Boolean.TRUE.equals(m.get('routingEngineEnabled'))
  }

  /**
   * When {@code intentRecipeRouting.enabled} is true and a recipe matches: run {@code engineSteps} on the Studio JVM
   * before the main tools loop (see {@code AuthoringIntentRecipeEngine}). Default {@code true} when omitted.
   */
  static boolean intentRecipeEngineEnabled(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    if (!m.containsKey('engineEnabled')) {
      return true
    }
    Boolean.TRUE.equals(m.get('engineEnabled'))
  }

  /** Max deterministic steps per matched recipe (clamped {@code 1–32}, default {@code 8}). */
  static int intentRecipeEngineMaxSteps(Map cfg) {
    return intentRecipeRoutingInt(cfg, 'engineMaxSteps', 8, 1, 32)
  }

  /** Max characters for the entire prefetch block appended to the user message (clamped {@code 8_192–400_000}, default {@code 200_000}). */
  static int intentRecipeEngineMaxTotalChars(Map cfg) {
    return intentRecipeRoutingInt(cfg, 'engineMaxTotalChars', 200_000, 8192, 400_000)
  }

  /** Max characters retained per tool payload field such as {@code contentXml} / {@code formDefinitionXml} (default {@code 120_000}). */
  static int intentRecipeEngineMaxFieldChars(Map cfg) {
    return intentRecipeRoutingInt(cfg, 'engineMaxFieldChars', 120_000, 4096, 500_000)
  }

  /** When {@code false}, skips {@code llmRefine} confirmation steps (default {@code true}). */
  static boolean intentRecipeConfirmationLlmRefineEnabled(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    if (m.containsKey('confirmationLlmRefineEnabled')) {
      return Boolean.TRUE.equals(m.get('confirmationLlmRefineEnabled'))
    }
    if (m.containsKey('confirmationPitchRefineEnabled')) {
      return Boolean.TRUE.equals(m.get('confirmationPitchRefineEnabled'))
    }
    return true
  }

  /**
   * @deprecated use {@link #intentRecipeConfirmationLlmRefineEnabled}
   */
  @Deprecated
  static boolean intentRecipeConfirmationPitchRefineEnabled(Map cfg) {
    return intentRecipeConfirmationLlmRefineEnabled(cfg)
  }

  /** Max output tokens for {@link plugins.org.craftercms.aiassistant.engine.routing.AuthoringIntentRecipeLlmRefiner}. */
  static int intentRecipeConfirmationLlmRefineMaxOutTokens(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    String key = m.containsKey('confirmationLlmRefineMaxOutTokens') ?
      'confirmationLlmRefineMaxOutTokens' :
      'confirmationPitchRefineMaxOutTokens'
    return intentRecipeRoutingInt(cfg, key, 2800, 512, 8192)
  }

  /**
   * @deprecated use {@link #intentRecipeConfirmationLlmRefineMaxOutTokens}
   */
  @Deprecated
  static int intentRecipeConfirmationPitchRefineMaxOutTokens(Map cfg) {
    return intentRecipeConfirmationLlmRefineMaxOutTokens(cfg)
  }

  /** Read timeout (ms) for confirmation {@code llmRefine} completion. */
  static int intentRecipeConfirmationLlmRefineReadTimeoutMs(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    String key = m.containsKey('confirmationLlmRefineReadTimeoutMs') ?
      'confirmationLlmRefineReadTimeoutMs' :
      'confirmationPitchRefineReadTimeoutMs'
    return intentRecipeRoutingInt(cfg, key, 120_000, 30_000, 600_000)
  }

  /**
   * @deprecated use {@link #intentRecipeConfirmationLlmRefineReadTimeoutMs}
   */
  @Deprecated
  static int intentRecipeConfirmationPitchRefineReadTimeoutMs(Map cfg) {
    return intentRecipeConfirmationLlmRefineReadTimeoutMs(cfg)
  }

  /** @see plugins.org.craftercms.aiassistant.contrib.tool.builtin.integrations.SerpApiWebSearchProjectSettings#WIRE */
  static final String SERP_API_WEB_SEARCH_WIRE =
    plugins.org.craftercms.aiassistant.contrib.tool.builtin.integrations.SerpApiWebSearchProjectSettings.WIRE

  private static final Set<String> WEB_SEARCH_WIRE_NAMES =
    Collections.unmodifiableSet(new LinkedHashSet<>(['WebSearch', SERP_API_WEB_SEARCH_WIRE]))

  /**
   * True when web search wire name.
   * @param wireName Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean isWebSearchWireName(String wireName) {
    String w = (wireName ?: '').toString().trim()
    if (!w) {
      return false
    }
    for (String known : WEB_SEARCH_WIRE_NAMES) {
      if (known.equalsIgnoreCase(w)) {
        return true
      }
    }
    return false
  }

  /** Per-tool block under {@code builtInToolSettings.<wireName>} in {@code tools.json}. */
  static Map builtInToolSettingsForWire(Map cfg, String wireName) {
    if (!(cfg instanceof Map) || !(wireName?.trim())) {
      return Collections.emptyMap()
    }
    Object section = cfg.get('builtInToolSettings')
    if (!(section instanceof Map)) {
      return Collections.emptyMap()
    }
    Object tool = ((Map) section).get(wireName.trim())
    return tool instanceof Map ? (Map) tool : Collections.emptyMap()
  }

  /**
   * True when built in wire allowed by whitelist.
   * @param wireName Caller-supplied input.
   * @param cfg Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean isBuiltInWireAllowedByWhitelist(String wireName, Map cfg) {
    Set<String> wl = enabledBuiltInWhitelist(cfg)
    if (wl == null) {
      return true
    }
    String w = (wireName ?: '').toString().trim()
    if (!w) {
      return false
    }
    for (String n : wl) {
      if (w.equalsIgnoreCase(n?.toString()?.trim())) {
        return true
      }
    }
    return 'ListContentDependencyScope'.equals(w) && wl.contains('ListContentTranslationScope')
  }

  /**
   * Intent recipe routing int.
   * @param cfg Caller-supplied input.
   * @param key Caller-supplied input.
   * @param defaultValue Caller-supplied input.
   * @param min Caller-supplied input.
   * @param max Caller-supplied input.
   * @return int result.
   */
  private static int intentRecipeRoutingInt(Map cfg, String key, int defaultValue, int min, int max) {
    Map m = intentRecipeRoutingSection(cfg)
    return sectionInt(m, key, defaultValue, min, max)
  }

  /**
   * Plugin rag section.
   * @param cfg Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  static Map pluginRagSection(Map cfg) {
    if (!(cfg instanceof Map)) {
      return Collections.emptyMap()
    }
    Object s = cfg.get('pluginRag')
    return s instanceof Map ? (Map) s : Collections.emptyMap()
  }

  /**
   * Agent skills rag section.
   * @param cfg Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  static Map agentSkillsRagSection(Map cfg) {
    if (!(cfg instanceof Map)) {
      return Collections.emptyMap()
    }
    Object s = cfg.get('agentSkillsRag')
    return s instanceof Map ? (Map) s : Collections.emptyMap()
  }

  /** {@code off} (default), {@code supplement}, or {@code replace} — bundled plugin instruction RAG. */
  static String pluginRagMode(Map cfg) {
    Map m = pluginRagSection(cfg)
    if (!m.containsKey('mode')) {
      return 'off'
    }
    String raw = m.get('mode')?.toString()?.trim()?.toLowerCase(Locale.US)
    if (raw == 'supplement' || raw == 'replace') {
      return raw
    }
    return 'off'
  }

  /**
   * Plugin rag mode active.
   * @param cfg Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean pluginRagModeActive(Map cfg) {
    String m = pluginRagMode(cfg)
    return m == 'supplement' || m == 'replace'
  }

  /**
   * Plugin rag kernel max chars.
   * @param cfg Caller-supplied input.
   * @return int result.
   */
  static int pluginRagKernelMaxChars(Map cfg) {
    return sectionInt(pluginRagSection(cfg), 'kernelMaxChars', 5200, 1024, 16_000)
  }

  /**
   * Plugin rag top k.
   * @param cfg Caller-supplied input.
   * @return int result.
   */
  static int pluginRagTopK(Map cfg) {
    return sectionInt(pluginRagSection(cfg), 'topK', 8, 1, 24)
  }

  /**
   * Plugin rag max append chars.
   * @param cfg Caller-supplied input.
   * @return int result.
   */
  static int pluginRagMaxAppendChars(Map cfg) {
    return sectionInt(pluginRagSection(cfg), 'maxAppendChars', 14_000, 2000, 80_000)
  }

  /**
   * Plugin rag max chunk chars.
   * @param cfg Caller-supplied input.
   * @return int result.
   */
  static int pluginRagMaxChunkChars(Map cfg) {
    return sectionInt(pluginRagSection(cfg), 'maxChunkChars', 1800, 512, 8000)
  }

  /**
   * Plugin rag max chunks.
   * @param cfg Caller-supplied input.
   * @return int result.
   */
  static int pluginRagMaxChunks(Map cfg) {
    return sectionInt(pluginRagSection(cfg), 'maxChunks', 400, 8, 2000)
  }

  /**
   * Plugin rag embed batch size.
   * @param cfg Caller-supplied input.
   * @return int result.
   */
  static int pluginRagEmbedBatchSize(Map cfg) {
    return sectionInt(pluginRagSection(cfg), 'embedBatchSize', 64, 8, 128)
  }

  /**
   * Agent skills rag max skills.
   * @param cfg Caller-supplied input.
   * @return int result.
   */
  static int agentSkillsRagMaxSkills(Map cfg) {
    return sectionInt(agentSkillsRagSection(cfg), 'maxSkills', 12, 1, 32)
  }

  /**
   * Agent skills rag embedding model.
   * @param cfg Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String agentSkillsRagEmbeddingModel(Map cfg) {
    Map m = agentSkillsRagSection(cfg)
    String p = m.get('embeddingModel')?.toString()?.trim()
    return p ?: 'text-embedding-3-small'
  }

  /**
   * Agent skills rag max chunks.
   * @param cfg Caller-supplied input.
   * @return int result.
   */
  static int agentSkillsRagMaxChunks(Map cfg) {
    return sectionInt(agentSkillsRagSection(cfg), 'maxChunks', 400, 8, 2000)
  }

  /**
   * Agent skills rag max chunk chars.
   * @param cfg Caller-supplied input.
   * @return int result.
   */
  static int agentSkillsRagMaxChunkChars(Map cfg) {
    return sectionInt(agentSkillsRagSection(cfg), 'maxChunkChars', 1800, 512, 8000)
  }

  /**
   * Section int.
   * @param section Caller-supplied input.
   * @param key Caller-supplied input.
   * @param defaultValue Caller-supplied input.
   * @param min Caller-supplied input.
   * @param max Caller-supplied input.
   * @return int result.
   */
  private static int sectionInt(Map section, String key, int defaultValue, int min, int max) {
    if (!(section instanceof Map)) {
      return defaultValue
    }
    Object v = section.get(key)
    if (v == null) {
      return defaultValue
    }
    try {
      int n = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim())
      return Math.max(min, Math.min(max, n))
    } catch (Throwable ignored) {
      return defaultValue
    }
  }
}
