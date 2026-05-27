package plugins.org.craftercms.aiassistant.tools

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.tools.operations.StudioToolOperationsSupport
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts

import org.craftercms.studio.api.v2.event.site.SyncFromRepoEvent
import org.dom4j.Document
import org.dom4j.DocumentException
import org.dom4j.Element
import org.dom4j.io.SAXReader
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType
import org.opensearch.client.opensearch.core.SearchRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.support.WebApplicationContextUtils

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Collections
import java.util.Base64
import java.util.List
import java.util.Locale
import java.util.Set
import java.util.regex.Pattern
import java.util.TimeZone

import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Studio integration layer for built-in tools: Spring beans, security context, config I/O, and repository write primitives.
 * Tool behavior lives in {@code *Tool.groovy} classes and {@code tools/cms/support}, {@code tools/http}, etc.
 * Keeps bean lookup + Studio API access out of orchestration classes.
 * <p><strong>Crafter Studio {@code support/4.x}</strong> (<a href="https://github.com/craftercms/studio/tree/support/4.x">studio support/4.x</a>):
 * bean {@code contentService} (v2 {@code org.craftercms.studio.api.v2.service.content.ContentService}) supports reads such as
 * {@code getContentByCommitId(siteId, path, commitId)} (use {@code HEAD} for sandbox tip), {@code getContentVersionHistory(siteId, path)}, etc.
 * <strong>That interface has no {@code write} on 4.x</strong> — repository writes (including all {@code .xml}) must use v1 bean
 * {@code cstudioContentService}. For {@code /site/...} paths use 8-arg {@code writeContent} (form/asset {@code processContent} pipeline in
 * {@code ContentServiceImpl#doWriteContent} on {@code support/4.x}) plus {@code notifyContentEvent} and {@link org.craftercms.studio.api.v2.event.site.SyncFromRepoEvent}.
 * Do <strong>not</strong> use {@code writeContentAndNotify} for {@code /site/...}: it delegates to 3-arg {@code writeContent}, which skips {@code doWriteContent}
 * (git commits but item/sidebar state may not match Studio UI). Outside {@code /site/}, prefer {@code writeContentAndNotify} when available.
 * (Newer Studio branches may add v2 {@code write}; do not assume it here.)
 * Publish: {@code cstudioDeploymentService} ({@link org.craftercms.studio.api.v1.service.deployment.DeploymentService})
 * {@code deploy(site, environment, paths, scheduledDate, approver, submissionComment, scheduleDateNow)}.</p>
 * <p>Tools-loop / tool callbacks run on Reactor/HTTP client worker threads where {@link SecurityContextHolder} is empty.
 * Studio resolves the current user from that holder ({@code SecurityServiceImpl#getCurrentUser}), so we install a
 * <strong>copy</strong> of the servlet thread's context around bean calls.</p>
 */
class StudioToolOperations {
  private static final Logger log = LoggerFactory.getLogger(StudioToolOperations.class)
  private static volatile boolean LOGGED_MISSING_SECURITY_CONTEXT = false
  // Preview/HTTP URL helpers: plugins.org.craftercms.aiassistant.tools.operations.StudioToolOperationsSupport

  /** @deprecated use {@link StudioToolOperationsSupport#readCrafterPreviewTokenFromServletRequest} */
  static String readCrafterPreviewTokenFromServletRequest(def request) {
    return StudioToolOperationsSupport.readCrafterPreviewTokenFromServletRequest(request)
  }

  /** @deprecated use {@link StudioToolOperationsSupport#readCrafterQChatUserFromServletRequest} */
  static String readCrafterQChatUserFromServletRequest(def request) {
    return StudioToolOperationsSupport.readCrafterQChatUserFromServletRequest(request)
  }

  final def request
  final def applicationContext
  /** Plugin script params (URL query {@code siteId} is the Studio session site, not POST-body working site). */
  final def params
  /** Copy of {@link org.springframework.security.core.context.SecurityContext} from the Studio HTTP request thread. */
  final def securityContextForTools
  /**
   * Studio Experience Builder preview cookie value ({@code crafterPreview}), set on the HTTP request as attribute
   * {@code aiassistant.previewToken} by the chat/stream REST scripts when the UI POSTs it.
   */
  final String resolvedPreviewTokenFromRequest
  /**
   * Snapshot of {@code Cookie} from the Studio chat/stream servlet request at {@code StudioToolOperations} construction
   * (servlet thread). Tool callbacks run on worker threads where {@code request.getHeader("Cookie")} can be empty; Engine
   * preview GET still needs forwarded non-session cookies (e.g. other Studio cookies), plus {@code crafterPreview} / {@code crafterSite}.
   */
  final String frozenCookieHeaderFromRequest
  /** Resolved once: v2 {@code contentService} (reads/history only on studio {@code support/4.x} — no {@code write} there). */
  final Object contentServiceBean
  /** Resolved once for support/4.x direct calls. */
  final Object configurationServiceBean
  /** v1 deployment service bean {@code cstudioDeploymentService}. */
  final Object deploymentServiceBean
  /** Optional v2 {@code publishService} — {@code isSitePublished}, {@code publishAll}. */
  final Object publishServiceBean
  /** v1 {@code org.craftercms.studio.api.v1.service.content.ContentService} (bean {@code cstudioContentService}) for repository writes. */
  final Object cstudioContentServiceBean
  /**
   * Optional v1 {@code org.craftercms.studio.api.v1.service.content.ContentTypeService} — bean {@code cstudioContentTypeService}
   * (or {@code contentTypeService}) when Studio registers it. Used by {@link #listStudioContentTypes}; may be null in some deployments.
   */
  final Object contentTypeServiceBean

  StudioToolOperations(
    def request,
    def applicationContext,
    def params = null,
    def securityContextForTools = null
  ) {
    this.request = request
    this.applicationContext = applicationContext
    this.params = params
    this.securityContextForTools = securityContextForTools
    String frozenCookie = ''
    try {
      frozenCookie = request?.getHeader('Cookie')?.toString()?.trim() ?: ''
    } catch (Throwable ignored) {}
    this.frozenCookieHeaderFromRequest = frozenCookie
    this.resolvedPreviewTokenFromRequest = StudioToolOperationsSupport.readCrafterPreviewTokenFromServletRequest(request)
    this.contentServiceBean = resolveRequiredBean('contentService',
      'Studio bean contentService not found. The AI Assistant expects the same in-process content service Crafter Studio registers (support/4.x).')
    this.configurationServiceBean = resolveRequiredBean('configurationService',
      'Studio configurationService bean not found (configurationService). AI Assistant tools use the Studio JVM only.')
    this.deploymentServiceBean = resolveRequiredBean('cstudioDeploymentService',
      'Studio cstudioDeploymentService bean not found (DeploymentService).')
    Object ps = null
    try {
      ps = applicationContext?.get('publishService')
    } catch (Throwable ignoredPs) {}
    this.publishServiceBean = ps
    this.cstudioContentServiceBean = resolveRequiredBean('cstudioContentService',
      'Studio cstudioContentService bean not found (v1 ContentService). AI Assistant writeContent uses this bean.')
    Object cts = null
    try {
      cts = applicationContext?.get('cstudioContentTypeService')
    } catch (Throwable ignored) {}
    if (cts == null) {
      try {
        cts = applicationContext?.get('contentTypeService')
      } catch (Throwable ignored2) {}
    }
    this.contentTypeServiceBean = cts
  }

  /**
   * Exposed for site-authored Groovy under {@code config/studio/scripts/aiassistant/user-tools/} (see {@code StudioAiUserSiteTools}).
   * Prefer {@link StudioToolOperations} methods for repository work; use the context only when you need additional Spring beans.
   */
  Object studioApplicationContext() {
    applicationContext
  }

  /**
   * Looks up a Spring bean by name from the Studio application context.
   * Throws IllegalStateException with the supplied message when the bean is missing.
   * Used once from the constructor so downstream CMS calls fail fast with clear diagnostics.
   */
  private Object resolveRequiredBean(String name, String errorMessage) {
    def s = null
    try {
      s = applicationContext?.get(name)
    } catch (Throwable ignored) {}
    if (s == null) {
      throw new IllegalStateException(errorMessage)
    }
    return s
  }

  /**
   * Snapshot of the current servlet-thread {@link SecurityContext} for tool callbacks on worker threads.
   * Returns null when there is no authenticated principal (caller may still run tools without install).
   */
  static SecurityContext captureSecurityContextCopy() {
    try {
      SecurityContext ctx = SecurityContextHolder.getContext()
      def auth = ctx?.getAuthentication()
      if (auth != null && auth.isAuthenticated()) {
        SecurityContext copy = SecurityContextHolder.createEmptyContext()
        copy.setAuthentication(auth)
        return copy
      }
    } catch (Throwable ignored) {
    }
    return null
  }

  /** New ops instance with an updated security snapshot (same request/beans). */
  StudioToolOperations withCapturedSecurityContext(SecurityContext ctx) {
    if (ctx == null) {
      return this
    }
    return new StudioToolOperations(request, applicationContext, params, ctx)
  }

  /** Username of the authenticated Studio user (same context as permission checks). */
  static String currentAuthenticatedUsername() {
    Authentication auth = SecurityContextHolder.getContext()?.getAuthentication()
    if (auth == null || !auth.isAuthenticated()) {
      throw new IllegalStateException('No authenticated Studio user; publish_content requires a logged-in Studio user.')
    }
    String name = auth.getName()?.toString()?.trim()
    if (!name) {
      throw new IllegalStateException('Authenticated user has no principal name; cannot set deployment approver.')
    }
    return name
  }

  /**
   * Default parallel workers for {@code TranslateContentBatch} when the model does not pass {@code maxConcurrency}.
   * Set per request from stream POST {@code translateBatchConcurrency} (agents.json per-agent); clamped {@code 1..64};
   * falls back to {@code 25}.
   */
  int resolveTranslateBatchDefaultMaxConcurrency() {
    try {
      def v = request?.getAttribute('aiassistant.translateBatchConcurrency')
      if (v instanceof Number) {
        int n = ((Number) v).intValue()
        return Math.max(1, Math.min(64, n))
      }
      if (v != null) {
        int n = Integer.parseInt(v.toString().trim())
        return Math.max(1, Math.min(64, n))
      }
    } catch (Throwable ignored) {}
    return 25
  }

  /** Crafter permission checks use authenticated user from {@link SecurityContextHolder}. */
  <T> T runWithStudioSecurity(Closure<T> work) {
    return withStudioRequestSecurity(work)
  }

  private <T> T withStudioRequestSecurity(Closure<T> work) {
    if (securityContextForTools == null) {
      if (!LOGGED_MISSING_SECURITY_CONTEXT) {
        LOGGED_MISSING_SECURITY_CONTEXT = true
        log.warn(
          'StudioToolOperations: SecurityContext was not captured on the HTTP thread; tools-loop / tool callbacks may fail with SubjectNotFoundException. Ensure AiOrchestration builds the chat client on an authenticated servlet thread (or anonymous is not treated as authenticated in your Spring Security setup).'
        )
      }
      return work.call()
    }
    def previous = SecurityContextHolder.getContext()
    try {
      SecurityContextHolder.setContext(securityContextForTools)
      return work.call()
    } finally {
      SecurityContextHolder.setContext(previous)
    }
  }

  /**
   * CMS site for this turn. The POST-body working site ({@code aiassistant.siteId}) wins over
   * LLM-supplied {@code siteId} on tool calls (models often echo the Studio session site from context).
   */
  String resolveEffectiveSiteId(String fromTool) {
    def tool = (fromTool ?: '').toString().trim()
    def reqSite = ''
    try {
      reqSite = request?.getAttribute('aiassistant.siteId')?.toString()?.trim() ?: ''
      if (!reqSite) reqSite = request?.getParameter('siteId')?.toString()?.trim() ?: ''
      if (!reqSite) reqSite = request?.getParameter('crafterSite')?.toString()?.trim() ?: ''
      if (!reqSite && params != null) {
        try {
          reqSite = params['siteId']?.toString()?.trim() ?: ''
        } catch (Throwable e) {
          try {
            reqSite = params.siteId?.toString()?.trim() ?: ''
          } catch (Throwable e2) {}
        }
      }
    } catch (Throwable ignored) {}

    if (reqSite) {
      return reqSite
    }
    if (tool && !tool.equalsIgnoreCase('default')) {
      return tool
    }
    return tool
  }

  /** Studio UI / plugin URL site (not POST-body working site). */
  String resolveStudioSessionSiteId() {
    Map paramMap = null
    try {
      if (params instanceof Map) {
        paramMap = (Map) params
      }
    } catch (Throwable ignoredParams) {
    }
    return AuthoringPreviewContext.resolveStudioSessionSiteId(request, paramMap) ?: ''
  }

  /**
   * Snapshot of site id, preview/form repo path, content type id, and Engine preview URL for
   * {@link plugins.org.craftercms.aiassistant.recipes.AuthoringIntentRecipeEngine}. REST scripts set
   * {@code aiassistant.*} request attributes on the servlet thread before orchestration runs.
   */
  Map recipeEngineAuthoringBindings() {
    String siteId = resolveEffectiveSiteId(null)
    String contentPath = ''
    String contentTypeId = ''
    try {
      contentPath = AuthoringPreviewContext.normalizeRepoPath(
        request?.getAttribute('aiassistant.contentPath')?.toString()
      ) ?: ''
      if (!contentPath) {
        contentPath = AuthoringPreviewContext.normalizeRepoPath(
          request?.getAttribute('aiassistant.formEngineItemPath')?.toString()
        ) ?: ''
      }
      contentTypeId = request?.getAttribute('aiassistant.contentTypeId')?.toString()?.trim() ?: ''
    } catch (Throwable ignored) {
    }
    String previewUrl = ''
    try {
      previewUrl = AuthoringPreviewContext.buildEnginePreviewAbsoluteUrl(request, siteId, contentPath) ?: ''
    } catch (Throwable ignoredPu) {
    }
    return Collections.unmodifiableMap([
      siteId        : siteId ?: '',
      contentPath   : contentPath ?: '',
      contentTypeId : contentTypeId ?: '',
      previewUrl    : previewUrl ?: ''
    ] as Map)
  }

  /**
   * Reads an entire stream as UTF-8. Parameter is {@link Object} so Groovy resolves the call for JGit
   * {@code ObjectStream.SmallStream} and other {@link InputStream} implementations returned by Studio resources.
  /**
   * Reads Studio module configuration text (same API family as browser {@code get_configuration}, module {@code studio}).
   * Returns {@code null} or blank when the path does not exist or is empty.
   * <p>When the target is absent, uses {@code cstudioContentService.contentExists} first so Studio does not log
   * {@code ContentNotFoundException} at ERROR from {@code getConfigurationAsString} (e.g. optional {@code user-tools/registry.json}).</p>
   */
  String readStudioConfigurationUtf8(String siteId, String relativePath) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      def path = (relativePath ?: '').toString().trim()
      if (!path.startsWith('/')) {
        path = "/${path}"
      }
      String sandboxRepoPath = toSandboxConfigStudioRepoPath(path)
      try {
        Object v1 = cstudioContentServiceBean
        if (v1 != null && v1.metaClass.respondsTo(v1, 'contentExists', String, String)) {
          Object exists = v1.contentExists(siteId, sandboxRepoPath)
          if (!(exists instanceof Boolean ? ((Boolean) exists).booleanValue() : Boolean.TRUE.equals(exists))) {
            log.trace('readStudioConfigurationUtf8: skip read (missing) siteId={} modulePath={} repoPath={}', siteId, path, sandboxRepoPath)
            return null
          }
        } else if (v1 != null && v1.metaClass.respondsTo(v1, 'shallowContentExists', String, String)) {
          Object exists = v1.shallowContentExists(siteId, sandboxRepoPath)
          if (!(exists instanceof Boolean ? ((Boolean) exists).booleanValue() : Boolean.TRUE.equals(exists))) {
            log.trace('readStudioConfigurationUtf8: skip read (shallow missing) siteId={} modulePath={} repoPath={}', siteId, path, sandboxRepoPath)
            return null
          }
        }
      } catch (Throwable probeIgnored) {
        // If exists probe fails, fall through to configuration read (legacy behavior).
      }
      try {
        String xml = configurationServiceBean.getConfigurationAsString(siteId, 'studio', path, '')
        return xml
      } catch (Throwable t) {
        log.debug('readStudioConfigurationUtf8 failed siteId={} path={}: {}', siteId, path, t.message)
        return null
      }
    }
  }

  /** Site sandbox path for a Studio {@code studio} module path (e.g. {@code /scripts/...} → {@code /config/studio/scripts/...}). */
  private static String toSandboxConfigStudioRepoPath(String studioModuleRelativePath) {
    String p = (studioModuleRelativePath ?: '').toString().trim()
    if (!p.startsWith('/')) {
      p = "/${p}"
    }
    return "/config/studio${p}"
  }

  /**
   * Writes Studio module configuration bytes via {@link org.craftercms.studio.api.v2.service.config.ConfigurationService#writeConfiguration}.
   */
  void writeStudioConfiguration(String siteId, String relativePath, byte[] bytes) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      def path = (relativePath ?: '').toString().trim()
      if (!path.startsWith('/')) {
        path = "/${path}"
      }
      if (bytes == null) {
        bytes = new byte[0]
      }
      if (!configurationServiceBean.metaClass.respondsTo(configurationServiceBean, 'writeConfiguration', String, String, String, String, InputStream)) {
        throw new IllegalStateException('configurationService.writeConfiguration(String,String,String,String,InputStream) not available')
      }
      configurationServiceBean.writeConfiguration(siteId, 'studio', path, '', new ByteArrayInputStream(bytes))
    }
  }

  /**
   * Persists bytes at {@code fullPath} via v1 {@code cstudioContentService} (studio {@code support/4.x} has no v2 {@code ContentService#write}).
   * <p>Paths under {@code /site/} always use 8-arg {@code writeContent} so {@code processContent} runs (see
   * {@code ContentServiceImpl#doWriteContent} on {@code support/4.x}). {@code writeContentAndNotify} uses 3-arg {@code writeContent},
   * which skips that pipeline — fine for config/assets, wrong for site content that must appear in the Studio sidebar.</p>
   */
  Map writeRepositoryFile(String siteId, String fullPath, byte[] bytes, boolean unlockAfterWrite = true) {
    log.info('writeRepoFile start: siteId={} path={} bytes={} unlockAfterWrite={}', siteId, fullPath, (bytes?.length ?: 0), unlockAfterWrite)
    boolean siteSandboxPath = (fullPath ?: '').startsWith('/site/')
    boolean usedEightArgWrite = false

    if (siteSandboxPath) {
      def parts = splitRepoPath(fullPath)
      String unlockStr = unlockAfterWrite ? 'true' : 'false'
      cstudioContentServiceBean.writeContent(
        siteId,
        parts.dir,
        parts.file,
        mimeTypeForPath(fullPath),
        new ByteArrayInputStream(bytes),
        'true',
        'true',
        unlockStr
      )
      usedEightArgWrite = true
      log.debug('writeRepoFile wrote via 8-arg writeContent (site pipeline): siteId={} path={} dir={} file={} unlock={}',
        siteId, fullPath, parts.dir, parts.file, unlockStr)
    } else if (unlockAfterWrite && cstudioContentServiceBean.metaClass.respondsTo(cstudioContentServiceBean, 'writeContentAndNotify', String, String, InputStream)) {
      cstudioContentServiceBean.writeContentAndNotify(siteId, fullPath, new ByteArrayInputStream(bytes))
      log.debug('writeRepoFile wrote via writeContentAndNotify: siteId={} path={}', siteId, fullPath)
    } else {
      def parts = splitRepoPath(fullPath)
      String unlockStr = unlockAfterWrite ? 'true' : 'false'
      cstudioContentServiceBean.writeContent(
        siteId,
        parts.dir,
        parts.file,
        mimeTypeForPath(fullPath),
        new ByteArrayInputStream(bytes),
        'true',
        'true',
        unlockStr
      )
      usedEightArgWrite = true
      log.debug('writeRepoFile wrote via 8-arg writeContent: siteId={} path={} dir={} file={} unlock={}',
        siteId, fullPath, parts.dir, parts.file, unlockStr)
    }
    boolean notified = notifyContentEventWithDebug(siteId, fullPath, 'writeRepoFile')
    if (!notified) {
      throw new IllegalStateException("Content saved but preview refresh event failed for '${fullPath}'")
    }
    if (usedEightArgWrite) {
      publishSyncFromRepoForSite(siteId)
    }
    [ok: true, siteId: siteId, path: fullPath, notified: notified, result: 'written']
  }
  private static String normalizeLeadingSlash(def value, String fieldName) {
    def normalized = (value ?: '').toString().trim()
    if (!normalized) throw new IllegalArgumentException("Missing required parameter: ${fieldName}")
    if (!normalized.startsWith('/')) throw new IllegalArgumentException("${fieldName} must start with '/': ${normalized}")
    return normalized
  }

  /** Parent path and file name for v1 8-arg {@code writeContent} (path = directory, fileName separate). */
  private static Map splitRepoPath(String fullPath) {
    String normalized = fullPath
    int slash = normalized.lastIndexOf('/')
    if (slash < 0 || slash == normalized.length() - 1) {
      throw new IllegalArgumentException("Invalid repository path: ${normalized}")
    }
    String dir
    String file
    if (slash == 0) {
      dir = '/'
      file = normalized.substring(1)
    } else {
      dir = normalized.substring(0, slash)
      file = normalized.substring(slash + 1)
    }
    if (!file) throw new IllegalArgumentException("Invalid repository path (no file name): ${normalized}")
    [dir: dir, file: file]
  }
  private static final long MAX_REMOTE_IMAGE_BYTES = 25L * 1024 * 1024

  /**
   * Parses a raster {@code data:image/...;base64,...} URL into bytes and a normalized MIME type.
   * SVG and non-image data URLs are rejected.
   */
  private static Map parseRasterDataImageUrl(String dataUrl) {
    int comma = dataUrl.indexOf(',')
    if (comma < 5 || !dataUrl.regionMatches(true, 0, 'data:', 0, 5)) {
      throw new IllegalArgumentException('Malformed data URL')
    }
    String meta = dataUrl.substring(5, comma).trim()
    String metaLower = meta.toLowerCase(Locale.ROOT)
    if (!metaLower.startsWith('image/')) {
      throw new IllegalArgumentException('data URL must use an image/* mediatype')
    }
    if (metaLower.contains('image/svg')) {
      throw new IllegalArgumentException('SVG data URLs are not supported for import')
    }
    if (!metaLower.contains(';base64')) {
      throw new IllegalArgumentException('data URL must be base64-encoded')
    }
    int b64Idx = metaLower.indexOf(';base64')
    String mime = (b64Idx > 0 ? meta.substring(0, b64Idx) : meta).trim().toLowerCase(Locale.ROOT)
    if (!mime.startsWith('image/')) {
      throw new IllegalArgumentException('Invalid image mediatype in data URL')
    }
    String b64payload = dataUrl.substring(comma + 1).trim().replaceAll(/\s+/, '')
    byte[] bytes
    try {
      bytes = Base64.decoder.decode(b64payload)
    } catch (Throwable t) {
      throw new IllegalArgumentException("Invalid base64 in data URL: ${t.message}")
    }
    if (!bytes || bytes.length == 0) {
      throw new IllegalStateException('data URL image is empty')
    }
    if (bytes.length > MAX_REMOTE_IMAGE_BYTES) {
      throw new IllegalStateException("Image exceeds maximum size (${MAX_REMOTE_IMAGE_BYTES} bytes)")
    }
    [bytes: bytes, contentType: mime]
  }

  /**
   * Downloads an image from a remote {@code https} URL (SSRF-hardened), or decodes a raster
   * {@code data:image/...;base64,...} URL, writes bytes under {@code /static-assets/...} using the same
   * content service as desktop upload, and returns the repository path for image-picker fields.
   * <p>{@code repoPath} supports the same macros as the desktop image datasource: {@code {yyyy}}, {@code {mm}},
   * {@code {dd}}, {@code {objectId}}, {@code {objectGroupId}}.</p>
   */
  Map importImageFromRemoteUrl(
    String siteId,
    String imageUrl,
    String repoPathRaw,
    String optionalFileName = null,
    String optionalObjectId = null,
    String optionalObjectGroupId = null
  ) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      def normalizedUrl = (imageUrl ?: '').toString().trim()
      if (!normalizedUrl) {
        throw new IllegalArgumentException('Missing required field: imageUrl')
      }
      URI parsed
      try {
        parsed = URI.create(normalizedUrl)
      } catch (Throwable t) {
        throw new IllegalArgumentException("Invalid imageUrl: ${t.message}")
      }
      String scheme = parsed.scheme?.toLowerCase(Locale.ROOT)

      String baseDir = expandImageImportRepoMacros(
        (repoPathRaw ?: '/static-assets/item/images/{yyyy}/{mm}/{dd}/').toString().trim(),
        optionalObjectId?.toString(),
        optionalObjectGroupId?.toString()
      )
      if (!baseDir.startsWith('/static-assets/')) {
        throw new IllegalArgumentException("repoPath must be under /static-assets/: ${baseDir}")
      }
      if (!baseDir.endsWith('/')) {
        baseDir = baseDir + '/'
      }

      byte[] bytes
      String contentType = ''

      if (scheme == 'data') {
        def decoded = parseRasterDataImageUrl(normalizedUrl)
        bytes = decoded.bytes as byte[]
        contentType = (decoded.contentType ?: 'image/png') as String
      } else if (scheme == 'https' || scheme == 'http') {
        String host = parsed.host
        if (!host) {
          throw new IllegalArgumentException('imageUrl must include a host')
        }
        if (scheme == 'http') {
          String h = host.toLowerCase()
          if (!(h == 'localhost' || h == '127.0.0.1' || h == '[::1]')) {
            throw new IllegalArgumentException('Only https URLs are allowed (http is limited to localhost).')
          }
        }
        InetAddress addr = InetAddress.getByName(host)
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress() ||
          addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
          throw new IllegalArgumentException('imageUrl host resolves to a non-public address (blocked).')
        }

        URL url = parsed.toURL()
        HttpURLConnection conn = (HttpURLConnection) url.openConnection()
        conn.setInstanceFollowRedirects(true)
        conn.setConnectTimeout(15_000)
        conn.setReadTimeout(120_000)
        conn.setRequestProperty('Accept', 'image/*,*/*;q=0.8')
        int status = conn.responseCode
        if (status < 200 || status >= 300) {
          throw new IllegalStateException("Failed to download image: HTTP ${status}")
        }
        contentType = (conn.contentType ?: '').split(';')[0]?.trim()?.toLowerCase() ?: ''
        if (contentType && !contentType.startsWith('image/')) {
          throw new IllegalStateException("URL did not return an image (Content-Type: ${contentType})")
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream()
        byte[] buf = new byte[16384]
        long total = 0
        InputStream inStream = conn.inputStream
        try {
          int n
          while ((n = inStream.read(buf)) != -1) {
            total += n
            if (total > MAX_REMOTE_IMAGE_BYTES) {
              throw new IllegalStateException("Image exceeds maximum size (${MAX_REMOTE_IMAGE_BYTES} bytes)")
            }
            bos.write(buf, 0, n)
          }
        } finally {
          try {
            inStream?.close()
          } catch (Throwable ignored) {
          }
        }
        bytes = bos.toByteArray()
        if (bytes.length == 0) {
          throw new IllegalStateException('Downloaded image is empty')
        }
      } else {
        throw new IllegalArgumentException('imageUrl must use http, https, or a raster data:image URL')
      }

      String ext = extensionForImageContentType(contentType)
      String nameFromUrl = scheme == 'data' ? '' : suggestedFileNameFromUrlPath(parsed.path ?: '')
      String baseName = (optionalFileName ?: '').toString().trim()
      if (!baseName) {
        baseName = nameFromUrl ?: "aiassistant-import${ext}"
      }
      baseName = sanitizeImageFileName(baseName, ext)
      if (!baseName.contains('.')) {
        baseName = baseName + ext
      }
      /** Uniquify to avoid overwriting prior imports in the same folder. */
      String uniqueName = insertUniqueSuffixBeforeExtension(baseName)
      String fullPath = baseDir + uniqueName

      writeRepositoryFile(siteId, fullPath, bytes)
      return [
        ok          : true,
        siteId      : siteId,
        relativeUrl : fullPath,
        fileName    : uniqueName,
        byteLength  : bytes.length,
        contentType : contentType ?: 'application/octet-stream'
      ]
    }
  }

  /**
   * Substitutes Crafter object-id macros inside repository-relative paths.
   * Keeps `/site`-relative prefixes stable.
   * Ensures imported binaries land beside their owning items.
   */
  private static String expandImageImportRepoMacros(String path, String objectId, String objectGroupId) {
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
    String y = String.format('%04d', cal.get(Calendar.YEAR))
    String m = String.format('%02d', cal.get(Calendar.MONTH) + 1)
    String d = String.format('%02d', cal.get(Calendar.DAY_OF_MONTH))
    String oid = (objectId ?: '').replaceAll(/[^a-zA-Z0-9_-]/, '_')
    String ogid = (objectGroupId ?: '').replaceAll(/[^a-zA-Z0-9_-]/, '_')
    return path.replace('{yyyy}', y).replace('{mm}', m).replace('{dd}', d)
      .replace('{objectId}', oid).replace('{objectGroupId}', ogid)
  }

  /**
   * Parses URL path segments for the trailing filename.
   * Decodes percent escapes best-effort.
   * Falls back to `image.bin` when headers omit filenames.
   */
  private static String suggestedFileNameFromUrlPath(String path) {
    if (!path) return ''
    int q = path.indexOf('?')
    if (q >= 0) path = path.substring(0, q)
    def parts = path.split('/') as List
    if (parts.isEmpty()) return ''
    String last = parts[parts.size() - 1]
    return last?.trim() ?: ''
  }

  /**
   * Strips dangerous characters from downloaded filenames.
   * Preserves friendly extensions when sane.
   * Appends default extension when upstream names lack suffixes.
   */
  private static String sanitizeImageFileName(String name, String defaultExt) {
    String s = (name ?: 'image').replaceAll(/[^a-zA-Z0-9._-]/, '_')
    if (s.length() > 180) {
      int dot = s.lastIndexOf('.')
      String ext = dot > 0 ? s.substring(dot) : defaultExt
      s = s.substring(0, Math.min(160, s.length())) + ext
    }
    return s ?: 'image' + defaultExt
  }

  /**
   * Splits basename/extension pairs.
   * Inserts `-uuid` stem before extension when collisions arise.
   * Keeps filenames deterministic yet unique under `/static-assets`.
   */
  private static String insertUniqueSuffixBeforeExtension(String fileName) {
    int dot = fileName.lastIndexOf('.')
    String base = dot > 0 ? fileName.substring(0, dot) : fileName
    String ext = dot > 0 ? fileName.substring(dot) : ''
    long ts = System.currentTimeMillis()
    return "${base}-${ts}${ext}"
  }

  /**
   * Maps common MIME families (png/jpeg/webp/gif/svg) to file suffixes.
   * Defaults to `.bin` when unknown.
   * Feeds sanitizeImageFileName when servers omit filenames.
   */
  private static String extensionForImageContentType(String contentType) {
    if (!contentType) return '.png'
    if (contentType.contains('jpeg') || contentType.contains('jpg')) return '.jpg'
    if (contentType.contains('png')) return '.png'
    if (contentType.contains('gif')) return '.gif'
    if (contentType.contains('webp')) return '.webp'
    if (contentType.contains('svg')) return '.svg'
    return '.bin'
  }
  private static String mimeTypeForPath(String fullPath) {
    def p = (fullPath ?: '').toLowerCase()
    if (p.endsWith('.xml')) return 'application/xml'
    if (p.endsWith('.ftl')) return 'text/plain'
    if (p.endsWith('.json')) return 'application/json'
    if (p.endsWith('.css')) return 'text/css'
    if (p.endsWith('.js')) return 'application/javascript'
    if (p.endsWith('.html') || p.endsWith('.htm')) return 'text/html'
    if (p.endsWith('.png')) return 'image/png'
    if (p.endsWith('.jpg') || p.endsWith('.jpeg')) return 'image/jpeg'
    if (p.endsWith('.gif')) return 'image/gif'
    if (p.endsWith('.webp')) return 'image/webp'
    if (p.endsWith('.svg')) return 'image/svg+xml'
    if (p.endsWith('.ico')) return 'image/x-icon'
    if (p.endsWith('.properties')) return 'text/plain'
    if (p.endsWith('.yaml') || p.endsWith('.yml')) return 'application/yaml'
    if (p.endsWith('.md') || p.endsWith('.txt')) return 'text/plain'
    if (p.endsWith('.groovy')) return 'text/plain'
    'application/octet-stream'
  }

  /**
   * Invokes Studio notification hooks after writes.
   * Logs failures at debug without failing tools outright.
   * Keeps sidebar/state fresher after AI-assisted edits.
   */
  private boolean notifyContentEventWithDebug(String siteId, String fullPath, String source) {
    try {
      log.info('notifyContentEvent start: source={} siteId={} path={}', source, siteId, fullPath)
      cstudioContentServiceBean.notifyContentEvent(siteId, fullPath)
      log.info('notifyContentEvent success: source={} siteId={} path={}', source, siteId, fullPath)
      return true
    } catch (Throwable t) {
      log.warn('notifyContentEvent failed: source={} siteId={} path={} reason={}',
        source, siteId, fullPath, (t.message ?: t.toString()), t)
      return false
    }
  }

  /**
   * Publishes {@link SyncFromRepoEvent} so Studio reconciles Git → item DB / sidebar (same event 3-arg
   * {@code ContentServiceImpl#writeContent} emits on {@code support/4.x}). 8-arg {@code writeContent} does not publish it.
   */
  private void publishSyncFromRepoForSite(String siteId) {
    if (!siteId) {
      return
    }
    try {
      ApplicationContext ctx = null
      try {
        def sc = request?.getServletContext()
        if (sc != null) {
          ctx = WebApplicationContextUtils.getWebApplicationContext(sc)
        }
      } catch (Throwable ignored) {
      }
      if (ctx == null && applicationContext instanceof ApplicationContext) {
        ctx = (ApplicationContext) applicationContext
      }
      if (ctx == null) {
        log.debug('publishSyncFromRepoForSite: no ApplicationContext for publishEvent; siteId={}', siteId)
        return
      }
      ctx.publishEvent(new SyncFromRepoEvent(siteId))
      log.info('publishSyncFromRepoForSite: siteId={}', siteId)
    } catch (Throwable t) {
      log.warn('publishSyncFromRepoForSite failed (non-fatal): siteId={} reason={}', siteId, (t.message ?: t.toString()))
    }
  }

  /**
   * First-level child folder names under a Studio sandbox directory (e.g. {@code /scripts/aiassistant/imagegen}).
   * Uses v1 {@code getContentItemTree} when available; returns an empty list on failure.
   */
  List<String> listStudioSandboxChildFolderNames(String siteId, String studioModuleParentDir) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      String dir = (studioModuleParentDir ?: '').toString().trim()
      if (!dir.startsWith('/')) {
        dir = "/${dir}"
      }
      String fullPath = toSandboxConfigStudioRepoPath(dir)
      try {
        if (cstudioContentServiceBean != null &&
          cstudioContentServiceBean.metaClass.respondsTo(cstudioContentServiceBean, 'getContentItemTree', String, String, int)) {
          Object root = cstudioContentServiceBean.getContentItemTree(siteId, fullPath, 2)
          return extractFirstLevelFolderNamesFromContentItemTree(root)
        }
      } catch (Throwable t) {
        log.debug('listStudioSandboxChildFolderNames failed siteId={} path={}: {}', siteId, fullPath, t.message)
      }
      []
    }
  }

  /**
   * Walks Crafter browse-tree nodes recursively.
   * Collects immediate child folder labels under `/site` branches.
   * Feeds authoring hints listing major sections.
   */
  private static List<String> extractFirstLevelFolderNamesFromContentItemTree(Object root) {
    if (root == null) {
      return []
    }
    Object children = null
    try {
      children = root.children
    } catch (Throwable ignored) {
    }
    if (children == null) {
      try {
        if (root.metaClass.respondsTo(root, 'getChildren')) {
          children = root.getChildren()
        }
      } catch (Throwable ignored2) {
      }
    }
    if (!(children instanceof Iterable)) {
      return []
    }
    List<String> out = []
    for (Object c : (Iterable) children) {
      if (c == null) {
        continue
      }
      String uri = ''
      String nm = ''
      try {
        uri = c.uri?.toString() ?: ''
      } catch (Throwable ignored) {
      }
      if (!uri) {
        try {
          uri = c.browserUri?.toString() ?: ''
        } catch (Throwable ignored) {
        }
      }
      try {
        nm = c.name?.toString() ?: ''
      } catch (Throwable ignored) {
      }
      if (!nm) {
        try {
          nm = c.internalName?.toString() ?: ''
        } catch (Throwable ignored) {
        }
      }
      if (!nm && uri) {
        int slash = uri.lastIndexOf('/')
        nm = slash >= 0 ? uri.substring(slash + 1) : uri
      }
      nm = (nm ?: '').trim()
      if (!nm) {
        continue
      }
      boolean looksLikeFile = uri && (uri.endsWith('.groovy') || uri.endsWith('.xml') || uri.endsWith('.json'))
      if (looksLikeFile) {
        continue
      }
      boolean isFolder = true
      try {
        Object f = c.folder
        if (f != null) {
          isFolder = Boolean.TRUE.equals(f) || 'true'.equalsIgnoreCase(f.toString())
        }
      } catch (Throwable ignored) {
      }
      if (isFolder) {
        out.add(nm)
      }
    }
    return out.unique()
  }

  /**
   * Deletes a sandbox item (file or folder) using v1 {@code deleteContent(String site, String path, String approver)}.
   */
  void deleteStudioSandboxItem(String siteId, String fullRepoPath, String approver) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      String path = (fullRepoPath ?: '').toString().trim()
      if (!path.startsWith('/')) {
        path = "/${path}"
      }
      String who = (approver ?: '').toString().trim()
      if (!who) {
        who = 'studio-aiassistant-plugin'
      }
      if (cstudioContentServiceBean == null) {
        throw new IllegalStateException('cstudioContentServiceBean unavailable')
      }
      if (!cstudioContentServiceBean.metaClass.respondsTo(cstudioContentServiceBean, 'deleteContent', String, String, String)) {
        throw new IllegalStateException('deleteContent(site,path,approver) not available on ContentService')
      }
      cstudioContentServiceBean.deleteContent(siteId, path, who)
    }
  }

  /** After writing/deleting Studio sandbox config files, notify Studio to reconcile (same as post-write tool path). */
  void publishConfigChangeRefresh(String siteId) {
    publishSyncFromRepoForSite(siteId)
  }

}
