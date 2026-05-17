package plugins.org.craftercms.aiassistant.recipes

import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.List
import java.util.Locale
import java.util.Map

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

  /** Loaded from recipe catalog {@code chatDefaults} (site override merges at load). */
  private static volatile Map<String, String> catalogChatDefaultsRef = defaultCatalogChatDefaults()

  private static Map<String, String> defaultCatalogChatDefaults() {
    Map d = new LinkedHashMap<>()
    d.put('prefixEmoji', '🥗')
    d.put('fallbackEmoji', '📋')
    d.put('lineSuffix', 'workflow')
    return Collections.unmodifiableMap(d)
  }

  static Map<String, String> catalogChatDefaults() {
    Map<String, String> d = catalogChatDefaultsRef
    return d != null ? d : defaultCatalogChatDefaults()
  }

  /**
   * Last-resort catalog when the JSON file is not on disk / classpath (marketplace copy often omits sibling files).
   * Keep in sync with {@link #BUNDLED_RELATIVE}.
   */
  private static final String BUNDLED_RECIPES_JSON_EMBEDDED = '''{
  "version": 1,
  "recipes": [
    {
      "id": "open_page_inquiry",
      "title": "Describe this page (read-only)",
      "chatEmoji": "📖",
      "toolsLoopForceTool": "GetContent",
      "toolsLoopAllowlist": ["GetContent", "GetPreviewHtml"],
      "deterministicMatch": {
        "signal": "open_page_inquiry",
        "priority": 93,
        "routerReason": "deterministic_open_page_inquiry"
      },
      "phases": {
        "context": {
          "engineSteps": [
            { "as": "pageItem", "tool": "GetContent", "args": { "siteId": "$siteId", "path": "$contentPath" } }
          ]
        },
        "action": ["Answer what the page is about from XML in plain prose."],
        "confirmation": ["Do not WriteContent unless the author asks to edit."]
      }
    },
    {
      "id": "modify_page_content",
      "title": "Modify page or component content",
      "description": "Change copy, tone, grammar, or field values on a page or component XML item. If the user provides the page URL, Title or Internal name then they may not be talking about the current page - look up the page path first.",
      "matchHints": ["update", "change", "rewrite", "proofread", "grammar", "tone", "rephrase", "look up", "fetch"],
      "phases": {
        "context": {
          "hints": [
            "If the author gives a page URL, title, or internal name, resolve the correct repository path before GetContent — they may not mean the item currently open in Studio.",
            "Load the target item with GetContent (and GetContentTypeFormDefinition with contentPath when the form model matters)."
          ],
          "engineSteps": [
            { "as": "pageItem", "tool": "GetContent", "args": { "siteId": "$siteId", "path": "$contentPath" } },
            { "as": "pageForm", "tool": "GetContentTypeFormDefinition", "args": { "siteId": "$siteId", "contentPath": "$contentPath" } }
          ]
        },
        "action": ["Use update_content or GetContent → revise XML → WriteContent; preserve <page>/<component> structure and node-selector shapes."],
        "confirmation": ["When an Engine preview URL exists, use GetPreviewHtml after substantive writes affecting rendered output."]
      }
    },
    {
      "id": "translate_content_item",
      "title": "Translate or localize content",
      "description": "Author explicitly asks to translate or localize page/component copy into another language. Use TranslateContentItem or TranslateContentBatch (with ListContentTranslationScope for full-page scope) — not update_content or same-language rewrite via GetContent→WriteContent.",
      "matchHints": ["translate", "translation", "localize", "localise", "localization", "localisation", "language"],
      "phases": {
        "context": {
          "hints": [
            "Full page / this page: ListContentTranslationScope once on contentPath, then TranslateContentBatch or TranslateContentItem per path.",
            "Open item only: TranslateContentItem on contentPath; skip ListContentTranslationScope unless scope expands."
          ],
          "engineSteps": [
            { "tool": "GetContent", "args": { "siteId": "$siteId", "path": "$contentPath" } },
            { "tool": "GetContentTypeFormDefinition", "args": { "siteId": "$siteId", "contentPath": "$contentPath" } }
          ]
        },
        "action": ["TranslateContentItem or TranslateContentBatch with explicit target language from the author."],
        "confirmation": ["Optional GetPreviewHtml when preview URL exists after translation writes."]
      }
    },
    {
      "id": "generate_image",
      "title": "Generate image (bitmap)",
      "description": "Author wants a new AI-generated image, illustration, art, logo, or picture",
      "matchHints": ["generate", "draw", "image", "picture", "illustration", "hero", "cover", "banner", "logo", "artwork", "graphic", "bitmap", "sketch", "paint"],
      "toolsLoopAllowlist": ["GenerateImage"],
      "toolsLoopAllowlistBypassIfAuthorMentions": ["WriteContent", "write content", "save to", "update_content", "image-picker", "static-assets", "upload to repo"],
      "phases": {
        "context": ["Build GenerateImage prompt from author words; skip GetContent unless prompt detail is missing."],
        "action": ["Call GenerateImage in the first tool round with a concrete prompt."],
        "confirmation": ["Short prose wrap-up only — image appears in the Studio chat image strip."]
      }
    },
    {
      "id": "template_display_change",
      "title": "Template / display (FTL) change",
      "description": "Author explicitly wants layout, FreeMarker, listing markup, dates formatting in code, or how the page renders.",
      "matchHints": ["template", "ftl", "freemarker", "render", "layout", "listing", "cards", "display"],
      "phases": {
        "context": ["GetContent on page/component XML; read display-template; follow sections_o keys to component templates when the shell is not the listing."],
        "action": ["Read templates with GetContent or analyze_template (read-only) before update_template; persist with WriteContent on .ftl paths."],
        "confirmation": ["GetPreviewHtml when preview URL is available."]
      }
    },
    {
      "id": "publish_site",
      "title": "Publish entire site / first go-live",
      "description": "Author wants entire site, everything, or first publish — publish_content publishScope=all.",
      "matchHints": ["publish entire site", "publish everything", "first publish"],
      "phases": {
        "context": ["Confirm siteId; use publishScope=all when site never published."],
        "action": ["publish_content with publishScope=all — not only open contentPath."],
        "confirmation": ["Report publishScope and counts; never claim whole site on single-path deploy."]
      }
    },
    {
      "id": "publish_item",
      "title": "Publish or go live",
      "description": "Author wants one item, a path list, or bulk subtree — not entire-site first publish.",
      "matchHints": ["publish", "go live", "deploy", "push to live", "release"],
      "phases": {
        "context": ["Confirm siteId, scope (item/paths/bulk), and path(s)."],
        "action": ["publish_content with publishScope item|paths|bulk per scope."],
        "confirmation": ["Summarize publishScope and tool outcome."]
      }
    },
    {
      "id": "new_content_item",
      "title": "Create new page or component",
      "description": "Author asks to create, draft, or write a new item (new URL or new component), not only edit the open file.",
      "matchHints": ["create", "new page", "new article", "draft", "write a", "add a page"],
      "phases": {
        "context": {
          "hints": ["ListStudioContentTypes (siteId only) then exact catalog match; GetContentTypeFormDefinition for resolved contentTypeId; GetContent on one sibling of the same type when siblings exist."],
          "engineSteps": [{ "tool": "ListStudioContentTypes", "args": { "siteId": "$siteId", "searchable": false } }]
        },
        "action": ["WriteContent the new item with correct conventions (objectId, dates, file-name, sections)."],
        "confirmation": ["Tell the author how to preview the new route; optional GetPreviewHtml."]
      }
    }
  ]
}'''

  /**
   * @return immutable list of recipe maps (each may contain id, title, description, matchHints, phases)
   */
  static List<Map> loadRecipes(StudioToolOperations ops, Map projectCfg) {
    List<Map> merged = new ArrayList<>()
    Set<String> seen = new LinkedHashSet<>()
    for (Map r : parseBundledRecipes()) {
      String id = r?.id?.toString()?.trim()
      if (!id) {
        continue
      }
      merged.add(new LinkedHashMap<>(r))
      seen.add(id)
    }
    List<String> catalogOrder = parseBundledRecipeOrder()
    List<String> siteOrder = []
    String sitePath = StudioAiAssistantProjectConfig.intentRecipeCustomRecipesPath(projectCfg)
    if (ops != null && sitePath?.trim()) {
      try {
        String siteId = ops.resolveEffectiveSiteId('')
        String raw = ops.readStudioConfigurationUtf8(siteId, sitePath.trim())
        if (raw?.trim()) {
          siteOrder = parseRecipeOrderFromJsonText(raw)
          parseCatalogDocument(raw)
          for (Map r : parseRecipesArrayFromJsonText(raw)) {
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

  private static List<String> parseBundledRecipeOrder() {
    String raw = loadBundledRecipesJsonText()
    if (!raw?.trim()) {
      raw = BUNDLED_RECIPES_JSON_EMBEDDED
    }
    return parseRecipeOrderFromJsonText(raw)
  }

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

  private static List<Map> parseBundledRecipes() {
    String raw = loadBundledRecipesJsonText()
    if (!raw?.trim()) {
      log.warn(
        'AuthoringIntentRecipeCatalog: missing bundled {} on disk/classpath — using embedded default catalog (deploy {} under config/studio/scripts/classes/{}/ or set JVM {} to override)',
        BUNDLED_RELATIVE,
        BUNDLED_RELATIVE,
        PACKAGE_RESOURCE_PREFIX,
        SYSPROP_BUNDLED_PATH
      )
      raw = BUNDLED_RECIPES_JSON_EMBEDDED
    }
    Map doc = parseCatalogDocument(raw)
    return doc?.recipes ?: []
  }

  /**
   * Studio loads Groovy from {@code config/studio/scripts/classes/…} on disk; JSON beside those sources is not
   * always visible to {@link Class#getResourceAsStream(String)}. Try peer resource, package classpath, then code-source directory.
   */
  private static String loadBundledRecipesJsonText() {
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

    String fromStream = readUtf8FromResourceStream(AuthoringIntentRecipeCatalog.class.getResourceAsStream(BUNDLED_RELATIVE))
    if (fromStream?.trim()) {
      return fromStream
    }

    ClassLoader cl = AuthoringIntentRecipeCatalog.class.classLoader
    String pkgPath = "${PACKAGE_RESOURCE_PREFIX}${BUNDLED_RELATIVE}"
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
        File base = new File(loc.toURI())
        if (base.isFile()) {
          base = base.parentFile
        }
        if (base != null && base.isDirectory()) {
          File candidate = new File(base, BUNDLED_RELATIVE)
          if (candidate.isFile()) {
            return candidate.getText('UTF-8')
          }
        }
      }
    } catch (Throwable t) {
      log.debug('AuthoringIntentRecipeCatalog: code-source directory load failed: {}', t.message)
    }

    return ''
  }

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

  static List<Map> parseRecipesArrayFromJsonText(String raw) {
    return parseCatalogDocument(raw)?.recipes ?: []
  }

  /**
   * @return {@code [recipes: List<Map>, chatDefaults: Map]} or {@code null}
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
      catalogChatDefaultsRef = Collections.unmodifiableMap(mergeChatDefaults((Map) root))
      Object arr = ((Map) root).get('recipes')
      if (!(arr instanceof List)) {
        return [recipes: [], chatDefaults: catalogChatDefaults()]
      }
      List<Map> out = []
      for (Object o : (List) arr) {
        if (o instanceof Map) {
          out.add(new LinkedHashMap<>((Map) o))
        }
      }
      return [recipes: out, chatDefaults: catalogChatDefaults()]
    } catch (Throwable t) {
      log.warn('AuthoringIntentRecipeCatalog: JSON parse failed: {}', t.message)
      return null
    }
  }

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
   * First matching recipe by {@code deterministicMatch} priority (catalog config only).
   * @return map with keys {@code recipe}, {@code recipeId}, {@code routerReason}, {@code skipPrefetch}, {@code visible}
   */
  static Map findDeterministicRecipeMatch(List<Map> recipes, Map ctx) {
    if (recipes == null || recipes.isEmpty() || !(ctx instanceof Map)) {
      return null
    }
    Map openPage = tryBuiltinOpenPageInquiryMatch(recipes, ctx)
    if (openPage != null) {
      return openPage
    }
    List<Map> candidates = []
    for (Map recipe : recipes) {
      if (!(recipe instanceof Map)) {
        continue
      }
      String rid = recipe.id?.toString()?.trim()
      if (!rid) {
        continue
      }
      for (Map entry : deterministicMatchEntries(recipe)) {
        String sig = entry.signal?.toString()?.trim()
        if (!sig) {
          continue
        }
        candidates.add([
          recipe      : recipe,
          recipeId    : rid,
          signal      : sig,
          priority    : entry.priority instanceof Number ? ((Number) entry.priority).intValue() : 0,
          routerReason: (entry.routerReason ?: "deterministic_${sig}").toString(),
          skipPrefetch: Boolean.TRUE.equals(entry.skipPrefetch)
        ])
      }
    }
    if (candidates.isEmpty()) {
      return null
    }
    candidates.sort { a, b -> (b.priority as Integer) <=> (a.priority as Integer) }
    for (Map c : candidates) {
      if (AuthoringIntentRecipeSignals.evaluate(c.signal as String, ctx)) {
        return [
          recipe      : c.recipe,
          recipeId    : c.recipeId,
          routerReason: c.routerReason,
          skipPrefetch: c.skipPrefetch,
          visible     : (ctx.routerVisible ?: '').toString()
        ]
      }
    }
    null
  }

  /**
   * Minimal read-only recipe when {@code authoring-intent-recipes-default.json} is missing from the Studio
   * classpath (embedded catalog fallback) or a site override removed {@code open_page_inquiry}.
   */
  static Map builtinOpenPageInquiryRecipeFallback() {
    return [
      id                : 'open_page_inquiry',
      title             : 'Describe this page (read-only)',
      chatEmoji         : '📖',
      toolsLoopForceTool: 'GetContent',
      toolsLoopAllowlist: ['GetContent', 'GetPreviewHtml'],
      matchedUserPrelude:
        '[Studio — read-only page inquiry] Summarize what this anchored page is about using prefetch or GetContent XML. ' +
        'Answer in clear prose only. Do not WriteContent unless the author asks to edit.',
      phases: [
        context: [
          hints: [
            'Prefetch loads pageItem (XML) for the anchored path when available.',
            'If prefetch already includes full contentXml for the anchored path, do not call GetContent again on that path.'
          ],
          engineSteps: [
            [as: 'pageItem', tool: 'GetContent', args: [siteId: '$siteId', path: '$contentPath']]
          ]
        ],
        action       : ['Answer what the page is about from XML fields in plain prose.'],
        confirmation : ['Do not call WriteContent unless the author asks to change content.']
      ]
    ]
  }

  /**
   * Anchored “what is / what would you say this page is about” — always route to {@code open_page_inquiry}
   * even when site recipe JSON replaced {@code deterministicMatch} on that row.
   */
  private static Map tryBuiltinOpenPageInquiryMatch(List<Map> recipes, Map ctx) {
    String prompt = (ctx.cand ?: ctx.routerVisible ?: '').toString()
    if (AuthoringPreviewContext.authorCurrentRequestLooksLikeImageOnlyGenerate(prompt)) {
      return null
    }
    if (!AuthoringPreviewContext.authorVisibleSuggestsOpenPageInquiry(prompt)) {
      return null
    }
    Map recipe = findRecipeById(recipes, 'open_page_inquiry')
    boolean usedFallback = false
    if (recipe == null) {
      recipe = builtinOpenPageInquiryRecipeFallback()
      usedFallback = true
      log.warn(
        'Intent recipe routing: open_page_inquiry missing from merged catalog — using builtin fallback recipe (deploy {} on Studio classpath)',
        BUNDLED_RELATIVE
      )
    } else {
      log.info('Intent recipe routing: builtin open_page_inquiry → open_page_inquiry')
    }
    return [
      recipe      : recipe,
      recipeId    : 'open_page_inquiry',
      routerReason: usedFallback ? 'deterministic_open_page_inquiry_fallback' : 'deterministic_open_page_inquiry',
      skipPrefetch: false,
      visible     : (ctx.routerVisible ?: '').toString()
    ]
  }

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

  static boolean recipeExcludedByDontMatchHints(Map recipe, String authorVisible) {
    List<String> dont = dontMatchHintsList(recipe)
    return !dont.isEmpty() && authorVisibleMatchesKeywordList(authorVisible, dont)
  }

  static boolean authorVisibleMatchesKeywordList(String authorVisible, List<String> keywords) {
    authorVisibleMatchesOrchestrationBypass(authorVisible, keywords)
  }

  private static List<String> matchHintsList(Map recipe) {
    hintStringList(recipe?.get('matchHints'))
  }

  private static List<String> dontMatchHintsList(Map recipe) {
    hintStringList(recipe?.get('dontMatchHints'))
  }

  private static List<String> hintStringList(Object raw) {
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

  private static String escMdCell(String s) {
    (s ?: '').replace('|', '/').replace('\n', ' ').trim()
  }

  private static String trimDesc(String s, int max) {
    String t = (s ?: '').replace('\n', ' ').trim()
    if (t.length() <= max) {
      return t
    }
    return t.substring(0, max) + '…'
  }

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
  static String formatIntentRecipeChatLine(Map recipe) {
    if (!(recipe instanceof Map) || recipe.isEmpty()) {
      return ''
    }
    Map<String, String> defs = catalogChatDefaults()
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

  static String formatMatchedRecipePrelude(Map recipe, String recipeId, double confidence, String reason) {
    return formatMatchedRecipePrelude(recipe, recipeId, confidence, reason, [:], [:])
  }

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

  static String matchedUserPrelude(Map recipe) {
    String p = recipe?.get('matchedUserPrelude')?.toString()?.trim()
    return p ?: ''
  }

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

  static Map orchestrationTelemetryExtras(Map recipe) {
    List<String> allow = toolsLoopAllowlistNames(recipe)
    List<String> bypass = toolsLoopAllowlistBypassKeywords(recipe)
    boolean toolsOff = Boolean.TRUE.equals(recipe?.get('toolsLoopDisable'))
    String forceTool = recipe?.toolsLoopForceTool?.toString()?.trim() ?: ''
    if (allow.isEmpty() && bypass.isEmpty() && !toolsOff && !forceTool) {
      return Collections.emptyMap()
    }
    Map extra = new LinkedHashMap<>()
    if (!allow.isEmpty()) {
      extra.put('toolsLoopAllowlist', allow)
    }
    if (!bypass.isEmpty()) {
      extra.put('toolsLoopAllowlistBypassIfAuthorMentions', bypass)
    }
    if (toolsOff) {
      extra.put('toolsLoopDisable', Boolean.TRUE)
    }
    if (forceTool) {
      extra.put('toolsLoopForceTool', forceTool)
    }
    if (Boolean.TRUE.equals(recipe?.get('prefetchHotpathForceWrite'))) {
      extra.put('prefetchHotpathForceWrite', Boolean.TRUE)
    }
    if (Boolean.TRUE.equals(recipe?.get('serverHotpathExternalContent'))) {
      extra.put('serverHotpathExternalContent', Boolean.TRUE)
    }
    Collections.unmodifiableMap(extra)
  }

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
}
