package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.integrations

import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.studio.secrets.StudioAiAssistantSecretsService
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext

/**
 * Site {@code tools.json} block for {@link SerpApiWebSearchTool} ({@code builtInToolSettings.SerpApiWebSearch}).
 * API key is configured on Project Tools → Secrets ({@link #SECRET_KEY}), not in this block.
 */
final class SerpApiWebSearchProjectSettings {

  static final String WIRE = 'SerpApiWebSearch'

  /** Built-in Secrets catalog key (value on Project Tools → Secrets only). */
  static final String SECRET_KEY = 'serpapi_api_key'

  /**
   * Private constructor; not for direct use.
   */
private SerpApiWebSearchProjectSettings() {}

  /**
   * Secret key id.
   * @param cfg Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String secretKeyId(Map cfg) {
    return SECRET_KEY
  }

  /**
   * Default defaults.
   * @return Map payload for tools or orchestration.
   */
  static Map defaultDefaults() {
    return Collections.unmodifiableMap([
      engine      : 'google',
      googleDomain: 'google.com',
      gl          : 'us',
      hl          : 'en',
      location    : 'United States',
      num         : 10,
      device      : 'desktop',
      safe        : 'active'
    ])
  }

  /** Merges {@code builtInToolSettings.SerpApiWebSearch.defaults} over {@link #defaultDefaults()}. */
  static Map resolveDefaults(Map cfg) {
    Object raw = StudioAiAssistantProjectConfig.builtInToolSettingsForWire(cfg, WIRE).get('defaults')
    Map base = defaultDefaults()
    if (!(raw instanceof Map) || ((Map) raw).isEmpty()) {
      return base
    }
    Map merged = new LinkedHashMap<>(base)
    for (Map.Entry e : ((Map) raw).entrySet()) {
      if (e.value != null && e.value.toString().trim()) {
        merged.put(e.key.toString(), e.value)
      }
    }
    return merged
  }

  /** Author-facing hint when {@link SerpApiWebSearchTool} cannot resolve an API key. */
  static String missingApiKeyMessage(StudioAiToolContext ctx) {
    if (ctx?.ops == null) {
      return 'SerpAPI is not set up for this site. Add your API key under Project Tools → Secrets (SerpAPI), then try again.'
    }
    Map status = StudioAiAssistantSecretsService.secretResolutionStatus(ctx.ops, SECRET_KEY)
    if (!Boolean.TRUE.equals(status.configured)) {
      return 'SerpAPI is not set up for this site. Add your API key under Project Tools → Secrets (SerpAPI), then try again.'
    }
    String kind = status.storedKind?.toString() ?: ''
    if ('env' == kind) {
      String envVar = (status.envVar ?: '').toString().trim()
      if (!envVar) {
        return 'SerpAPI is set to use a server environment variable, but the variable name is missing in Secrets. ' +
          'Open Project Tools → Secrets and fix the SerpAPI entry.'
      }
      return "SerpAPI is set to use server environment variable ${envVar}, but it is not set on this Studio host. " +
        'Add the key under Project Tools → Secrets (SerpAPI), or set that variable on the server.'
    }
    if ('enc' == kind) {
      if (Boolean.TRUE.equals(status.unresolvedMacro)) {
        return 'SerpAPI is saved in Secrets as an encrypted value but could not be decrypted on this Studio host. ' +
          'Open Project Tools → Secrets, re-save the SerpAPI key, or verify Studio encryption matches this environment.'
      }
      return 'SerpAPI is listed in Secrets but did not resolve to a key. Open Project Tools → Secrets and check the SerpAPI entry.'
    }
    if ('secret_ref' == kind) {
      return 'SerpAPI Secrets entry references another secret that could not be resolved. Check Project Tools → Secrets.'
    }
    return 'SerpAPI is listed in Secrets but did not resolve to a key. Open Project Tools → Secrets and check the SerpAPI entry.'
  }

  /**
   * True when {@link #SECRET_KEY} expands to a usable API key on this Studio host (not empty / unresolved macro).
   */
  static boolean isApiKeyResolved(StudioToolOperations ops) {
    if (ops == null) {
      return false
    }
    String resolved = StudioAiAssistantSecretsService.resolveSecretKey(ops, SECRET_KEY)
    String r = (resolved ?: '').trim()
    return r.length() > 0 && !r.contains('${')
  }

  /**
   * When SerpAPI is configured and allowed by site {@code tools.json}, omit DuckDuckGo {@code WebSearch} from the wire.
   */
  static boolean shouldOmitWebSearchForSerpApi(StudioToolOperations ops, Map projectCfg) {
    if (!isApiKeyResolved(ops)) {
      return false
    }
    Map cfg = projectCfg instanceof Map ? projectCfg : [:]
    Set<String> disabled = StudioAiAssistantProjectConfig.disabledBuiltInSet(cfg)
    if (StudioAiAssistantProjectConfig.isToolNameDisabled(WIRE, disabled)) {
      return false
    }
    if (!StudioAiAssistantProjectConfig.isBuiltInWireAllowedByWhitelist(WIRE, cfg)) {
      return false
    }
    return true
  }

  /** Maps {@code WebSearch} → {@code SerpApiWebSearch} when DuckDuckGo is omitted in favor of SerpAPI. */
  static String rewriteWebSearchWireName(String wireName, StudioToolOperations ops, Map projectCfg) {
    String w = (wireName ?: '').trim()
    if (!'WebSearch'.equals(w)) {
      return w
    }
    return shouldOmitWebSearchForSerpApi(ops, projectCfg) ? WIRE : w
  }

  /**
   * Replaces {@code WebSearch} with {@code SerpApiWebSearch} in recipe allowlists when SerpAPI is preferred.
   */
  static Set<String> rewriteAllowlistForSerpApi(Set<String> allowNames, StudioToolOperations ops, Map projectCfg) {
    if (!(allowNames instanceof Set) || allowNames.isEmpty()) {
      return allowNames ?: new LinkedHashSet<>()
    }
    if (!shouldOmitWebSearchForSerpApi(ops, projectCfg)) {
      return allowNames
    }
    if (!allowNames.contains('WebSearch')) {
      return allowNames
    }
    Set<String> out = new LinkedHashSet<>(allowNames)
    out.remove('WebSearch')
    out.add(WIRE)
    return out
  }
}
