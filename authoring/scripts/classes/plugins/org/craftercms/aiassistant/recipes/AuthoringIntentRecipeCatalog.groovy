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
import java.util.LinkedHashSet
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.Set
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Loads bundled + optional site **authoring intent recipes** for the intent-router pass.
 * <p>Default file ships next to this class on the classpath; sites may override via
 * {@link StudioAiAssistantProjectConfig#intentRecipeCustomRecipesPath}.</p>
 * <p>Phase helpers: {@link #collectPrefetchEngineSteps} (context + action JVM prefetch),
 * {@link #collectConfirmationEngineSteps} and {@link #inferConfirmationEngineStepsFromHints}
 * (post-action confirmation), {@link #formatMatchedRecipePrelude} (execution plan wire block).</p>
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
        String raw = readSiteIntentRecipesUtf8(ops, sitePath.trim())

        if (raw?.trim()) {
          siteOrder = parseRecipeOrderFromJsonText(raw)
          Map siteDoc = parseCatalogDocument(raw)

          if (siteDoc?.chatDefaults instanceof Map) {
            String chatDefaultsSite = ops.resolveEffectiveSiteId('')?.toString()?.trim() ?: ops.resolveStudioSessionSiteId()?.trim()
            installCatalogChatDefaults((Map) siteDoc.chatDefaults, chatDefaultsSite)
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
        String raw = readSiteIntentRecipesUtf8(ops, sitePath.trim())
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
  /**
   * Plugin-bundled default catalog in the site sandbox — always read from the Studio session site
   * (plugin install target), not the POST-body working site used for CMS tools.
   */
  private static String loadBundledRecipesJsonFromSiteSandbox(StudioToolOperations ops) {
    if (ops == null) {
      return ''
    }

    List<String> siteIds = []
    String sessionSite = ops.resolveStudioSessionSiteId()?.toString()?.trim()
    if (sessionSite) {
      siteIds.add(sessionSite)
    }
    String workingSite = ops.resolveEffectiveSiteId('')?.toString()?.trim()
    if (workingSite && !siteIds.contains(workingSite)) {
      siteIds.add(workingSite)
    }

    for (String siteId : siteIds) {
      try {
        Map item = plugins.org.craftercms.aiassistant.tools.cms.support.CmsGetContent.read(
          ops,
          siteId,
          BUNDLED_SANDBOX_REPO_PATH
        ) as Map
        String raw = item?.contentXml?.toString()?.trim()
        if (raw?.trim() && parseCatalogDocument(raw)?.recipes) {
          return raw
        }
      } catch (Throwable t) {
        log.trace('AuthoringIntentRecipeCatalog: sandbox bundled JSON not readable siteId={}: {}', siteId, t.message)
      }
    }
    return ''
  }

  /** Site {@code intent-recipes.json}: working site first, then Studio session site when cross-site. */
  private static String readSiteIntentRecipesUtf8(StudioToolOperations ops, String sitePath) {
    String workingSite = ops.resolveEffectiveSiteId('')?.toString()?.trim() ?: ''
    String raw = ''
    if (workingSite) {
      try {
        raw = ops.readStudioConfigurationUtf8(workingSite, sitePath) ?: ''
      } catch (Throwable ignoredWorking) {
      }
    }
    if (raw?.trim()) {
      return raw
    }
    String sessionSite = ops.resolveStudioSessionSiteId()?.toString()?.trim() ?: ''
    if (sessionSite && !sessionSite.equalsIgnoreCase(workingSite)) {
      try {
        return ops.readStudioConfigurationUtf8(sessionSite, sitePath) ?: ''
      } catch (Throwable ignoredSession) {
        return ''
      }
    }
    return raw ?: ''
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
      if (!(recipe instanceof Map) || !recipeEligibleForAuthorUrlDraftFastPath(recipe)) {
        continue
      }
      Map draftFast = buildAuthorUrlExclusiveDraftDeterministicMatch(recipe, ctx)
      if (draftFast != null) {
        String fastId = draftFast.recipeId?.toString()
        Map existingFast = byId.get(fastId)
        int fastPri = draftFast.priority instanceof Number ? ((Number) draftFast.priority).intValue() : 0
        int existPri = existingFast?.priority instanceof Number ? ((Number) existingFast.priority).intValue() : 0
        if (existingFast == null || fastPri > existPri) {
          byId.put(fastId, draftFast)
        }
      }
    }
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
        String prefetchSupplement = entry?.get('toolsLoopPrefetchSupplement')?.toString()?.trim() ?: ''
        if (!prefetchSupplement) {
          prefetchSupplement = recipe?.get('toolsLoopPrefetchSupplement')?.toString()?.trim() ?: ''
        }
        if (prefetchSupplement) {
          match.toolsLoopPrefetchSupplement = prefetchSupplement
        }
        String preludeOverride = entry?.get('matchedUserPreludeOverride')?.toString()?.trim() ?: ''
        if (preludeOverride) {
          match.matchedUserPreludeOverride = preludeOverride
        }
        List<String> requireTools = mergeToolsLoopRequireSuccessfulTools(recipe, entry)
        if (!requireTools.isEmpty()) {
          match.toolsLoopRequireSuccessfulTools = requireTools
        }

        Map existing = byId.get(rid)

        if (existing == null || priority > (existing.priority instanceof Number ?
          ((Number) existing.priority).intValue() : 0)) {
          byId.put(rid, match)
        }
      }
    }

    List<Map> out = new ArrayList<>(byId.values())
    if (out.isEmpty()) {
      Map resolved = resolveSingleAuthorUrlDraftDeterministicMatch(recipes, ctx)
      if (resolved != null) {
        out.add(resolved)
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
   * Single deterministic match when exactly one recipe matches; {@code null} when none or ambiguous.
   * @return map with keys {@code recipe}, {@code recipeId}, {@code routerReason}, {@code skipPrefetch}, {@code visible}
   */
  static Map findDeterministicRecipeMatch(List<Map> recipes, Map ctx) {
    List<Map> matches = findDeterministicRecipeMatches(recipes, ctx)
    return matches.size() == 1 ? matches[0] : null
  }

  /**
   * Merged {@code routingRecipeFamilies} + {@code multiGoalDefer} from bundled catalog and optional site override.
   * Routing keys are merged from the **canonical** plugin JSON (classpath / code-source) first so a stale site-sandbox
   * copy of {@link #BUNDLED_RELATIVE} cannot drop {@code multiGoalDefer}.
   */
  static Map loadMergedCatalogRoutingConfig(StudioToolOperations ops, Map projectCfg) {
    Map merged = new LinkedHashMap()
    mergeCatalogRoutingSections(merged, parseCatalogDocument(loadBundledCatalogRoutingJsonCanonical()))
    mergeCatalogRoutingSections(merged, parseCatalogDocument(loadBundledRecipesJsonText(ops)))
    String sitePath = StudioAiAssistantProjectConfig.intentRecipeCustomRecipesPath(projectCfg)
    if (ops != null && sitePath?.trim()) {
      try {
        String raw = ops.readStudioConfigurationUtf8(ops.resolveEffectiveSiteId(''), sitePath.trim())
        if (raw?.trim()) {
          mergeCatalogRoutingSections(merged, parseCatalogDocument(raw))
        }
      } catch (Throwable t) {
        log.warn('AuthoringIntentRecipeCatalog: site routing config read failed: {}', t.message)
      }
    }
    if (!(merged.multiGoalDefer instanceof Map) || !(((Map) merged.multiGoalDefer).groups instanceof Map)) {
      log.warn(
        'AuthoringIntentRecipeCatalog: multiGoalDefer missing after merge — multi-goal plan defer disabled until catalog JSON is updated'
      )
    }
    return Collections.unmodifiableMap(merged)
  }

  /**
   * Bundled catalog JSON for routing keys only — classpath / code-source, **not** the site sandbox copy
   * ({@link #loadBundledRecipesJsonFromSiteSandbox}), which is often stale after plugin upgrades.
   */
  private static String loadBundledCatalogRoutingJsonCanonical() {
    String override = System.getProperty(SYSPROP_BUNDLED_PATH)?.toString()?.trim()
    if (override) {
      try {
        File f = new File(override)
        if (f.isFile()) {
          return f.getText('UTF-8')
        }
      } catch (Throwable ignored) {
        // fall through
      }
    }
    String pkgPath = "${PACKAGE_RESOURCE_PREFIX}${BUNDLED_RELATIVE}"
    for (String raw : [
      readUtf8FromResourceUrl(AuthoringIntentRecipeCatalog.class.getResource(BUNDLED_RELATIVE)),
      readUtf8FromResourceUrl(AuthoringIntentRecipeCatalog.class.getResource("/${pkgPath}")),
      readUtf8FromResourceStream(AuthoringIntentRecipeCatalog.class.getResourceAsStream(BUNDLED_RELATIVE)),
      readUtf8FromResourceStream(AuthoringIntentRecipeCatalog.class.classLoader?.getResourceAsStream(pkgPath))
    ]) {
      if (raw?.trim()) {
        return raw
      }
    }
    try {
      def loc = AuthoringIntentRecipeCatalog.class.protectionDomain?.codeSource?.location
      if (loc != null) {
        String fromCodeSource = readBundledJsonBesideCodeSource(loc)
        if (fromCodeSource?.trim()) {
          return fromCodeSource
        }
      }
    } catch (Throwable ignored) {
      // fall through
    }
    return ''
  }

  /** Deep-merges {@code routingRecipeFamilies} and replaces {@code multiGoalDefer} when present on {@code doc}. */
  private static void mergeCatalogRoutingSections(Map into, Map doc) {
    if (!(into instanceof Map) || !(doc instanceof Map)) {
      return
    }
    Object fam = doc.get('routingRecipeFamilies')
    if (fam instanceof Map) {
      Map target = into.routingRecipeFamilies instanceof Map ?
        new LinkedHashMap<>((Map) into.routingRecipeFamilies) :
        new LinkedHashMap<>()
      for (Map.Entry e : ((Map) fam).entrySet()) {
        target.put(e.key?.toString(), hintStringList(e.value))
      }
      into.routingRecipeFamilies = target
    }
    if (doc.multiGoalDefer instanceof Map) {
      into.multiGoalDefer = new LinkedHashMap<>((Map) doc.multiGoalDefer)
    }
  }

  /** Recipe ids listed under a {@code routingRecipeFamilies} key in catalog routing config. */
  static List<String> routingFamilyRecipeIds(Map routingCfg, String familyKey) {
    if (!(routingCfg instanceof Map) || !familyKey?.trim()) {
      return Collections.emptyList()
    }
    Object fam = routingCfg.routingRecipeFamilies
    if (!(fam instanceof Map)) {
      return Collections.emptyList()
    }
    return hintStringList(((Map) fam).get(familyKey.trim()))
  }

  /**
   * True when {@code recipeId} is in {@link #findDeterministicRecipeMatches} for {@code ctx}, or
   * {@code matchHints} hit the routing prompt (even when {@code dontMatchHints} block a whole-turn match).
   */
  static boolean recipeAuthoringSignalsHit(String recipeId, List<Map> recipes, Map ctx) {
    String rid = (recipeId ?: '').toString().trim()
    if (!rid || !recipes) {
      return false
    }
    Map recipe = findRecipeById(recipes, rid)
    if (!recipe) {
      return false
    }
    if (findDeterministicRecipeMatches(recipes, ctx).any { it.recipeId?.toString() == rid }) {
      return true
    }
    List<String> hints = matchHintsList(recipe)
    if (hints.isEmpty()) {
      return false
    }
    String author = deterministicRoutingPrompt(ctx)
    return authorVisibleMatchesKeywordList(author, hints)
  }

  /** True when any recipe id in a configured routing family signals on {@code ctx}. */
  static boolean authorMatchesRoutingFamily(String familyKey, List<Map> recipes, Map ctx, Map routingCfg) {
    List<String> ids = routingFamilyRecipeIds(routingCfg, familyKey)
    return ids.any { String rid -> recipeAuthoringSignalsHit(rid, recipes, ctx) }
  }

  /** Any configured research family ({@code researchAny}, or web + site + llm lists). */
  static boolean authorVisibleSuggestsConfiguredResearch(List<Map> recipes, Map ctx, Map routingCfg) {
    if (authorMatchesRoutingFamily('researchAny', recipes, ctx, routingCfg)) {
      return true
    }
    return authorMatchesRoutingFamily('researchWeb', recipes, ctx, routingCfg) ||
      authorMatchesRoutingFamily('researchSite', recipes, ctx, routingCfg) ||
      authorMatchesRoutingFamily('researchLlm', recipes, ctx, routingCfg)
  }

  /**
   * General-knowledge family only ({@code researchLlm}), excluding web/site research families when they also match.
   */
  static boolean authorCurrentRequestSuggestsGeneralKnowledgeResearch(
    List<Map> recipes,
    Map ctx,
    Map routingCfg
  ) {
    if (authorMatchesRoutingFamily('researchWeb', recipes, ctx, routingCfg) ||
      authorMatchesRoutingFamily('researchSite', recipes, ctx, routingCfg)) {
      return false
    }
    return authorMatchesRoutingFamily('researchLlm', recipes, ctx, routingCfg)
  }

  /**
   * {@code multiGoalDefer} in catalog JSON: at least {@code minDistinctGroups} groups have a signaling recipe.
   */
  static boolean authorSuggestsMultiGoalDefer(List<Map> recipes, Map ctx, Map routingCfg) {
    Object mg = routingCfg?.multiGoalDefer
    if (!(mg instanceof Map)) {
      return false
    }
    Object groups = mg.groups
    if (!(groups instanceof Map) || ((Map) groups).isEmpty()) {
      return false
    }
    int minGroups = mg.minDistinctGroups instanceof Number ?
      ((Number) mg.minDistinctGroups).intValue() :
      2
    if (minGroups < 2) {
      minGroups = 2
    }
    int hit = 0
    for (Object groupIds : ((Map) groups).values()) {
      List<String> ids = hintStringList(groupIds)
      boolean groupHit = ids.any { String rid -> recipeAuthoringSignalsHit(rid, recipes, ctx) }
      if (groupHit) {
        hit++
      }
    }
    return hit >= minGroups
  }

  /** Distinct {@code multiGoalDefer.groups} keys that have at least one signaling recipe (for telemetry). */
  static List<String> multiGoalDeferGroupsHit(List<Map> recipes, Map ctx, Map routingCfg) {
    Object mg = routingCfg?.multiGoalDefer
    if (!(mg instanceof Map) || !(((Map) mg).groups instanceof Map)) {
      return Collections.emptyList()
    }
    List<String> hit = new ArrayList<>()
    for (Map.Entry e : ((Map) ((Map) mg).groups).entrySet()) {
      List<String> ids = hintStringList(e.value)
      boolean groupHit = ids.any { String rid -> recipeAuthoringSignalsHit(rid, recipes, ctx) }
      if (groupHit) {
        hit.add(e.key?.toString() ?: '')
      }
    }
    return hit
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
   * Collects all {@code engineSteps} in phase order (context → action → confirmation) plus legacy top-level steps.
   * Used for binding names and recipe editor parity. Prefetch execution uses {@link #collectPrefetchEngineSteps};
   * confirmation execution uses {@link #collectConfirmationEngineSteps}.
   */
  static List<Map> collectEngineSteps(Map recipe) {
    List<Map> out = new ArrayList<>()
    out.addAll(collectPrefetchEngineSteps(recipe))
    out.addAll(collectConfirmationEngineSteps(recipe))
    if (!(recipe instanceof Map)) {
      return Collections.unmodifiableList(out)
    }
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

  /** {@code engineSteps} for JVM prefetch only: {@code phases.context} and {@code phases.action}. */
  static List<Map> collectPrefetchEngineSteps(Map recipe) {
    List<Map> out = new ArrayList<>()
    if (!(recipe instanceof Map)) {
      return Collections.unmodifiableList(out)
    }
    appendEngineStepsFromPhase(recipe.get('phases'), 'context', out)
    appendEngineStepsFromPhase(recipe.get('phases'), 'action', out)
    Collections.unmodifiableList(out)
  }

  /** {@code engineSteps} for post-action server execution: {@code phases.confirmation} only. */
  static List<Map> collectConfirmationEngineSteps(Map recipe) {
    List<Map> out = new ArrayList<>()
    if (!(recipe instanceof Map)) {
      return Collections.unmodifiableList(out)
    }
    appendEngineStepsFromPhase(recipe.get('phases'), 'confirmation', out)
    if (out.isEmpty()) {
      out.addAll(inferConfirmationEngineStepsFromHints(recipe))
    }
    Collections.unmodifiableList(out)
  }

  /**
   * When Confirmation is hint-only (string list), infer JVM steps for allowlisted wires named in hints that
   * opt into {@link plugins.org.craftercms.aiassistant.tools.spi.StudioAiOrchestrationTool#recipeEngineConfirmationStep()}.
   */
  static List<Map> inferConfirmationEngineStepsFromHints(Map recipe) {
    List<Map> out = new ArrayList<>()
    if (!(recipe instanceof Map)) {
      return Collections.unmodifiableList(out)
    }
    String blob = collectPhaseHintTextsForPhase(recipe, 'confirmation').join('\n').toLowerCase(Locale.ROOT)
    if (!blob) {
      return Collections.unmodifiableList(out)
    }
    List<String> allow = toolsLoopAllowlistNames(recipe)
    Set<String> confWires =
      plugins.org.craftercms.aiassistant.tools.catalog.StudioAiToolRegistry.recipeEngineConfirmationWireNames()
    for (String wire : confWires) {
      String w = (wire ?: '').trim()
      if (!w) {
        continue
      }
      if (allow != null && !allow.isEmpty() && !allow.contains(w)) {
        continue
      }
      if (!blob.contains(w.toLowerCase(Locale.ROOT))) {
        continue
      }
      out.add([tool: w, args: [:]] as Map)
    }
    Collections.unmodifiableList(out)
  }

  /** Hint lines for one phase ({@code context}, {@code action}, or {@code confirmation}). */
  static List<String> collectPhaseHintTextsForPhase(Map recipe, String phaseKey) {
    List<String> out = new ArrayList<>()
    if (!(recipe instanceof Map) || !(phaseKey?.trim())) {
      return Collections.unmodifiableList(out)
    }
    Object phases = recipe.get('phases')
    if (!(phases instanceof Map)) {
      return Collections.unmodifiableList(out)
    }
    Object raw = ((Map) phases).get(phaseKey.trim())
    if (raw instanceof List) {
      for (Object line : (List) raw) {
        String s = line?.toString()?.trim()
        if (s) {
          out.add(s)
        }
      }
    } else if (raw instanceof Map) {
      Object hints = ((Map) raw).get('hints')
      if (!(hints instanceof List)) {
        hints = ((Map) raw).get('lines')
      }
      if (hints instanceof List) {
        for (Object line : (List) hints) {
          String s = line?.toString()?.trim()
          if (s) {
            out.add(s)
          }
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
    return formatMatchedRecipePrelude(recipe, recipeId, confidence, reason, [:], [:], null)
  }

  private static final Pattern AUTHOR_HTTP_URL_PATTERN = Pattern.compile(
    'https?://[^\\s<>"\\)\\]\\u00a0]+',
    Pattern.CASE_INSENSITIVE
  )

  /**
   * Distinct http(s) URLs from author-visible text (studio blocks stripped). Trailing punctuation trimmed.
   */
  static List<String> extractAuthorHttpUrls(String authorVisible) {
    String stripped = AuthoringPreviewContext.stripStudioInjectedPromptBlocks((authorVisible ?: '').trim()) ?: ''
    if (!stripped) {
      return Collections.emptyList()
    }

    LinkedHashSet<String> urls = new LinkedHashSet<>()
    def m = AUTHOR_HTTP_URL_PATTERN.matcher(stripped)
    while (m.find()) {
      String u = m.group().replaceAll(/[.,;:!?]+$/, '').trim()
      if (u.startsWith('http://') || u.startsWith('https://')) {
        urls.add(u)
      }
    }
    return new ArrayList<>(urls)
  }

  private static final Pattern DRAFT_BLOG_FROM_URL_SIGNAL = Pattern.compile(
    '(?is)(\\bdraft\\b.{0,120}\\b(?:blog|article|post)\\b|\\b(?:blog|article|post)\\b.{0,80}\\bdraft\\b|\\bsummariz(?:e|ing)\\b.{0,120}\\b(?:blog|article|post|topics)\\b|\\bfrom\\s+this\\s*:\\s*https?://)'
  )

  private static final List<String> DRAFT_FROM_URL_NONE_PHRASES = Collections.unmodifiableList([
    'create a new',
    'save to repository',
    'make a post with',
    'create a post',
    'save the draft',
    'WriteContent'
  ])

  /** Recipe catalog hints that identify author-URL draft workflows (bundled + typical site overrides). */
  private static final List<String> AUTHOR_URL_DRAFT_RECIPE_HINT_MARKERS = Collections.unmodifiableList([
    'from this:',
    'from this url',
    'draft a blog',
    'draft blog',
    'summarize from',
    'summarizing from'
  ])

  /**
   * True when the author pasted http(s) URL(s) and wants a chat draft/summary from that source (not create/save in repo).
   */
  static boolean authorVisibleSuggestsDraftContentFromAuthorUrl(String authorVisible) {
    String author = AuthoringPreviewContext.stripStudioInjectedPromptBlocks((authorVisible ?: '').trim())?.trim()
    if (!author || extractAuthorHttpUrls(author).isEmpty()) {
      return false
    }
    if (!DRAFT_BLOG_FROM_URL_SIGNAL.matcher(author).find()) {
      return false
    }
    return !authorVisibleMatchesKeywordList(author, DRAFT_FROM_URL_NONE_PHRASES)
  }

  /**
   * Site overrides often keep draft-from-URL {@code matchHints} / {@code routerReason} but omit {@code toolsLoopAuthorUrlExclusive}.
   */
  static boolean recipeEligibleForAuthorUrlDraftFastPath(Map recipe) {
    if (!(recipe instanceof Map)) {
      return false
    }
    if (recipeToolsLoopAuthorUrlExclusive(recipe)) {
      return true
    }
    List<String> hints = matchHintsList(recipe)
    for (String marker : AUTHOR_URL_DRAFT_RECIPE_HINT_MARKERS) {
      for (String h : hints) {
        if (h != null && h.equalsIgnoreCase(marker)) {
          return true
        }
      }
    }
    for (Map entry : deterministicMatchEntries(recipe)) {
      String reason = (entry.routerReason ?: '').toString().toLowerCase(Locale.ROOT)
      if (reason.contains('draft') && reason.contains('author_url')) {
        return true
      }
    }
    return false
  }

  /**
   * Highest-priority author-URL draft recipe match when structural {@code when} evaluation returned none.
   */
  static Map resolveSingleAuthorUrlDraftDeterministicMatch(List<Map> recipes, Map ctx) {
    if (recipes == null || recipes.isEmpty() || !(ctx instanceof Map)) {
      return null
    }
    String author = deterministicRoutingPrompt(ctx)
    if (!authorVisibleSuggestsDraftContentFromAuthorUrl(author)) {
      return null
    }
    Map best = null
    int bestPri = Integer.MIN_VALUE
    for (Map recipe : recipes) {
      if (!recipeEligibleForAuthorUrlDraftFastPath(recipe)) {
        continue
      }
      Map match = buildAuthorUrlExclusiveDraftDeterministicMatch(recipe, ctx)
      if (!match) {
        continue
      }
      int pri = match.priority instanceof Number ? ((Number) match.priority).intValue() : 0
      if (best == null || pri > bestPri) {
        best = match
        bestPri = pri
      }
    }
    return best
  }

  /**
   * Safety-net match for author-URL draft turns when JSON {@code when} fails (older sandboxes or broken site overrides).
   */
  static Map buildAuthorUrlExclusiveDraftDeterministicMatch(Map recipe, Map ctx) {
    if (!(recipe instanceof Map) || !(ctx instanceof Map)) {
      return null
    }
    String author = deterministicRoutingPrompt(ctx)
    if (!authorVisibleSuggestsDraftContentFromAuthorUrl(author)) {
      return null
    }
    if (recipeExcludedByDontMatchHints(recipe, author)) {
      return null
    }
    String rid = recipe.id?.toString()?.trim() ?: ''
    if (!rid) {
      return null
    }
    List<Map> dmEntries = deterministicMatchEntries(recipe)
    Map entry = dmEntries.isEmpty() ? [:] : dmEntries[0]
    int priority = entry.priority instanceof Number ? ((Number) entry.priority).intValue() : 92
    String reason = (entry.routerReason ?: 'deterministic_draft_content_from_author_url').toString()
    Map match = [
      recipe                       : recipe,
      recipeId                     : rid,
      routerReason                 : reason,
      skipPrefetch                 : Boolean.TRUE.equals(entry.skipPrefetch),
      visible                      : author,
      priority                     : priority,
      deterministicAuthorUrlFastPath: Boolean.TRUE
    ]
    String prefetchSupplement = entry?.get('toolsLoopPrefetchSupplement')?.toString()?.trim() ?: ''
    if (!prefetchSupplement) {
      prefetchSupplement = recipe?.get('toolsLoopPrefetchSupplement')?.toString()?.trim() ?: ''
    }
    if (prefetchSupplement) {
      match.toolsLoopPrefetchSupplement = prefetchSupplement
    }
    List<String> requireTools = mergeToolsLoopRequireSuccessfulTools(recipe, entry)
    if (!requireTools.isEmpty()) {
      match.toolsLoopRequireSuccessfulTools = requireTools
    }
    return match
  }

  /**
   * When clarify/enrich returns a one-line intent without the author's URL(s), keep URLs on the routing prompt for rematch.
   */
  static String mergeAuthorHttpUrlsIntoRouterVisible(String originalRouterVisible, String clarifiedRouterVisible) {
    String orig = (originalRouterVisible ?: '').trim()
    String clar = (clarifiedRouterVisible ?: '').trim()
    if (!orig || !clar) {
      return clar ?: orig
    }
    List<String> urls = extractAuthorHttpUrls(orig)
    if (urls.isEmpty()) {
      return clar
    }
    boolean clarHasUrl = !extractAuthorHttpUrls(clar).isEmpty()
    if (clarHasUrl) {
      return clar
    }
    return clar + '\n\n' + urls.join('\n')
  }

  /** When true and the author message includes http(s) URL(s), tools-loop skips open-web search and FetchHttpUrl is limited to those URLs only. */
  static boolean recipeToolsLoopAuthorUrlExclusive(Map recipe) {
    return Boolean.TRUE.equals(recipe?.get('toolsLoopAuthorUrlExclusive'))
  }

  /**
   * Normalizes a URL for author-url-exclusive FetchHttpUrl matching (trim, strip trailing punctuation/slash).
   */
  static String normalizeHttpUrlForAuthorExclusiveMatch(String url) {
    String u = (url ?: '').trim().replaceAll(/[.,;:!?]+$/, '').trim()
    if (!u) {
      return ''
    }
    while (u.endsWith('/')) {
      u = u.substring(0, u.length() - 1)
    }
    return u
  }

  static boolean authorProvidedHttpUrlMatches(String fetchUrl, List<String> authorProvidedUrls) {
    String want = normalizeHttpUrlForAuthorExclusiveMatch(fetchUrl)
    if (!want || !authorProvidedUrls) {
      return false
    }
    for (String a : authorProvidedUrls) {
      if (want.equalsIgnoreCase(normalizeHttpUrlForAuthorExclusiveMatch(a))) {
        return true
      }
    }
    false
  }

  /**
   * When {@link #recipeToolsLoopAuthorUrlExclusive} is set and {@code authorVisible} contains URL(s),
   * returns telemetry overrides: no forced web search, Serp/WebSearch excluded, FetchHttpUrl capped to author URLs only.
   */
  static Map authorUrlExclusiveTelemetryOverlay(Map recipe, String authorVisible) {
    if (!recipeToolsLoopAuthorUrlExclusive(recipe)) {
      return Collections.emptyMap()
    }
    List<String> authorUrls = extractAuthorHttpUrls(authorVisible)
    if (authorUrls.isEmpty()) {
      return Collections.emptyMap()
    }

    Map overlay = new LinkedHashMap<>()
    overlay.put('toolsLoopAuthorUrlExclusiveActive', Boolean.TRUE)
    overlay.put('toolsLoopAuthorProvidedUrls', Collections.unmodifiableList(new ArrayList<>(authorUrls)))

    LinkedHashSet<String> exclude = new LinkedHashSet<>(toolsLoopExcludeToolNames(recipe))
    exclude.add('SerpApiWebSearch')
    exclude.add('WebSearch')
    overlay.put('toolsLoopExcludeTools', new ArrayList<>(exclude))

    LinkedHashSet<String> allow = new LinkedHashSet<>()
    for (String n : toolsLoopAllowlistNames(recipe)) {
      if ('FetchHttpUrl'.equals(n)) {
        allow.add(n)
      }
    }
    if (allow.isEmpty()) {
      allow.add('FetchHttpUrl')
    }
    overlay.put('toolsLoopAllowlist', new ArrayList<>(allow))
    overlay.put('toolsLoopForceTool', '')
    int recipeMax = resolveToolsLoopMaxFetchHttpUrlCalls(recipe)
    int cap = authorUrls.size()
    if (recipeMax > 0) {
      cap = Math.min(recipeMax, cap)
    }
    overlay.put('toolsLoopMaxFetchHttpUrlCalls', Math.max(1, cap))
    Collections.unmodifiableMap(overlay)
  }

  /**
   * Optional JVM prefetch supplement id from a deterministic match entry or recipe root
   * (e.g. {@code createFromChatDraft} — implemented in {@link AuthoringIntentRecipeEngine}).
   */
  static String toolsLoopPrefetchSupplementFromMatch(Map detMatch) {
    if (!(detMatch instanceof Map)) {
      return ''
    }
    String fromEntry = detMatch.get('toolsLoopPrefetchSupplement')?.toString()?.trim() ?: ''
    if (fromEntry) {
      return fromEntry
    }
    Map recipe = detMatch.recipe instanceof Map ? (Map) detMatch.recipe : null
    return recipe?.get('toolsLoopPrefetchSupplement')?.toString()?.trim() ?: ''
  }

  /**
   * Optional {@code prefetchSupplementConfig} from deterministic match entry or recipe root (site-specific draft headings).
   */
  static Map toolsLoopPrefetchSupplementConfigFromMatch(Map detMatch) {
    if (!(detMatch instanceof Map)) {
      return [:]
    }
    Object fromEntry = detMatch.get('prefetchSupplementConfig')
    if (fromEntry instanceof Map && !((Map) fromEntry).isEmpty()) {
      return new LinkedHashMap<>((Map) fromEntry)
    }
    Map recipe = detMatch.recipe instanceof Map ? (Map) detMatch.recipe : null
    Object fromRecipe = recipe?.get('prefetchSupplementConfig')
    if (fromRecipe instanceof Map && !((Map) fromRecipe).isEmpty()) {
      return new LinkedHashMap<>((Map) fromRecipe)
    }
    String supplementId = toolsLoopPrefetchSupplementFromMatch(detMatch)
    if (supplementId && recipe?.prefetchSupplements instanceof Map) {
      Object nested = ((Map) recipe.prefetchSupplements).get(supplementId)
      if (nested instanceof Map) {
        return new LinkedHashMap<>((Map) nested)
      }
    }
    return [:]
  }

  /**
   * Copies tools-loop policy from a matched recipe onto a routing-pass map (LLM router and attach fallbacks).
   */
  static void copyRecipeToolsLoopPolicyToRoutingPass(Map out, Map recipe) {
    if (!(out instanceof Map) || !(recipe instanceof Map)) {
      return
    }
    Map synthetic = [recipe: recipe]
    String supplement = toolsLoopPrefetchSupplementFromMatch(synthetic)
    if (supplement) {
      out.toolsLoopPrefetchSupplement = supplement
    }
    Map cfg = toolsLoopPrefetchSupplementConfigFromMatch(synthetic)
    if (!cfg.isEmpty()) {
      out.toolsLoopPrefetchSupplementConfig = cfg
    }
    List<String> requireTools = toolsLoopRequireSuccessfulToolsFromMatch(synthetic)
    if (!requireTools.isEmpty()) {
      out.toolsLoopRequireSuccessfulTools = requireTools
    }
    String writeVerification = recipe?.get('toolsLoopWriteVerification')?.toString()?.trim() ?: ''
    if (writeVerification) {
      out.toolsLoopWriteVerification = writeVerification
    }
    Object wvCfg = recipe?.get('writeVerification')
    if (wvCfg instanceof Map && !((Map) wvCfg).isEmpty()) {
      out.toolsLoopWriteVerificationConfig = new LinkedHashMap<>((Map) wvCfg)
    }
  }

  static Map writeVerificationConfigFromMatch(Map detMatch) {
    if (!(detMatch instanceof Map)) {
      return [:]
    }
    Map recipe = detMatch.recipe instanceof Map ? (Map) detMatch.recipe : null
    Object fromEntry = detMatch.get('writeVerification')
    if (fromEntry instanceof Map && !((Map) fromEntry).isEmpty()) {
      return new LinkedHashMap<>((Map) fromEntry)
    }
    Object fromRecipe = recipe?.get('writeVerification')
    if (fromRecipe instanceof Map && !((Map) fromRecipe).isEmpty()) {
      return new LinkedHashMap<>((Map) fromRecipe)
    }
    return [:]
  }

  static String toolsLoopWriteVerificationFromMatch(Map detMatch) {
    if (!(detMatch instanceof Map)) {
      return ''
    }
    String fromEntry = detMatch.get('toolsLoopWriteVerification')?.toString()?.trim() ?: ''
    if (fromEntry) {
      return fromEntry
    }
    Map recipe = detMatch.recipe instanceof Map ? (Map) detMatch.recipe : null
    return recipe?.get('toolsLoopWriteVerification')?.toString()?.trim() ?: ''
  }

  /**
   * Wire names that must succeed at least once before the tools loop may finish (from match entry or recipe).
   */
  static List<String> toolsLoopRequireSuccessfulToolsFromMatch(Map detMatch) {
    if (!(detMatch instanceof Map)) {
      return Collections.emptyList()
    }
    Map recipe = detMatch.recipe instanceof Map ? (Map) detMatch.recipe : null
    return mergeToolsLoopRequireSuccessfulTools(recipe, detMatch)
  }

  private static List<String> mergeToolsLoopRequireSuccessfulTools(Map recipe, Map entry) {
    LinkedHashSet<String> names = new LinkedHashSet<>()
    addWireNamesFromConfigList(names, entry?.get('toolsLoopRequireSuccessfulTools'))
    addWireNamesFromConfigList(names, recipe?.get('toolsLoopRequireSuccessfulTools'))
    return names.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(names))
  }

  private static void addWireNamesFromConfigList(LinkedHashSet<String> names, Object raw) {
    if (!(raw instanceof List)) {
      return
    }
    for (Object o : (List) raw) {
      String n = o?.toString()?.trim()
      if (n) {
        names.add(n)
      }
    }
  }

  /**
   * Telemetry + loop policy from a prefetch supplement result (site-agnostic keys — no per-use-case flags in orchestration).
   */
  static Map prefetchSupplementTelemetryOverlay(String supplementId, Map supplementResult, List<String> requireSuccessfulTools) {
    if (!(supplementId?.trim()) && (requireSuccessfulTools == null || requireSuccessfulTools.isEmpty())) {
      return Collections.emptyMap()
    }
    Map sup = supplementResult instanceof Map ? supplementResult : [:]
    Map overlay = new LinkedHashMap<>()
    String sid = (supplementId ?: '').toString().trim()
    if (sid) {
      overlay.put('toolsLoopPrefetchSupplement', sid)
    }
    if (requireSuccessfulTools != null && !requireSuccessfulTools.isEmpty()) {
      overlay.put('toolsLoopRequireSuccessfulTools', new ArrayList<>(requireSuccessfulTools))
    }
    String suggested = (sup.suggestedNewItemPath ?: '').toString().trim()
    String sibling = (sup.siblingPath ?: '').toString().trim()
    String banned = (sup.bannedAnchorPath ?: '').toString().trim()
    String resolvedType = (sup.resolvedContentTypeId ?: '').toString().trim()
    if (suggested) {
      overlay.put('toolsLoopSuggestedNewItemPath', suggested)
    }
    if (sibling) {
      overlay.put('toolsLoopSiblingTemplatePath', sibling)
    }
    if (banned) {
      overlay.put('toolsLoopBannedRepoPaths', Collections.singletonList(banned))
    }
    if (resolvedType) {
      overlay.put('toolsLoopPrefetchResolvedContentTypeId', resolvedType)
    }
    if (Boolean.FALSE.equals(sup.siblingGetContentPresent)) {
      overlay.put('toolsLoopSiblingGetContentRequired', Boolean.TRUE)
    }
    if (Boolean.TRUE.equals(sup.draftExtractReady)) {
      overlay.put('toolsLoopCreateFromChatDraftDraftExtractReady', Boolean.TRUE)
    }
    String priorLabel = (sup.priorAuthorLabelFromPrior ?: '').toString().trim()
    if (priorLabel) {
      overlay.put('toolsLoopPriorAuthorLabel', priorLabel)
    }
    Object derived = sup.priorDerivedRootFieldValues
    if (derived instanceof Map && !((Map) derived).isEmpty()) {
      overlay.put('toolsLoopPriorDerivedRootFieldValues', new LinkedHashMap<>((Map) derived))
    }
    Object nodeCands = sup.nodeSelectorCandidates
    if (nodeCands instanceof List && !((List) nodeCands).isEmpty()) {
      overlay.put('toolsLoopNodeSelectorCandidates', new ArrayList<>((List) nodeCands))
    }
    return Collections.unmodifiableMap(overlay)
  }

  /** Internal user-message when the loop must run repository tools but the model returned prose-only. */
  static String formatToolsLoopRequiredToolsGuardMessage(Map telemetry) {
    Map tel = telemetry instanceof Map ? telemetry : [:]
    List<String> required = []
    Object reqObj = tel.get('toolsLoopRequireSuccessfulTools')
    if (reqObj instanceof List) {
      for (Object o : (List) reqObj) {
        String n = o?.toString()?.trim()
        if (n) {
          required.add(n)
        }
      }
    }
    if (required.isEmpty()) {
      return ''
    }
    String suggested = (tel.toolsLoopSuggestedNewItemPath ?: '').toString().trim()
    String sibling = (tel.toolsLoopSiblingTemplatePath ?: '').toString().trim()
    List<String> banned = []
    Object banObj = tel.get('toolsLoopBannedRepoPaths')
    if (banObj instanceof List) {
      for (Object o : (List) banObj) {
        String p = o?.toString()?.trim()
        if (p) {
          banned.add(p)
        }
      }
    }
    StringBuilder sb = new StringBuilder()
    sb.append('[aiassistant: tools-loop required tools — internal]\n')
    sb.append('This recipe requires successful **').append(required.join('**, **')).append('** before you finish.\n')
    sb.append('Emit **real API tool_calls** — do not print `functions.WriteContent(...)` or numbered fake tool lists in prose.\n')
    String supplement = (tel.toolsLoopPrefetchSupplement ?: '').toString().trim()
    if (sibling && !'createFromChatDraft'.equals(supplement)) {
      sb.append('**GetContent** sibling template: `').append(sibling).append('`.\n')
    }
    if (suggested) {
      sb.append('**Target path:** `').append(suggested).append('`.\n')
    }
    if (Boolean.TRUE.equals(tel.get('toolsLoopCreateFromChatDraftDraftExtractReady'))) {
      sb.append(
        '**Prior-chat draft** is in **[Prior conversation]** — build **contentXml** from **GetContentTypeFormDefinition** (parent + nested types); copy title/body verbatim from chat; do not copy sibling field values.\n'
      )
    }
    if (ToolsLoopWriteVerification.isActiveVerificationId(tel.get('toolsLoopWriteVerification')?.toString())) {
      sb.append(
        '**Write verification:** **WriteContent** is rejected until **contentXml** passes server checks (distinct UUIDs, verified node-selector paths, full body). Fix errors from the tool result and call **WriteContent** again. Topics/tags are your assignment from prefetch + draft — server does not enforce taxonomy fit.\n'
      )
    }
    if (!banned.isEmpty()) {
      sb.append('**Banned paths:** ')
      banned.eachWithIndex { String p, int i ->
        if (i > 0) {
          sb.append(', ')
        }
        sb.append('`').append(p).append('`')
      }
      sb.append('.\n')
    }
    return sb.toString()
  }

  /** Author-visible message when required tools never succeeded. */
  static String formatToolsLoopRequiredToolsMissedMessage(Map telemetry) {
    Map tel = telemetry instanceof Map ? telemetry : [:]
    List<String> required = []
    Object reqObj = tel.get('toolsLoopRequireSuccessfulTools')
    if (reqObj instanceof List) {
      for (Object o : (List) reqObj) {
        String n = o?.toString()?.trim()
        if (n) {
          required.add(n)
        }
      }
    }
    String suggested = (tel.toolsLoopSuggestedNewItemPath ?: '').toString().trim()
    String supplement = (tel.toolsLoopPrefetchSupplement ?: '').toString().trim()
    StringBuilder sb = new StringBuilder()
    sb.append('## Plan Execution\n\n')
    sb.append('❌ **Repository work did not complete.** Required tool(s): **')
      .append(required.isEmpty() ? 'WriteContent' : required.join('**, **'))
      .append('** did not succeed.\n\n')
    if (suggested) {
      sb.append('**Expected path:** `').append(suggested).append('`.\n\n')
    }
    if (supplement) {
      sb.append('_Prefetch supplement: `').append(supplement).append('`._\n\n')
    }
    sb.append('Retry with a shorter request, or complete the step manually in Studio.\n')
    sb.toString()
  }

  /** Stall-guard hint when required tools are still pending. */
  static String formatToolsLoopRequiredToolsStallHint(Map telemetry) {
    Map tel = telemetry instanceof Map ? telemetry : [:]
    if (!(tel.toolsLoopRequireSuccessfulTools instanceof List) || ((List) tel.toolsLoopRequireSuccessfulTools).isEmpty()) {
      return ''
    }
    String suggested = (tel.toolsLoopSuggestedNewItemPath ?: '').toString().trim()
    StringBuilder sb = new StringBuilder()
    sb.append('\n**Required tools:** ')
    sb.append(((List) tel.toolsLoopRequireSuccessfulTools).join(', '))
    sb.append('. ')
    if (suggested) {
      sb.append('Target **`').append(suggested).append('`**. ')
    }
    Object banObj = tel.get('toolsLoopBannedRepoPaths')
    if (banObj instanceof List && !((List) banObj).isEmpty()) {
      sb.append('Do **not** read/write banned preview anchor path(s) again. ')
    }
    sb.append('\n')
    return sb.toString()
  }

  /**
   * Builds the Studio user-message prelude after a recipe match: metadata, binding names, and phase hints
   * with {@link StudioRecipeClockTemplates} ({@code {{studio.today}}}, {@code {{studio.today-7D}}}, …) and
   * {@link AuthoringIntentRecipeBindings#expandHintTemplates} ({@code {{initial.*}}}/{@code {{current.*}}}) expansion.
   * Web-research recipes also append required round-0 search + {@code FetchHttpUrl} limits when
   * {@link #recipePhasesImplyWebResearch} and {@code toolsLoopForceTool} are set.
   */
  static String formatMatchedRecipePrelude(
    Map recipe,
    String recipeId,
    double confidence,
    String reason,
    Map<String, Map> initialBindings,
    Map<String, Map> currentBindings
  ) {
    return formatMatchedRecipePrelude(recipe, recipeId, confidence, reason, initialBindings, currentBindings, null)
  }

  /**
   * @param authorVisible author-visible turn text; when it contains http(s) URLs, web-research preludes
   *   instruct {@code FetchHttpUrl} on those links before Serp-only fetches
   */
  static String formatMatchedRecipePrelude(
    Map recipe,
    String recipeId,
    double confidence,
    String reason,
    Map<String, Map> initialBindings,
    Map<String, Map> currentBindings,
    String authorVisible
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
    Map executionPlan = AuthoringIntentRecipePlanCompiler.compile(recipe)
    String planBlock = AuthoringIntentRecipePlanCompiler.formatExecutionPlanWireBlock(executionPlan)
    if (planBlock?.trim()) {
      sb.append(planBlock)
    }
    sb.append('\n**Phase hints** (visitor-visible outcomes; mirror these in **## Plan** — Action steps are yours to execute in chat/tools):\n\n')
    appendPhase(sb, 'Context', recipe?.phases, 'context', initialBindings, currentBindings, maxExpand)
    appendPhase(sb, 'Action', recipe?.phases, 'action', initialBindings, currentBindings, maxExpand)
    appendPhase(sb, 'Confirmation', recipe?.phases, 'confirmation', initialBindings, currentBindings, maxExpand)
    String searchWire = resolveToolsLoopForceTool(recipe)
    if (recipePhasesImplyWebResearch(recipe) && searchWire) {
      int fetchCap = resolveFetchHttpUrlWireMaxChars(recipe)
      sb.append('\n**Tool execution (required):**\n')
      int maxFetch = resolveToolsLoopMaxFetchHttpUrlCalls(recipe)
      List<String> authorUrls = extractAuthorHttpUrls(authorVisible)
      boolean authorUrlExclusive = recipeToolsLoopAuthorUrlExclusive(recipe) && !authorUrls.isEmpty()
      if (authorUrlExclusive) {
        sb.append('- **Author URL-only mode:** **Current request** includes http(s) link(s) — use **FetchHttpUrl** on **only** those author URL(s). **Do not** call **SerpApiWebSearch**, **WebSearch**, or **FetchHttpUrl** on any other URL.\n')
        sb.append('- **Author-provided URL(s):** ')
        for (int i = 0; i < authorUrls.size(); i++) {
          if (i > 0) {
            sb.append('; ')
          }
          sb.append(authorUrls.get(i))
        }
        sb.append('\n')
        sb.append('- Call **FetchHttpUrl** on each distinct author URL above (pass **maxChars: ')
          .append(fetchCap > 0 ? fetchCap : DEFAULT_WEB_RESEARCH_FETCH_HTTP_WIRE_MAX_CHARS)
          .append('**; **at most ')
          .append(maxFetch > 0 ? Math.min(maxFetch, authorUrls.size()) : authorUrls.size())
          .append('** fetches total). The draft must reflect what you read from the author’s link(s).\n')
        sb.append('- After those reads, **complete the deliverable in chat** using the **##** section headings required by this recipe (see **matchedUserPrelude** / confirmation passthrough) — **no** **ResearchSiteContent**, **no SerpApiWebSearch**, and no more **FetchHttpUrl** on this turn.\n')
      } else if (!authorUrls.isEmpty()) {
        sb.append('- **Author-provided URL(s) in Current request (fetch first):** ')
        for (int i = 0; i < authorUrls.size(); i++) {
          if (i > 0) {
            sb.append('; ')
          }
          sb.append(authorUrls.get(i))
        }
        sb.append('\n')
        sb.append('- Call **FetchHttpUrl** on each distinct author URL above **before** treating Serp hits as primary sources (counts toward the per-turn fetch limit; pass **maxChars: ')
          .append(fetchCap > 0 ? fetchCap : DEFAULT_WEB_RESEARCH_FETCH_HTTP_WIRE_MAX_CHARS)
          .append('**). The draft must reflect what you read from the author’s link.\n')
        sb.append('- **SerpApiWebSearch** supplements the author link (at most **two** attempts) — **do not** skip the author URL when they gave one.\n')
        sb.append('- Additional **FetchHttpUrl** calls may use http(s) URLs from Serp results (still **at most ')
          .append(maxFetch > 0 ? maxFetch : 3)
          .append('** distinct fetches total). **Do not invent URLs**.\n')
      } else {
        sb.append('- Round 0: call **').append(searchWire).append('** via API **`tool_calls`** (not emoji-only 🛠️ narration).\n')
        sb.append('- Use **only** http(s) URLs returned in that search result — **do not invent or guess URLs**.\n')
        sb.append('- Fetch **at most ').append(maxFetch > 0 ? maxFetch : 3).append('** distinct citation URLs with **FetchHttpUrl** (pass **maxChars: ')
          .append(fetchCap > 0 ? fetchCap : DEFAULT_WEB_RESEARCH_FETCH_HTTP_WIRE_MAX_CHARS)
          .append('** on each call). Do **not** re-fetch the same URL.\n')
      }
      sb.append('- After those reads, **complete the deliverable in chat** from snippets and successful fetches — do **not** run more **FetchHttpUrl** / **')
        .append(searchWire).append('** unless a fetch failed.\n')
      sb.append('- Respond as **markdown in chat** unless the author explicitly asks to save or publish in the CMS.\n')
      sb.append('- Do **not** call **WriteContent**, **update_content**, or **GetCrafterizingPlaybook** for this workflow unless the author explicitly requests repository saves.\n')
    }
    sb.append('\n---\n\n')
    sb.toString()
  }

  /** Optional extra author-facing prelude text from the recipe row ({@code matchedUserPrelude}). */
  static String matchedUserPrelude(Map recipe) {
    String p = recipe?.get('matchedUserPrelude')?.toString()?.trim()
    return p ?: ''
  }

  /**
   * Deterministic-match prelude override when {@code matchedUserPreludeOverride} is set on the winning entry
   * (e.g. create-from-chat-draft vs generic new item on the same recipe id).
   */
  static String matchedUserPreludeFromMatch(Map detMatch) {
    if (!(detMatch instanceof Map)) {
      return ''
    }
    String fromEntry = detMatch.get('matchedUserPreludeOverride')?.toString()?.trim() ?: ''
    return fromEntry
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
   * All author-facing phase hint lines ({@code phases.context|action|confirmation} lists or {@code hints}).
   */
  static List<String> collectPhaseHintTexts(Map recipe) {
    List<String> out = new ArrayList<>()

    if (!(recipe instanceof Map)) {
      return Collections.unmodifiableList(out)
    }

    Object phases = recipe.get('phases')

    if (!(phases instanceof Map)) {
      return Collections.unmodifiableList(out)
    }

    for (String phaseKey : ['context', 'action', 'confirmation']) {
      Object raw = ((Map) phases).get(phaseKey)

      if (raw instanceof List) {
        for (Object line : (List) raw) {
          String s = line?.toString()?.trim()

          if (s) {
            out.add(s)
          }
        }
        continue
      }

      if (raw instanceof Map) {
        Object hints = ((Map) raw).get('hints')

        if (!(hints instanceof List)) {
          hints = ((Map) raw).get('lines')
        }

        if (hints instanceof List) {
          for (Object line : (List) hints) {
            String s = line?.toString()?.trim()

            if (s) {
              out.add(s)
            }
          }
        }
      }
    }

    Collections.unmodifiableList(out)
  }

  /** Lowercased join of {@link #collectPhaseHintTexts(Map)} for phrase checks. */
  private static String phaseHintTextBlob(Map recipe) {
    return collectPhaseHintTexts(recipe).join('\n').toLowerCase(Locale.ROOT)
  }

  /**
   * True when recipe phases (or explicit {@code toolsLoopForceTool}) call for public-web research
   * ({@code WebSearch} / citation fetch), same signals as bundled {@code web_research}.
   */
  static boolean recipePhasesImplyWebResearch(Map recipe) {
    String explicit = recipe?.toolsLoopForceTool?.toString()?.trim() ?: ''

    if (StudioAiAssistantProjectConfig.isWebSearchWireName(explicit)) {
      return true
    }

    String blob = phaseHintTextBlob(recipe)

    if (!blob) {
      return false
    }

    return blob.contains('search the web') ||
      blob.contains('websearch') ||
      blob.contains('web search') ||
      blob.contains('call websearch') ||
      blob.contains('serpapi')
  }

  /** {@code toolsLoopForceTool} from the recipe row only (no site-level substitution). */
  static String resolveToolsLoopForceTool(Map recipe) {
    return recipe?.toolsLoopForceTool?.toString()?.trim() ?: ''
  }

  /** True when {@code toolName} appears in the OpenAI {@code tools[]} wire list for this session. */
  static boolean isToolRegisteredOnWire(List wireTools, String toolName) {
    return wireToolsIncludeNamedTool(wireTools, toolName)
  }

  /**
   * Author-facing explanation when a recipe's {@code toolsLoopForceTool} is disabled or not registered.
   */
  static String explainToolsLoopForceToolUnavailable(String forceTool, Map cfg, StudioToolOperations ops) {
    String w = (forceTool ?: '').toString().trim()
    if (!w) {
      return 'No forced tool was configured.'
    }
    Set<String> disabled = StudioAiAssistantProjectConfig.disabledBuiltInSet(cfg)
    if (StudioAiAssistantProjectConfig.isToolNameDisabled(w, disabled)) {
      return "The tool **${w}** is disabled for this site (**disabledBuiltInTools** in **tools.json**)."
    }
    Set<String> wl = StudioAiAssistantProjectConfig.enabledBuiltInWhitelist(cfg)
    if (wl != null && !StudioAiAssistantProjectConfig.isBuiltInWireAllowedByWhitelist(w, cfg)) {
      return "The tool **${w}** is not included in this site's **enabledBuiltInTools** whitelist in **tools.json**."
    }
    return "The tool **${w}** is not registered for this chat session (check **tools.json** and agent **enableTools**)."
  }

  private static boolean wireToolsIncludeNamedTool(List wireTools, String toolName) {
    if (!(wireTools instanceof List) || wireTools.isEmpty() || !toolName?.trim()) {
      return false
    }
    String want = toolName.trim()
    for (def t : wireTools) {
      if (!(t instanceof Map)) {
        continue
      }
      def fn = ((Map) t).get('function')
      if (fn instanceof Map) {
        String n = (fn.get('name') ?: '').toString()
        if (want.equalsIgnoreCase(n)) {
          return true
        }
      }
    }
    return false
  }

  /** Max {@code FetchHttpUrl} calls per chat turn for web-research recipes (0 = no server cap). */
  static int resolveToolsLoopMaxFetchHttpUrlCalls(Map recipe) {
    Object raw = recipe?.get('toolsLoopMaxFetchHttpUrlCalls')
    if (raw instanceof Number) {
      int n = ((Number) raw).intValue()
      if (n >= 0) {
        return Math.min(n, 10)
      }
    } else if (raw != null) {
      try {
        int n = Integer.parseInt(raw.toString().trim())
        if (n >= 0) {
          return Math.min(n, 10)
        }
      } catch (Throwable ignored) {
      }
    }
    return recipeToolsLoopIsWebResearchOnly(recipe) || recipePhasesImplyWebResearch(recipe) ? 3 : 0
  }

  /** Default cap for {@code FetchHttpUrl} HTML on the tools-loop wire for web-research-only recipes. */
  static final int DEFAULT_WEB_RESEARCH_FETCH_HTTP_WIRE_MAX_CHARS = 10_000

  /**
   * True when {@code toolsLoopAllowlist} is non-empty and only names open-web search wire(s) and/or {@code FetchHttpUrl}.
   */
  static boolean recipeToolsLoopIsWebResearchOnly(Map recipe) {
    List<String> allow = toolsLoopAllowlistNames(recipe)

    if (allow.isEmpty()) {
      String force = resolveToolsLoopForceTool(recipe)
      return recipePhasesImplyWebResearch(recipe) && StudioAiAssistantProjectConfig.isWebSearchWireName(force)
    }

    Set<String> allowed = new LinkedHashSet<>(['WebSearch', 'SerpApiWebSearch', 'FetchHttpUrl'])

    for (String n : allow) {
      if (!allowed.contains(n)) {
        return false
      }
    }

    return true
  }

  /** Per-recipe or default {@code FetchHttpUrl} body cap on the chat wire for web-research workflows. */
  static int resolveFetchHttpUrlWireMaxChars(Map recipe) {
    Object raw = recipe?.get('toolsLoopFetchHttpUrlWireMaxChars')

    if (raw instanceof Number) {
      int n = ((Number) raw).intValue()

      if (n > 256) {
        return Math.min(n, 24_000)
      }
    } else if (raw != null) {
      try {
        int n = Integer.parseInt(raw.toString().trim())

        if (n > 256) {
          return Math.min(n, 24_000)
        }
      } catch (Throwable ignored) {
      }
    }

    return recipeToolsLoopIsWebResearchOnly(recipe) || recipePhasesImplyWebResearch(recipe) ?
      DEFAULT_WEB_RESEARCH_FETCH_HTTP_WIRE_MAX_CHARS :
      0
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
    String forceTool = resolveToolsLoopForceTool(recipe)
    boolean webResearchOnly = recipeToolsLoopIsWebResearchOnly(recipe)
    int fetchWireCap = resolveFetchHttpUrlWireMaxChars(recipe)

    if (allow.isEmpty() && bypass.isEmpty() && exclude.isEmpty() && !toolsOff && !forceTool && fetchWireCap <= 0) {
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

    if (fetchWireCap > 0 && (webResearchOnly || recipePhasesImplyWebResearch(recipe))) {
      extra.put('toolsLoopFetchHttpUrlWireMaxChars', fetchWireCap)
    }

    if (webResearchOnly) {
      extra.put('toolsLoopWebResearchOnly', Boolean.TRUE)
    }

    int maxFetchCalls = resolveToolsLoopMaxFetchHttpUrlCalls(recipe)
    if (maxFetchCalls > 0 && (webResearchOnly || recipePhasesImplyWebResearch(recipe))) {
      extra.put('toolsLoopMaxFetchHttpUrlCalls', maxFetchCalls)
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
