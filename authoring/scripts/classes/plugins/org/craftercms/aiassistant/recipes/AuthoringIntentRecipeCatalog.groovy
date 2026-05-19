package plugins.org.craftercms.aiassistant.recipes

import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads bundled + optional site **authoring intent recipes** for the intent-router pass.
 * <p>Default file ships next to this class on the classpath; sites may override via
 * {@link StudioAiAssistantProjectConfig#intentRecipeCustomRecipesPath}.</p>
 */
final class AuthoringIntentRecipeCatalog {

  private static final Logger log = LoggerFactory.getLogger(AuthoringIntentRecipeCatalog.class)

  private AuthoringIntentRecipeCatalog() {}

  private static final String BUNDLED_RELATIVE = 'authoring-intent-recipes-default.json'

  /** Classpath path under {@code scripts/classes} when the file is deployed beside Groovy sources. */
  private static final String PACKAGE_RESOURCE_PREFIX = 'plugins/org/craftercms/aiassistant/recipes/'

  /** Optional JVM override: absolute path to bundled recipes JSON (hotfix without redeploy). */
  private static final String SYSPROP_BUNDLED_PATH = 'aiassistant.authoringIntentRecipesDefault.path'

  /** Site sandbox path after {@code install-plugin.sh} copies {@code authoring/scripts/classes}. */
  private static final String BUNDLED_SANDBOX_REPO_PATH =
    "/config/studio/scripts/classes/${PACKAGE_RESOURCE_PREFIX}${BUNDLED_RELATIVE}"

  /** Key for bundled catalog {@code chatDefaults} (not a real site id). */
  static final String BUNDLED_CHAT_DEFAULTS_SITE_KEY = '__bundled__'

  private static final ConcurrentHashMap<String, Map<String, String>> CATALOG_CHAT_DEFAULTS_BY_SITE =
    new ConcurrentHashMap<>()

  /** Built-in emoji / line suffix defaults before any catalog JSON is loaded. */
  private static Map<String, String> defaultCatalogChatDefaults() {
    Map d = new LinkedHashMap<>()
    d.put('prefixEmoji', '🥗')
    d.put('fallbackEmoji', '📋')
    d.put('lineSuffix', 'workflow')
    return Collections.unmodifiableMap(d)
  }

  static {
    CATALOG_CHAT_DEFAULTS_BY_SITE.put(BUNDLED_CHAT_DEFAULTS_SITE_KEY, defaultCatalogChatDefaults())
  }

  /**
   * @param siteId Crafter site id when known; otherwise bundled defaults apply
   */
  static Map<String, String> catalogChatDefaults(String siteId = null) {
    String key = normalizeChatDefaultsSiteKey(siteId)
    Map<String, String> perSite = CATALOG_CHAT_DEFAULTS_BY_SITE.get(key)

    if (perSite != null) {
      return perSite
    }

    Map<String, String> bundled = CATALOG_CHAT_DEFAULTS_BY_SITE.get(BUNDLED_CHAT_DEFAULTS_SITE_KEY)
    return bundled != null ? bundled : defaultCatalogChatDefaults()
  }

  /** Maps blank site id to the bundled {@code __bundled__} chat-defaults cache key. */
  private static String normalizeChatDefaultsSiteKey(String siteId) {
    String s = siteId?.toString()?.trim()
    return s ? s : BUNDLED_CHAT_DEFAULTS_SITE_KEY
  }

  /**
   * Installs {@code chatDefaults} for a site (or bundled key when {@code siteId} is blank).
   */
  static void installCatalogChatDefaults(Map<String, String> chatDefaults, String siteId = null) {
    if (!(chatDefaults instanceof Map) || chatDefaults.isEmpty()) {
      return
    }

    String key = normalizeChatDefaultsSiteKey(siteId)
    CATALOG_CHAT_DEFAULTS_BY_SITE.put(key, Collections.unmodifiableMap(new LinkedHashMap<>(chatDefaults)))
  }


  /**
   * @return immutable list of recipe maps (each may contain id, title, description, matchHints, phases)
   */
  static List<Map> loadRecipes(StudioToolOperations ops, Map projectCfg) {
    List<Map> merged = new ArrayList<>()
    Set<String> seen = new LinkedHashSet<>()
    for (Map r : parseBundledRecipes(ops)) {
      String id = r?.id?.toString()?.trim()

      if (!id) {
        continue
      }
      merged.add(new LinkedHashMap<>(r))
      seen.add(id)
    }

    List<String> catalogOrder = parseBundledRecipeOrder(ops)
    List<String> siteOrder = []
    // Site override is optional: bundled recipes (classpath / in-memory) are always loaded above.
    // readStudioConfigurationUtf8 probes contentExists first so a missing intent-recipes.json does not ERROR-log.
    String sitePath = StudioAiAssistantProjectConfig.intentRecipeCustomRecipesPath(projectCfg)

    if (ops != null && sitePath?.trim()) {
      try {
        String siteId = ops.resolveEffectiveSiteId('')
        String raw = ops.readStudioConfigurationUtf8(siteId, sitePath.trim())

        if (raw?.trim()) {
          siteOrder = parseRecipeOrderFromJsonText(raw)
          Map siteDoc = parseCatalogDocument(raw)

          if (siteDoc?.chatDefaults instanceof Map) {
            installCatalogChatDefaults((Map) siteDoc.chatDefaults, siteId)
          }

          for (Map r : (siteDoc?.recipes ?: []) as List<Map>) {
            String id = r?.id?.toString()?.trim()

            if (!id) {
              continue
            }

            if (seen.contains(id)) {
              for (int i = 0; i < merged.size(); i++) {
                if (id == merged.get(i)?.get('id')?.toString()?.trim()) {
                  merged.set(i, new LinkedHashMap<>(r))
                  break
                }
              }
            } else {
              merged.add(new LinkedHashMap<>(r))
              seen.add(id)
            }
          }
        }
      } catch (Throwable t) {
        log.warn('AuthoringIntentRecipeCatalog: site recipes read failed path={}: {}', sitePath, t.message)
      }
    }

    List<String> effectiveOrder = siteOrder.isEmpty() ? catalogOrder : siteOrder
    Collections.unmodifiableList(applyRecipeOrder(merged, effectiveOrder))
  }

  /**
   * Declarative read-only tool steps for intent-routing passes (bundled catalog + site recipe JSON + tools.json).
   * Same execution path as recipe {@code engineSteps} via {@link AuthoringIntentRoutingEngine}.
   */
  static List<Map> loadRoutingEngineSteps(StudioToolOperations ops, Map projectCfg) {
    List<Map> merged = new ArrayList<>()
    Map bundledDoc = parseCatalogDocument(loadBundledRecipesJsonText(ops))
    appendRoutingEngineStepsFromDoc(merged, bundledDoc)

    String sitePath = StudioAiAssistantProjectConfig.intentRecipeCustomRecipesPath(projectCfg)
    if (ops != null && sitePath?.trim()) {
      try {
        String siteId = ops.resolveEffectiveSiteId('')
        String raw = ops.readStudioConfigurationUtf8(siteId, sitePath.trim())
        if (raw?.trim()) {
          appendRoutingEngineStepsFromDoc(merged, parseCatalogDocument(raw))
        }
      } catch (Throwable t) {
        log.warn('AuthoringIntentRecipeCatalog: site routingEngineSteps read failed: {}', t.message)
      }
    }

    Map routingSec = StudioAiAssistantProjectConfig.intentRecipeRoutingSection(projectCfg) ?: [:]
    Object siteToolsSteps = routingSec.get('routingEngineSteps')
    if (siteToolsSteps instanceof List) {
      for (Object o : (List) siteToolsSteps) {
        if (o instanceof Map) {
          merged.add(new LinkedHashMap<>((Map) o))
        }
      }
    }
    return Collections.unmodifiableList(merged)
  }

  /** Appends {@code routingEngineSteps} from a parsed catalog document into {@code merged}. */
  private static void appendRoutingEngineStepsFromDoc(List<Map> merged, Map doc) {
    if (!(merged instanceof List) || !(doc instanceof Map)) {
      return
    }
    Object steps = doc.get('routingEngineSteps')
    if (!(steps instanceof List)) {
      return
    }
    for (Object o : (List) steps) {
      if (o instanceof Map) {
        merged.add(new LinkedHashMap<>((Map) o))
      }
    }
  }

  /**
   * Operator diagnostic: bundled catalog load, merged recipe count, chatDefaults keys, prefetch allowlist size.
   */
  static Map catalogHealthCheck(StudioToolOperations ops, Map projectCfg) {
    String bundledRaw = loadBundledRecipesJsonText(ops)
    boolean bundledLoaded = bundledRaw?.trim()?.length() > 0
    List<Map> bundledOnly = parseBundledRecipes(ops)
    List<Map> merged = (ops != null && projectCfg != null) ? loadRecipes(ops, projectCfg) : bundledOnly
    String siteId = ''

    if (ops != null) {
      try {
        siteId = ops.resolveEffectiveSiteId('')?.toString()?.trim() ?: ''
      } catch (Throwable ignored) {
      }
    }

    Map<String, String> chatDefs = catalogChatDefaults(siteId)
    Set<String> prefetchTools =
      plugins.org.craftercms.aiassistant.tools.catalog.StudioAiToolRegistry.recipeEngineReadOnlyWireNames()
    return [
      ok                 : bundledLoaded && !merged.isEmpty(),
      bundledJsonLoaded  : bundledLoaded,
      bundledRecipeCount : bundledOnly?.size() ?: 0,
      mergedRecipeCount  : merged?.size() ?: 0,
      siteId             : siteId,
      chatDefaultsKeys   : chatDefs ? new ArrayList<>(chatDefs.keySet()) : [],
      prefetchToolCount  : prefetchTools?.size() ?: 0,
      prefetchTools      : prefetchTools ? new ArrayList<>(prefetchTools) : [],
      coreToolCount      : plugins.org.craftercms.aiassistant.tools.catalog.StudioAiToolRegistry.coreTools()?.size() ?: 0
    ]
  }

  /** Reads {@code recipeOrder} from the bundled catalog JSON on the classpath or sandbox. */
  private static List<String> parseBundledRecipeOrder(StudioToolOperations ops = null) {
    return parseRecipeOrderFromJsonText(loadBundledRecipesJsonText(ops))
  }

  /** Parses the top-level {@code recipeOrder} string array from a catalog JSON document. */
  private static List<String> parseRecipeOrderFromJsonText(String raw) {
    if (!raw?.trim()) {
      return []
    }

    try {
      Object root = new JsonSlurper().parseText(raw.trim())

      if (!(root instanceof Map)) {
        return []
      }

      Object order = ((Map) root).get('recipeOrder')

      if (!(order instanceof List)) {
        return []
      }

      List<String> out = []

      for (Object o : (List) order) {
        String id = o?.toString()?.trim()

        if (id) {
          out.add(id)
        }
      }

      return out
    } catch (Throwable ignored) {
      return []
    }
  }

  /**
   * Reorders merged recipes per {@code recipeOrder}, then appends any recipes not listed
   * (stable relative order for unlisted ids).
   */
  private static List<Map> applyRecipeOrder(List<Map> merged, List<String> recipeOrder) {
    if (merged == null || merged.isEmpty() || recipeOrder == null || recipeOrder.isEmpty()) {
      return merged ?: []
    }

    Map<String, Map> byId = new LinkedHashMap<>()
    for (Map r : merged) {
      String id = r?.get('id')?.toString()?.trim()

      if (id) {
        byId.put(id, r)
      }
    }

    List<Map> out = new ArrayList<>()
    Set<String> seen = new LinkedHashSet<>()
    for (String id : recipeOrder) {
      Map r = byId.get(id)

      if (r != null) {
        out.add(r)
        seen.add(id)
      }
    }

    for (Map r : merged) {
      String id = r?.get('id')?.toString()?.trim()

      if (id && !seen.contains(id)) {
        out.add(r)
      }
    }
    out
  }

  /** Loads bundled catalog JSON, installs bundled {@code chatDefaults}, and returns the {@code recipes} array. */
  private static List<Map> parseBundledRecipes(StudioToolOperations ops = null) {
    String raw = loadBundledRecipesJsonText(ops)

    if (!raw?.trim()) {
      log.warn(
        'AuthoringIntentRecipeCatalog: missing bundled {} — no default recipes loaded (deploy {} under config/studio/scripts/classes/{}/ or set JVM {} to override)',
        BUNDLED_RELATIVE,
        BUNDLED_RELATIVE,
        PACKAGE_RESOURCE_PREFIX,
        SYSPROP_BUNDLED_PATH
      )
      return []
    }

    Map doc = parseCatalogDocument(raw)

    if (doc?.chatDefaults instanceof Map) {
      installCatalogChatDefaults((Map) doc.chatDefaults, BUNDLED_CHAT_DEFAULTS_SITE_KEY)
    }

    return doc?.recipes ?: []
  }

  /**
   * Canonical bundled catalog: {@link #BUNDLED_RELATIVE} next to this class (repo:
   * {@code authoring/scripts/classes/plugins/org/craftercms/aiassistant/recipes/authoring-intent-recipes-default.json}).
   * Studio deploys the whole {@code authoring/scripts/classes} tree; try classpath stream, resource URL, and code-source peer file.
   */
  private static String loadBundledRecipesJsonText(StudioToolOperations ops = null) {
    String override = System.getProperty(SYSPROP_BUNDLED_PATH)?.toString()?.trim()

    if (override) {
      try {
        File f = new File(override)

        if (f.isFile()) {
          return f.getText('UTF-8')
        }
        log.warn('{} set but not a file: {}', SYSPROP_BUNDLED_PATH, override)
      } catch (Throwable t) {
        log.warn('Failed reading bundled recipes from {}: {}', override, t.message)
      }
    }

    String pkgPath = "${PACKAGE_RESOURCE_PREFIX}${BUNDLED_RELATIVE}"
    String fromResource = readUtf8FromResourceUrl(AuthoringIntentRecipeCatalog.class.getResource(BUNDLED_RELATIVE))

    if (fromResource?.trim()) {
      return fromResource
    }

    fromResource = readUtf8FromResourceUrl(AuthoringIntentRecipeCatalog.class.getResource("/${pkgPath}"))

    if (fromResource?.trim()) {
      return fromResource
    }

    ClassLoader cl = AuthoringIntentRecipeCatalog.class.classLoader
    fromResource = readUtf8FromResourceUrl(cl?.getResource(pkgPath))

    if (fromResource?.trim()) {
      return fromResource
    }

    fromResource = readUtf8FromResourceUrl(Thread.currentThread().contextClassLoader?.getResource(pkgPath))

    if (fromResource?.trim()) {
      return fromResource
    }

    String fromStream = readUtf8FromResourceStream(AuthoringIntentRecipeCatalog.class.getResourceAsStream(BUNDLED_RELATIVE))

    if (fromStream?.trim()) {
      return fromStream
    }

    fromStream = readUtf8FromResourceStream(cl?.getResourceAsStream(pkgPath))

    if (fromStream?.trim()) {
      return fromStream
    }

    fromStream = readUtf8FromResourceStream(Thread.currentThread().contextClassLoader?.getResourceAsStream(pkgPath))

    if (fromStream?.trim()) {
      return fromStream
    }

    try {
      def loc = AuthoringIntentRecipeCatalog.class.protectionDomain?.codeSource?.location

      if (loc != null) {
        String fromCodeSource = readBundledJsonBesideCodeSource(loc)

        if (fromCodeSource?.trim()) {
          return fromCodeSource
        }
        File base = new File(loc.toURI())

        if (base.isFile()) {
          base = base.parentFile
        }

        if (base != null && base.isDirectory()) {
          for (String rel : [BUNDLED_RELATIVE, pkgPath]) {
            File candidate = new File(base, rel)

            if (candidate.isFile()) {
              return candidate.getText('UTF-8')
            }
          }
        }
      }
    } catch (Throwable t) {
      log.debug('AuthoringIntentRecipeCatalog: code-source directory load failed: {}', t.message)
    }

    String fromSandbox = loadBundledRecipesJsonFromSiteSandbox(ops)

    if (fromSandbox?.trim()) {
      log.debug(
        'AuthoringIntentRecipeCatalog: loaded bundled {} from site sandbox {} (classpath/code-source miss)',
        BUNDLED_RELATIVE,
        BUNDLED_SANDBOX_REPO_PATH
      )
      return fromSandbox
    }

    return ''
  }

  /**
   * Studio Groovy often loads this class from a classpath that does not include sibling JSON; the site sandbox
   * copy under {@link #BUNDLED_SANDBOX_REPO_PATH} is authoritative after {@code install-plugin.sh}.
   */
  private static String loadBundledRecipesJsonFromSiteSandbox(StudioToolOperations ops) {
    if (ops == null) {
      return ''
    }

    try {
      String siteId = ops.resolveEffectiveSiteId('')?.toString()?.trim()

      if (!siteId) {
        return ''
      }

      Map item = ops.getContent(siteId, BUNDLED_SANDBOX_REPO_PATH) as Map
      String raw = item?.contentXml?.toString()?.trim()
      return raw ?: ''
    } catch (Throwable t) {
      log.trace('AuthoringIntentRecipeCatalog: sandbox bundled JSON not readable: {}', t.message)
      return ''
    }
  }

  /** Peer {@link #BUNDLED_RELATIVE} next to this class code-source (handles Studio {@code file:/config/...} URLs). */
  private static String readBundledJsonBesideCodeSource(def codeSourceLocation) {
    if (codeSourceLocation == null) {
      return ''
    }

    try {
      URL codeUrl = (codeSourceLocation instanceof URL) ?
        (URL) codeSourceLocation :
        codeSourceLocation.toURI()?.toURL()
      if (codeUrl == null) {
        return ''
      }

      String path = codeUrl.path ?: ''

      if (!path) {
        return ''
      }

      int slash = path.lastIndexOf('/')
      String dir = slash >= 0 ? path.substring(0, slash + 1) : ''
      URL jsonUrl = new URL(codeUrl.protocol, codeUrl.host, codeUrl.port, "${dir}${BUNDLED_RELATIVE}")
      return readUtf8FromResourceUrl(jsonUrl)
    } catch (Throwable t) {
      log.debug('AuthoringIntentRecipeCatalog: code-source peer JSON load failed: {}', t.message)
      return ''
    }
  }

  /** Reads a classpath or file URL as UTF-8 text (file protocol uses direct file read when possible). */
  private static String readUtf8FromResourceUrl(URL url) {
    if (url == null) {
      return ''
    }

    try {
      if ('file'.equalsIgnoreCase(url.protocol)) {
        try {
          File f = new File(url.toURI())

          if (f.isFile()) {
            return f.getText('UTF-8')
          }
        } catch (Throwable ignored) {
        }

        return readUtf8FromResourceStream(url.openStream())
      }

      return readUtf8FromResourceStream(url.openStream())
    } catch (Throwable t) {
      log.debug('AuthoringIntentRecipeCatalog: resource URL read failed {}: {}', url, t.message)
      return ''
    }
  }

  /** Reads an input stream to a UTF-8 string and closes the stream. */
  private static String readUtf8FromResourceStream(InputStream is) {
    if (is == null) {
      return ''
    }

    try {
      return new String(is.readAllBytes(), StandardCharsets.UTF_8)
    } finally {
      try {
        is.close()
      } catch (Throwable ignored) {}
    }
  }

  /** Convenience: returns only the {@code recipes} list from {@link #parseCatalogDocument}. */
  static List<Map> parseRecipesArrayFromJsonText(String raw) {
    return parseCatalogDocument(raw)?.recipes ?: []
  }

  /**
   * @return {@code [recipes, chatDefaults, routingEngineSteps]} or {@code null}
   */
  static Map parseCatalogDocument(String raw) {
    if (!raw?.trim()) {
      return null
    }

    try {
      Object root = new JsonSlurper().parseText(raw.trim())

      if (!(root instanceof Map)) {
        return null
      }

      Map<String, String> chatDefaults = Collections.unmodifiableMap(mergeChatDefaults((Map) root))
      Object arr = ((Map) root).get('recipes')

      if (!(arr instanceof List)) {
        return [recipes: [], chatDefaults: chatDefaults, routingEngineSteps: []]
      }

      List<Map> out = []

      for (Object o : (List) arr) {
        if (o instanceof Map) {
          out.add(new LinkedHashMap<>((Map) o))
        }
      }

      List<Map> routingSteps = []
      Object routingRaw = ((Map) root).get('routingEngineSteps')
      if (routingRaw instanceof List) {
        for (Object o : (List) routingRaw) {
          if (o instanceof Map) {
            routingSteps.add(new LinkedHashMap<>((Map) o))
          }
        }
      }

      return [recipes: out, chatDefaults: chatDefaults, routingEngineSteps: routingSteps]
    } catch (Throwable t) {
      log.warn('AuthoringIntentRecipeCatalog: JSON parse failed: {}', t.message)
      return null
    }
  }

  /** Overlays catalog {@code chatDefaults} onto bundled defaults ({@code prefixEmoji}, {@code fallbackEmoji}, {@code lineSuffix}). */
  private static Map<String, String> mergeChatDefaults(Map root) {
    Map<String, String> d = new LinkedHashMap<>(defaultCatalogChatDefaults())
    Object cd = root?.get('chatDefaults')

    if (cd instanceof Map) {
      Map m = (Map) cd

      if (m.prefixEmoji != null) {
        d.put('prefixEmoji', m.prefixEmoji.toString())
      }

      if (m.fallbackEmoji != null) {
        d.put('fallbackEmoji', m.fallbackEmoji.toString())
      }

      if (m.lineSuffix != null) {
        d.put('lineSuffix', m.lineSuffix.toString())
      }
    }
    d
  }

  /**
   * All recipes whose {@code deterministicMatch} rules evaluate true for {@code ctx} (deduped by {@code recipeId},
   * highest priority per id). Rules come from recipe config ({@code when}, shorthands, {@code matchHints}) — not
   * recipe-named Java switches.
   */
  static List<Map> findDeterministicRecipeMatches(List<Map> recipes, Map ctx) {
    if (recipes == null || recipes.isEmpty() || !(ctx instanceof Map)) {
      return Collections.emptyList()
    }

    Map<String, Map> byId = new LinkedHashMap<>()
    String visible = deterministicRoutingPrompt(ctx)
    for (Map recipe : recipes) {
      if (!(recipe instanceof Map)) {
        continue
      }

      String rid = recipe.id?.toString()?.trim()

      if (!rid) {
        continue
      }

      for (Map entry : deterministicMatchEntries(recipe)) {
        if (!AuthoringIntentRecipeWhen.evaluateMatchEntry(entry, recipe, ctx)) {
          continue
        }

        int priority = entry.priority instanceof Number ? ((Number) entry.priority).intValue() : 0
        String reason = (entry.routerReason ?: "deterministic_${rid}").toString()
        Map match = [
          recipe      : recipe,
          recipeId    : rid,
          routerReason: reason,
          skipPrefetch: Boolean.TRUE.equals(entry.skipPrefetch),
          visible     : visible,
          priority    : priority
        ]

        Map existing = byId.get(rid)

        if (existing == null || priority > (existing.priority instanceof Number ?
          ((Number) existing.priority).intValue() : 0)) {
          byId.put(rid, match)
        }
      }
    }

    List<Map> out = new ArrayList<>(byId.values())
    out.sort { a, b ->
      int pa = a.priority instanceof Number ? ((Number) a.priority).intValue() : 0
      int pb = b.priority instanceof Number ? ((Number) b.priority).intValue() : 0
      pb <=> pa
    }

    return out
  }

  /**
   * Single deterministic match when exactly one recipe matches; {@code null} when none or ambiguous.
   * @return map with keys {@code recipe}, {@code recipeId}, {@code routerReason}, {@code skipPrefetch}, {@code visible}
   */
  static Map findDeterministicRecipeMatch(List<Map> recipes, Map ctx) {
    List<Map> matches = findDeterministicRecipeMatches(recipes, ctx)
    return matches.size() == 1 ? matches[0] : null
  }

  /**
   * Per-step deterministic recipe hits for plan orchestration (step {@code summary} as {@code routerVisible}).
   * @return list of maps with {@code stepId}, {@code summary}, {@code recipeId}, {@code routerReason}
   */
  static List<Map> matchRecipesForPlanSteps(List<Map> recipes, Map baseCtx, List<Map> planSteps) {
    if (recipes == null || recipes.isEmpty() || planSteps == null || planSteps.isEmpty()) {
      return Collections.emptyList()
    }

    Map base = baseCtx instanceof Map ? new LinkedHashMap<>((Map) baseCtx) : [:]

    List<Map> out = new ArrayList<>()
    for (Map step : planSteps) {
      if (!(step instanceof Map)) {
        continue
      }

      String summary = step.summary?.toString()?.trim()

      if (!summary) {
        continue
      }

      Map ctx = new LinkedHashMap<>(base)
      ctx.routerVisible = summary
      Map hit = findDeterministicRecipeMatch(recipes, ctx)

      if (hit == null) {
        continue
      }
      out.add([
        stepId      : step.id,
        summary     : summary,
        recipeId    : hit.recipeId?.toString()?.trim(),
        routerReason: hit.routerReason?.toString()
      ])
    }

    return out
  }

  /**
   * Optional {@code ambiguityMatch} rows on recipes (config) for clarify / disambiguation when deterministic
   * matching is empty or tied.
   */
  static List<Map> findStructuralRecipeCompetitors(List<Map> recipes, Map ctx, String routerVisible) {
    if (recipes == null || recipes.isEmpty() || !(ctx instanceof Map)) {
      return Collections.emptyList()
    }

    Map routeCtx = ctx instanceof Map ? new LinkedHashMap<>((Map) ctx) : [:]

    if (routerVisible?.trim()) {
      routeCtx.routerVisible = routerVisible.trim()
    }

    List<Map> out = []

    String visible = deterministicRoutingPrompt(routeCtx)
    for (Map recipe : recipes) {
      if (!(recipe instanceof Map)) {
        continue
      }

      String rid = recipe.id?.toString()?.trim()

      if (!rid) {
        continue
      }

      for (Map entry : ambiguityMatchEntries(recipe)) {
        if (!AuthoringIntentRecipeWhen.evaluateMatchEntry(entry, recipe, routeCtx)) {
          continue
        }

        int priority = entry.priority instanceof Number ? ((Number) entry.priority).intValue() : 0
        out.add([
          recipe      : recipe,
          recipeId    : rid,
          routerReason: (entry.routerReason ?: "ambiguity_${rid}").toString(),
          skipPrefetch: Boolean.TRUE.equals(entry.skipPrefetch),
          visible     : visible,
          priority    : priority
        ])
      }
    }
    out.sort { a, b ->
      int pa = a.priority instanceof Number ? ((Number) a.priority).intValue() : 0
      int pb = b.priority instanceof Number ? ((Number) b.priority).intValue() : 0
      pb <=> pa
    }

    return out
  }

  /**
   * Unions deterministic and structural recipe candidate maps by {@code recipeId}, keeping the higher-priority
   * entry when both lists contain the same id.
   */
  private static List<Map> mergeRecipeCandidateLists(List<Map> primary, List<Map> additional) {
    Map<String, Map> byId = new LinkedHashMap<>()
    for (Map m : (primary ?: [])) {
      String rid = m?.recipeId?.toString()?.trim()

      if (rid) {
        byId.put(rid, m)
      }
    }

    for (Map m : (additional ?: [])) {
      String rid = m?.recipeId?.toString()?.trim()

      if (rid && !byId.containsKey(rid)) {
        byId.put(rid, m)
      }
    }

    List<Map> out = new ArrayList<>(byId.values())
    out.sort { a, b ->
      int pa = a.priority instanceof Number ? ((Number) a.priority).intValue() : 0
      int pb = b.priority instanceof Number ? ((Number) b.priority).intValue() : 0
      pb <=> pa
    }

    return out
  }

  /**
   * Deterministic signal matches plus structural competitors (e.g. anchored page + “create” + “title”).
   * Used to decide when orchestration runs intent-tighten before deterministic retest / LLM router.
   */
  static List<Map> findAmbiguousRecipeCandidates(List<Map> recipes, Map ctx, String routerVisible) {
    List<Map> det = findDeterministicRecipeMatches(recipes, ctx)
    List<Map> structural = findStructuralRecipeCompetitors(recipes, ctx, routerVisible)

    if (det.size() > 1) {
      return det
    }

    return mergeRecipeCandidateLists(det, structural)
  }

  /** Renders a markdown table of ambiguous deterministic recipe matches for operator / LLM clarify prompts. */
  static String formatAmbiguousDeterministicMatchesMarkdown(List<Map> matches) {
    if (matches == null || matches.isEmpty()) {
      return '(none)'
    }

    StringBuilder sb = new StringBuilder()
    sb.append('| recipeId | title | routerReason |\n|---|---|---|\n')
    for (Map m : matches) {
      Map recipe = m.recipe instanceof Map ? (Map) m.recipe : [:]

      String rid = (m.recipeId ?: recipe.id ?: '').toString()
      String title = (recipe.title ?: rid).toString().replace('|', '/')
      String reason = (m.routerReason ?: '').toString().replace('|', '/')
      sb.append('| `').append(rid).append('` | ').append(title).append(' | `').append(reason).append('` |\n')
    }

    return sb.toString()
  }

  /** Author text for deterministic routing (current turn, not prior conversation in wire prompt). */
  static String deterministicRoutingPrompt(Map ctx) {
    if (!(ctx instanceof Map)) {
      return ''
    }

    String visible = (ctx.routerVisible ?: '').toString().trim()

    if (visible) {
      return visible
    }

    String cand = (ctx.cand ?: '').toString()
    String current = AuthoringPreviewContext.extractAuthorCurrentRequestVisible(cand)

    if (current?.trim()) {
      return current.trim()
    }

    return AuthoringPreviewContext.stripStudioInjectedPromptBlocks(cand) ?: ''
  }

  /** Normalizes {@code deterministicMatch} on a recipe to a list of match entry maps. */
  private static List<Map> deterministicMatchEntries(Map recipe) {
    List<Map> out = []

    Object dm = recipe?.get('deterministicMatch')

    if (dm instanceof Map) {
      out.add(new LinkedHashMap<>((Map) dm))
    } else if (dm instanceof List) {
      for (Object o : (List) dm) {
        if (o instanceof Map) {
          out.add(new LinkedHashMap<>((Map) o))
        }
      }
    }
    out
  }

  /** Normalizes {@code ambiguityMatch} on a recipe to a list of structural competitor entry maps. */
  private static List<Map> ambiguityMatchEntries(Map recipe) {
    List<Map> out = []

    Object am = recipe?.get('ambiguityMatch')

    if (am instanceof Map) {
      out.add(new LinkedHashMap<>((Map) am))
    } else if (am instanceof List) {
      for (Object o : (List) am) {
        if (o instanceof Map) {
          out.add(new LinkedHashMap<>((Map) o))
        }
      }
    }
    out
  }

  /**
   * Compact markdown for the router model (ids + titles + short descriptions + optional hint columns).
   */
  static String toRouterCatalogMarkdown(List<Map> recipes) {
    if (recipes == null || recipes.isEmpty()) {
      return '(no recipes configured)'
    }

    StringBuilder sb = new StringBuilder()
    sb.append('| recipeId | title | description (short) | match if | do not match if |\n')
    sb.append('|----------|-------|----------------------|----------|----------------|\n')
    for (Map r : recipes) {
      String id = escMdCell(r?.id?.toString()?.trim() ?: '')
      String title = escMdCell(r?.title?.toString()?.trim() ?: '')
      String desc = escMdCell(trimDesc(r?.description?.toString() ?: '', 180))
      String match = escMdCell(formatHintListForRouterCell(matchHintsList(r), 120))
      String dont = escMdCell(formatHintListForRouterCell(dontMatchHintsList(r), 120))
      sb.append('| `')
        .append(id)
        .append('` | ')
        .append(title)
        .append(' | ')
        .append(desc)
        .append(' | ')
        .append(match)
        .append(' | ')
        .append(dont)
        .append(" |\n")
    }
    sb.append('\nIf **none** of the rows fit, return `"recipeId": null`.')
    sb
  }

  /**
   * Drops recipes whose {@code dontMatchHints} appear in the author-visible message (substring, case-insensitive).
   */
  static List<Map> filterRecipesEligibleForRouter(List<Map> recipes, String authorVisible) {
    if (recipes == null || recipes.isEmpty()) {
      return recipes ?: []
    }

    if (!authorVisible?.trim()) {
      return recipes
    }

    List<Map> out = new ArrayList<>()
    for (Map r : recipes) {
      if (!recipeExcludedByDontMatchHints(r, authorVisible)) {
        out.add(r)
      }
    }
    out
  }

  /** True when any {@code dontMatchHints} phrase appears in the author-visible message. */
  static boolean recipeExcludedByDontMatchHints(Map recipe, String authorVisible) {
    List<String> dont = dontMatchHintsList(recipe)
    return !dont.isEmpty() && authorVisibleMatchesKeywordList(authorVisible, dont)
  }

  /** Delegates substring keyword matching to {@link #authorVisibleMatchesOrchestrationBypass}. */
  static boolean authorVisibleMatchesKeywordList(String authorVisible, List<String> keywords) {
    authorVisibleMatchesOrchestrationBypass(authorVisible, keywords)
  }

  /** Returns trimmed {@code matchHints} strings from a recipe map. */
  static List<String> matchHintsList(Map recipe) {
    hintStringList(recipe?.get('matchHints'))
  }

  /** Returns trimmed {@code dontMatchHints} strings from a recipe map. */
  private static List<String> dontMatchHintsList(Map recipe) {
    hintStringList(recipe?.get('dontMatchHints'))
  }

  /** Coerces a JSON list of hint strings to a trimmed, non-empty {@link List}. */
  static List<String> hintStringList(Object raw) {
    if (!(raw instanceof List)) {
      return Collections.emptyList()
    }

    List<String> out = []

    for (Object o : (List) raw) {
      String n = o?.toString()?.trim()

      if (n) {
        out.add(n)
      }
    }
    out
  }

  /** Joins hint strings for a router catalog table cell, truncating with ellipsis when over {@code maxChars}. */
  private static String formatHintListForRouterCell(List<String> hints, int maxChars) {
    if (hints == null || hints.isEmpty()) {
      return '—'
    }

    String joined = hints.join(', ')

    if (joined.length() <= maxChars) {
      return joined
    }

    return joined.substring(0, maxChars) + '…'
  }

  /** Sanitizes a string for markdown table cells (pipes and newlines). */
  private static String escMdCell(String s) {
    (s ?: '').replace('|', '/').replace('\n', ' ').trim()
  }

  /** Truncates a recipe description for the router catalog column. */
  private static String trimDesc(String s, int max) {
    String t = (s ?: '').replace('\n', ' ').trim()

    if (t.length() <= max) {
      return t
    }

    return t.substring(0, max) + '…'
  }

  /** Linear search for a recipe map by {@code id}. */
  static Map findRecipeById(List<Map> recipes, String id) {
    if (!id?.trim() || recipes == null) {
      return null
    }

    for (Map r : recipes) {
      if (id == r?.get('id')?.toString()?.trim()) {
        return r
      }
    }
    null
  }

  /**
   * Collects deterministic read-only {@code engineSteps} in execution order:
   * {@code phases.context} → {@code phases.action} → {@code phases.confirmation} (each phase may be a {@link Map}
   * with an {@code engineSteps} array), then legacy top-level {@code engineSteps}.
   */
  static List<Map> collectEngineSteps(Map recipe) {
    List<Map> out = new ArrayList<>()

    if (!(recipe instanceof Map)) {
      return Collections.unmodifiableList(out)
    }
    appendEngineStepsFromPhase(recipe.get('phases'), 'context', out)
    appendEngineStepsFromPhase(recipe.get('phases'), 'action', out)
    appendEngineStepsFromPhase(recipe.get('phases'), 'confirmation', out)
    Object legacy = recipe.get('engineSteps')

    if (legacy instanceof List) {
      for (Object o : (List) legacy) {
        if (o instanceof Map) {
          out.add(new LinkedHashMap<>((Map) o))
        }
      }
    }

    Collections.unmodifiableList(out)
  }

  /** Appends {@code engineSteps} from one recipe phase ({@code context}, {@code action}, or {@code confirmation}). */
  private static void appendEngineStepsFromPhase(Object phases, String phaseKey, List<Map> sink) {
    if (!(phases instanceof Map)) {
      return
    }

    Object phaseVal = ((Map) phases).get(phaseKey)

    if (!(phaseVal instanceof Map)) {
      return
    }

    Object es = ((Map) phaseVal).get('engineSteps')

    if (!(es instanceof List)) {
      return
    }

    for (Object o : (List) es) {
      if (o instanceof Map) {
        sink.add(new LinkedHashMap<>((Map) o))
      }
    }
  }

  /**
   * Chat workflow line from recipe catalog fields ({@code chatEmoji}, {@code title}, {@code chatLineSuffix})
   * and root {@code chatDefaults} — orchestration must not hardcode per-recipe presentation.
   */
  static String formatIntentRecipeChatLine(Map recipe, String siteId = null) {
    if (!(recipe instanceof Map) || recipe.isEmpty()) {
      return ''
    }

    Map<String, String> defs = catalogChatDefaults(siteId)
    String prefix = recipe.chatPrefixEmoji?.toString()?.trim() ?: defs.prefixEmoji ?: '🥗'
    String emoji = recipe.chatEmoji?.toString()?.trim() ?: defs.fallbackEmoji ?: '📋'
    String suffix = recipe.chatLineSuffix?.toString()?.trim() ?: defs.lineSuffix ?: 'workflow'
    String title = recipe.title?.toString()?.trim() ?: recipe.id?.toString()?.trim() ?: ''

    if (!title) {
      return ''
    }

    return prefix + ' ' + emoji + ' **' + title + '** ' + suffix + '\n'
  }

  /** @deprecated use {@link #formatIntentRecipeChatLine(Map)} with the matched recipe row */
  static String formatIntentRecipeChatLine(String recipeId, String recipeTitle) {
    Map recipe = [id: recipeId, title: recipeTitle]

    return formatIntentRecipeChatLine(recipe)
  }

  /** Builds the matched-recipe prelude without binding expansion (empty initial/current maps). */
  static String formatMatchedRecipePrelude(Map recipe, String recipeId, double confidence, String reason) {
    return formatMatchedRecipePrelude(recipe, recipeId, confidence, reason, [:], [:])
  }

  /**
   * Builds the Studio user-message prelude after a recipe match: metadata, binding names, and phase hints
   * with {@code {{initial.*}}}/{@code {{current.*}}} template expansion.
   */
  static String formatMatchedRecipePrelude(
    Map recipe,
    String recipeId,
    double confidence,
    String reason,
    Map<String, Map> initialBindings,
    Map<String, Map> currentBindings
  ) {
    int maxExpand = 12_000
    StringBuilder sb = new StringBuilder()
    sb.append('[Studio — matched authoring intent recipe]\n')
    sb.append('recipeId: ').append(recipeId).append('\n')
    sb.append('confidence: ').append(String.format(java.util.Locale.US, '%.2f', confidence)).append('\n')
    if (reason?.trim()) {
      sb.append('routerNote: ').append(reason.trim().replace('\n', ' ')).append('\n')
    }

    List<String> bindingNames = AuthoringIntentRecipeBindings.declaredBindingNames(recipe)

    if (!bindingNames.isEmpty()) {
      sb.append('bindings: ').append(bindingNames.join(', ')).append('\n')
      sb.append('(Prefetch snapshots: initial.* at turn start; current.* updates after WriteContent on the same path.)\n')
    }
    sb.append('\n**Align ## Plan and CMS tools with these phases** (visitor-visible outcomes; do not treat this block as a substitute for calling tools when work is required):\n\n')
    appendPhase(sb, 'Context', recipe?.phases, 'context', initialBindings, currentBindings, maxExpand)
    appendPhase(sb, 'Action', recipe?.phases, 'action', initialBindings, currentBindings, maxExpand)
    appendPhase(sb, 'Confirmation', recipe?.phases, 'confirmation', initialBindings, currentBindings, maxExpand)
    sb.append('\n---\n\n')
    sb.toString()
  }

  /** Optional extra author-facing prelude text from the recipe row ({@code matchedUserPrelude}). */
  static String matchedUserPrelude(Map recipe) {
    String p = recipe?.get('matchedUserPrelude')?.toString()?.trim()
    return p ?: ''
  }

  /**
   * Case-insensitive substring check: true when any bypass keyword appears in the author-visible text
   * (tools-loop allowlist bypass, match hints, etc.).
   */
  static boolean authorVisibleMatchesOrchestrationBypass(String authorVisible, List<String> bypassKeywords) {
    if (!authorVisible?.trim() || bypassKeywords == null || bypassKeywords.isEmpty()) {
      return false
    }

    String a = authorVisible.toLowerCase(Locale.ROOT)
    for (String kw : bypassKeywords) {
      String k = (kw ?: '').trim().toLowerCase(Locale.ROOT)

      if (k && a.contains(k)) {
        return true
      }
    }
    false
  }

  /**
   * Collects per-recipe tools-loop overrides ({@code allowlist}, bypass keywords, disable, force tool, excludes)
   * for orchestration telemetry and runtime enforcement.
   */
  static Map orchestrationTelemetryExtras(Map recipe) {
    List<String> allow = toolsLoopAllowlistNames(recipe)
    List<String> bypass = toolsLoopAllowlistBypassKeywords(recipe)
    List<String> exclude = toolsLoopExcludeToolNames(recipe)
    boolean toolsOff = Boolean.TRUE.equals(recipe?.get('toolsLoopDisable'))
    String forceTool = recipe?.toolsLoopForceTool?.toString()?.trim() ?: ''

    if (allow.isEmpty() && bypass.isEmpty() && exclude.isEmpty() && !toolsOff && !forceTool) {
      return Collections.emptyMap()
    }

    Map extra = new LinkedHashMap<>()

    if (!allow.isEmpty()) {
      extra.put('toolsLoopAllowlist', allow)
    }

    if (!bypass.isEmpty()) {
      extra.put('toolsLoopAllowlistBypassIfAuthorMentions', bypass)
    }

    if (!exclude.isEmpty()) {
      extra.put('toolsLoopExcludeTools', exclude)
    }

    if (toolsOff) {
      extra.put('toolsLoopDisable', Boolean.TRUE)
    }

    if (forceTool) {
      extra.put('toolsLoopForceTool', forceTool)
    }

    Collections.unmodifiableMap(extra)
  }

  /** Parses {@code toolsLoopExcludeTools} wire names from a recipe map. */
  private static List<String> toolsLoopExcludeToolNames(Map recipe) {
    Object raw = recipe?.get('toolsLoopExcludeTools')

    if (!(raw instanceof List)) {
      return Collections.emptyList()
    }

    List<String> out = []

    for (Object o : (List) raw) {
      String n = o?.toString()?.trim()

      if (n) {
        out.add(n)
      }
    }

    return Collections.unmodifiableList(out)
  }

  /** Parses {@code toolsLoopAllowlist} wire names from a recipe map. */
  private static List<String> toolsLoopAllowlistNames(Map recipe) {
    Object raw = recipe?.get('toolsLoopAllowlist')

    if (!(raw instanceof List)) {
      return Collections.emptyList()
    }

    List<String> out = []

    for (Object o : (List) raw) {
      String n = o?.toString()?.trim()

      if (n) {
        out.add(n)
      }
    }
    out
  }

  /** Parses {@code toolsLoopAllowlistBypassIfAuthorMentions} keywords from a recipe map. */
  private static List<String> toolsLoopAllowlistBypassKeywords(Map recipe) {
    Object raw = recipe?.get('toolsLoopAllowlistBypassIfAuthorMentions')

    if (!(raw instanceof List)) {
      return Collections.emptyList()
    }

    List<String> out = []

    for (Object o : (List) raw) {
      String n = o?.toString()?.trim()

      if (n) {
        out.add(n)
      }
    }
    out
  }

  /**
   * Appends one phase section (Context / Action / Confirmation) to the matched-recipe prelude, expanding
   * hint templates when binding maps are present.
   */
  private static void appendPhase(
    StringBuilder sb,
    String label,
    Object phases,
    String key,
    Map<String, Map> initialBindings,
    Map<String, Map> currentBindings,
    int maxExpandChars
  ) {
    if (!(phases instanceof Map)) {
      return
    }

    Object raw = ((Map) phases).get(key)

    if (raw == null) {
      return
    }

    if (raw instanceof List) {
      appendPhaseHintLines(sb, label, (List) raw, initialBindings, currentBindings, maxExpandChars)
      return
    }

    if (raw instanceof Map) {
      Map pm = (Map) raw
      Object hints = pm.get('hints')

      if (!(hints instanceof List) || ((List) hints).isEmpty()) {
        hints = pm.get('lines')
      }

      if (hints instanceof List && !((List) hints).isEmpty()) {
        appendPhaseHintLines(sb, label, (List) hints, initialBindings, currentBindings, maxExpandChars)
      }
    }
  }

  /** Renders bullet hint lines for a phase, running each through {@link AuthoringIntentRecipeBindings#expandHintTemplates}. */
  private static void appendPhaseHintLines(
    StringBuilder sb,
    String label,
    List lines,
    Map<String, Map> initialBindings,
    Map<String, Map> currentBindings,
    int maxExpandChars
  ) {
    if (lines == null || lines.isEmpty()) {
      return
    }
    sb.append('**').append(label).append(":**\n")
    for (Object line : lines) {
      String s = line?.toString()?.trim()

      if (s) {
        String expanded = AuthoringIntentRecipeBindings.expandHintTemplates(
          s,
          initialBindings ?: [:],
          currentBindings ?: [:],
          maxExpandChars
        )
        sb.append('- ').append(expanded).append('\n')
      }
    }
    sb.append('\n')
  }

  /**
   * Prepended on plan-defer turns so the model sees recipes and wired tools while formulating **## Plan**.
   * @param recipeCatalogMd optional pre-built markdown (e.g. from routing pass); rebuilt when blank
   */
  static String formatPlanDeferOrchestrationContextBlock(
    List<Map> recipes,
    String routerVisible,
    StudioToolOperations ops,
    Map cfg,
    String recipeCatalogMd = null,
    Map toolsLoopSessionBundle = null
  ) {
    StringBuilder sb = new StringBuilder()
    sb.append('[Studio — plan defer: recipe + tool catalog]\n\n')
    sb.append(
      'No single whole-turn recipe matched. When you formulate **## Plan** and **`tool_calls`**, use **both** catalogs below.\n'
    )
    sb.append(
      '- **Prefer a recipe** when a catalog row clearly fits a step (recipe **phases** encode tool policy and order). Name the workflow in plain language on **📋** lines; execute with the tools that recipe implies.\n'
    )
    sb.append(
      '- **Prefer an individual wire tool** only when one call is enough (**simple** tier) or **no recipe** fits that step.\n'
    )
    sb.append(
      '- **Site user tools:** call **`InvokeSiteUserTool`** with **`toolId`** exactly as registered (e.g. author says “Gold Tool” → match registry **toolId**, not the display title alone).\n'
    )
    sb.append(
      '- **Live / external data** (prices, news, APIs): do **not** answer from memory when a recipe (**web_research**) or **`InvokeSiteUserTool`** / **FetchHttpUrl** fits — use **`tool_calls`**.\n\n'
    )
    String recipeMd = (recipeCatalogMd ?: '').trim()
    if (!recipeMd) {
      List<Map> eligible = filterRecipesEligibleForRouter(recipes, routerVisible)
      recipeMd = toRouterCatalogMarkdown(eligible)
    }
    sb.append('## Intent recipe catalog\n\n').append(recipeMd).append('\n\n')
    sb.append(AiOrchestrationTools.formatPlanDeferToolsCatalogMarkdown(ops, cfg, toolsLoopSessionBundle))
    sb.append('\n---\n\n')
    sb.toString()
  }

  /** Per-**## Plan** step recipe hints after deterministic re-match (defer-to-plan execution). */
  static String formatPlanStepRecipeHintsWire(List<Map> planStepHits) {
    if (planStepHits == null || planStepHits.isEmpty()) {
      return ''
    }
    StringBuilder sb = new StringBuilder()
    sb.append('[Studio — plan-step recipe hints]\n')
    sb.append(
      'Deterministic recipe matches for **## Plan** steps. When a step lists a **recipeId**, prefer that recipe’s workflow over ad-hoc tool picking for that step.\n\n'
    )
    for (Map hit : planStepHits) {
      String stepId = hit.stepId?.toString()?.trim() ?: '?'
      String summary = hit.summary?.toString()?.trim() ?: ''
      String rid = hit.recipeId?.toString()?.trim() ?: ''
      String reason = hit.routerReason?.toString()?.trim() ?: ''
      sb.append('- Step ').append(stepId)
      if (summary) {
        sb.append(' (“').append(summary.replace('\n', ' ')).append('”)')
      }
      sb.append(': **').append(rid).append('**')
      if (reason) {
        sb.append(' (').append(reason).append(')')
      }
      sb.append('\n')
    }
    sb.append('\n---\n\n')
    sb.toString()
  }
}
