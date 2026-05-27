package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal

import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.engine.context.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.engine.catalog.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsRepositorySupport
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
/** CMS tool implementation extracted from StudioToolOperations. */
final class CmsPublishContent {

  private static final Logger log = LoggerFactory.getLogger(CmsPublishContent)

  /**
   * Private constructor; not for direct use.
   */
private CmsPublishContent() {}
  /**
   * Site ever published.
   * @param ops Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @return Boolean result.
   */
  static Boolean siteEverPublished(StudioToolOperations ops, String siteId) {
    if (ops.publishServiceBean == null) {
      return null
    }
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      try {
        return Boolean.valueOf(ops.publishServiceBean.isSitePublished(siteId))
      } catch (Throwable t) {
        log.warn('isSiteEverPublished failed for site {}: {}', siteId, t.message)
        return null
      }
    }
  }

  /**
   * Publishes all pending changes for the site (v2 {@code PublishService#publishAll}) — use for first publish / publish everything.
   */
  // --- Publish operations (deployment packages, bulk go-live, publish-all) ---

  /**
   * Requires ops.publishServiceBean then resolves site/target/comments.
   * Calls PublishService.publishAll under Studio security.
   * Summarizes updated/deleted/failed counts for LLM-visible telemetry.
   */
  static Map publishAll(StudioToolOperations ops, String siteId, String publishingTarget, String submissionComment = null) {
    if (ops.publishServiceBean == null) {
      throw new IllegalStateException('Studio publishService bean not found; publishScope=all requires PublishService.')
    }
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      def target = (publishingTarget ?: 'live').toString().trim()
      if (!target) target = 'live'
      def comment = (submissionComment ?: 'publish_content tool (publish all)').toString().trim()
      def changes = ops.publishServiceBean.publishAll(siteId, target, comment)
      int updatedCount = 0
      int deletedCount = 0
      int failedCount = 0
      try {
        updatedCount = changes?.getUpdatedPaths()?.size() ?: 0
        deletedCount = changes?.getDeletedPaths()?.size() ?: 0
        failedCount = changes?.getFailedPaths()?.size() ?: 0
      } catch (Throwable ignored) {}
      return [
        initialPublish : changes?.isInitialPublish(),
        updatedCount   : updatedCount,
        deletedCount   : deletedCount,
        failedCount    : failedCount,
        empty          : changes?.isEmpty()
      ]
    }
  }

  /**
   * Bulk go-live from a repository subtree ({@code DeploymentService#bulkGoLive}) — Studio “publish all under path”.
   */
  static void submitBulk(StudioToolOperations ops, String siteId, String basePath, String publishingTarget, String submissionComment = null) {
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      def normalized = CmsRepositorySupport.normalizeLeadingSlash((basePath ?: '/site'), 'path')
      def target = (publishingTarget ?: 'live').toString().trim()
      if (!target) target = 'live'
      def comment = (submissionComment ?: 'publish_content tool (bulk)').toString().trim()
      ops.deploymentServiceBean.bulkGoLive(siteId, target, normalized, comment)
    }
  }

  /**
   * Normalizes arguments then forwards to submitPublishPackageList.
   * Passes a singleton path list so DeploymentService.deploy receives consistent metadata.
   * Returns null today—compat wrapper for single-path publishes.
   */
  static Long submitPackage(StudioToolOperations ops, String siteId, String path, String publishingTarget, String optionalScheduleIso = null) {
    submitPackageList(ops, siteId, [path], publishingTarget, optionalScheduleIso)
  }

  /**
   * Publishes multiple repository paths in one deployment package ({@code DeploymentService#deploy} with a path list).
   */
  static Long submitPackageList(StudioToolOperations ops, String siteId, List paths, String publishingTarget, String optionalScheduleIso = null) {
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      if (paths == null || paths.isEmpty()) {
        throw new IllegalArgumentException('paths must be a non-empty list of repository paths')
      }
      def normalized = []
      for (def p : paths) {
        def s = (p ?: '').toString().trim()
        if (s) {
          normalized.add(CmsRepositorySupport.normalizeLeadingSlash(s, 'path'))
        }
      }
      if (normalized.isEmpty()) {
        throw new IllegalArgumentException('paths must contain at least one non-empty repository path')
      }
      def target = (publishingTarget ?: 'live').toString().trim()
      if (!target) target = 'live'
      Instant schedule = null
      def raw = (optionalScheduleIso ?: '').toString().trim()
      if (raw) {
        try {
          schedule = Instant.parse(raw)
        } catch (Throwable ignored) {
          try {
            schedule = ZonedDateTime.parse(raw).toInstant()
          } catch (Throwable ignored2) {
            log.warn('submitPublishPackageList: could not parse schedule "{}", publishing immediately', raw)
          }
        }
      }
      ZonedDateTime scheduledDate = schedule != null ? ZonedDateTime.ofInstant(schedule, ZoneId.systemDefault()) : null
      boolean scheduleDateNow = (scheduledDate == null)
      def comment = normalized.size() == 1 ?
        'publish_content tool' :
        "publish_content tool (${normalized.size()} paths)"
      ops.deploymentServiceBean.deploy(
        siteId,
        target,
        normalized,
        scheduledDate,
        StudioToolOperations.currentAuthenticatedUsername(),
        comment,
        scheduleDateNow
      )
      null
    }
  }

  /**
   * Resolves {@code publish_content} tool input into a single deploy / bulk / publish-all operation.
   */
  static Map fromToolInput(StudioToolOperations ops, Map input, boolean pathProtectFormItem = false, String normProtectedFormPath = '') {
    def siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
    if (!siteId) {
      throw new IllegalArgumentException('Missing required field: siteId')
    }
    def target = input?.publishingTarget?.toString()?.trim() ?: 'live'
    def date = input?.date?.toString()?.trim()
    def comment = input?.submissionComment?.toString()?.trim() ?: input?.comment?.toString()?.trim()
    def scope = normalizePublishScope(input)
    def paths = CmsPublishContent.collectPaths(input)
    Boolean everPublished = siteEverPublished(ops, siteId)

    if (!scope) {
      if (plugins.org.craftercms.aiassistant.engine.context.AuthoringPreviewContext.isTruthy(input?.publishEntireSite) ||
        plugins.org.craftercms.aiassistant.engine.context.AuthoringPreviewContext.isTruthy(input?.publishAll) ||
        plugins.org.craftercms.aiassistant.engine.context.AuthoringPreviewContext.isTruthy(input?.publishEverything)) {
        scope = 'all'
      } else if (paths.size() > 1) {
        scope = 'paths'
      } else if (paths.size() == 1) {
        scope = 'item'
      }
    }

    if (scope == 'all') {
      def summary = publishAll(ops, siteId, target, comment)
      return [
        action               : 'publish_content',
        siteId               : siteId,
        publishScope         : 'all',
        publishingTarget     : target,
        siteEverPublishedBefore: everPublished,
        ok                   : true,
        message              : buildPublishAllMessage(summary, everPublished),
        result               : summary
      ]
    }

    if (scope == 'bulk') {
      def bulkRoot = (input?.bulkRootPath ?: input?.bulkPath ?: '/site').toString().trim()
      if (!bulkRoot) bulkRoot = '/site'
      String err = null
      try {
        submitBulk(ops, siteId, bulkRoot, target, comment)
      } catch (Throwable t) {
        err = (t.message ?: t.toString())
        log.warn('publish_content bulk failed: {}', err)
      }
      return [
        action               : 'publish_content',
        siteId               : siteId,
        publishScope         : 'bulk',
        bulkRootPath         : CmsRepositorySupport.normalizeLeadingSlash(bulkRoot, 'bulkRootPath'),
        publishingTarget     : target,
        siteEverPublishedBefore: everPublished,
        ok                   : err == null,
        message              : err ?: "Bulk publish submitted from ${bulkRoot}.",
        result               : err == null ? 'ok' : null
      ]
    }

    if (scope == 'paths') {
      if (paths.isEmpty()) {
        throw new IllegalArgumentException('publishScope=paths requires paths or contentPaths (non-empty array)')
      }
      def blocked = []
      def publishable = []
      for (def p : paths) {
        if (pathProtectFormItem && plugins.org.craftercms.aiassistant.engine.context.AuthoringPreviewContext.sameRepoPath(p, normProtectedFormPath)) {
          blocked.add(p)
        } else {
          publishable.add(p)
        }
      }
      if (publishable.isEmpty()) {
        return [
          action                   : 'publish_content',
          ok                       : false,
          blockedForFormClientApply: true,
          blockedPaths             : blocked,
          message                  : 'publish_content blocked for all requested paths (form client-side apply).',
        ]
      }
      String err = null
      try {
        submitPackageList(ops, siteId, publishable, target, date)
      } catch (Throwable t) {
        err = (t.message ?: t.toString())
        log.warn('publish_content paths failed: {}', err)
      }
      def out = [
        action               : 'publish_content',
        siteId               : siteId,
        publishScope         : 'paths',
        paths                : publishable,
        pathCount            : publishable.size(),
        publishingTarget     : target,
        date                 : date,
        siteEverPublishedBefore: everPublished,
        ok                   : err == null,
        message              : err ?: "Publish submitted for ${publishable.size()} path(s).",
        result               : err == null ? 'ok' : null
      ]
      if (!blocked.isEmpty()) {
        out.blockedPaths = blocked
        out.partialBlockedForFormClientApply = true
      }
      if (everPublished == Boolean.FALSE && publishable.size() == 1) {
        out.warning = 'Site has never been published. For first go-live or entire site, use publishScope=all (not a single path).'
      }
      return out
    }

    def path = paths ? paths[0] : plugins.org.craftercms.aiassistant.engine.catalog.AiOrchestrationTools.repoPathFromToolInput((Map) (input ?: [:]))
    if (!path) {
      throw new IllegalArgumentException('Missing required field: path (or contentPath), or set publishScope=all|bulk|paths')
    }
    if (pathProtectFormItem && plugins.org.craftercms.aiassistant.engine.context.AuthoringPreviewContext.sameRepoPath(path, normProtectedFormPath)) {
      return [
        ok                       : false,
        blockedForFormClientApply: true,
        path                     : plugins.org.craftercms.aiassistant.engine.context.AuthoringPreviewContext.normalizeRepoPath(path),
        message                  :
          'publish_content blocked for the form item path (client-side apply). Save/publish from Studio after applying form updates.',
        action                   : 'publish_content'
      ]
    }
    String err = null
    try {
      submitPackage(ops, siteId, path, target, date)
    } catch (Throwable t) {
      err = (t.message ?: t.toString())
      log.warn('publish_content failed: {}', err)
    }
    def result = [
      action               : 'publish_content',
      siteId               : siteId,
      path                 : path,
      publishScope         : 'item',
      date                 : date,
      publishingTarget     : target,
      siteEverPublishedBefore: everPublished,
      ok                   : err == null,
      message              : err ?: 'Publish submitted.',
      result               : err == null ? 'ok' : null
    ]
    if (everPublished == Boolean.FALSE) {
      result.warning =
        'Site has never been published. Studio first publish typically requires publishScope=all (entire site), not a single item path.'
    }
    return result
  }

  /**
   * Builds publish all message for tool or orchestration output.
   * @param summary Caller-supplied input.
   * @param everPublished Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String buildPublishAllMessage(Map summary, Boolean everPublished) {
    def initial = summary?.initialPublish == true
    def updated = summary?.updatedCount ?: 0
    def deleted = summary?.deletedCount ?: 0
    def failed = summary?.failedCount ?: 0
    def base = initial ?
      'Initial site publish (publish all) submitted.' :
      'Publish all pending changes submitted.'
    if (everPublished == Boolean.FALSE) {
      base = 'First-time site publish (publish all) submitted.'
    }
    if (updated > 0 || deleted > 0 || failed > 0) {
      base += " (${updated} updated, ${deleted} deleted" + (failed > 0 ? ", ${failed} failed" : '') + ').'
    }
    return base
  }

  /**
   * Reads canonical keys (`publishScope`,`publish_scope`).
   * Maps synonyms (`all`,`bulk`,`site`) onto internal enumerations.
   * Defaults safely when authors omit explicit scope tokens.
   */
  private static String normalizePublishScope(Map input) {
    def raw = (input?.publishScope ?: input?.publishMode ?: '').toString().trim().toLowerCase(Locale.ROOT)
    if (!raw) {
      return ''
    }
    if (raw in ['item', 'single', 'one']) {
      return 'item'
    }
    if (raw in ['paths', 'list', 'batch', 'multiple']) {
      return 'paths'
    }
    if (raw in ['bulk', 'subtree', 'bulkgolive', 'bulk_go_live']) {
      return 'bulk'
    }
    if (raw in ['all', 'everything', 'entire', 'site', 'publishall', 'publish_all', 'first']) {
      return 'all'
    }
    return raw
  }

  /**
   * Coerces mixed tool payloads (`paths`,`items`,`files`) into normalized repo paths.
   * Dedupes while preserving order.
   * Ensures DeploymentService.deploy receives clean `/site/...` strings.
   */
  static List<String> collectPaths(Map input) {
    LinkedHashSet<String> out = new LinkedHashSet<>()
    if (!(input instanceof Map)) {
      return []
    }
    def single = plugins.org.craftercms.aiassistant.engine.catalog.AiOrchestrationTools.repoPathFromToolInput(input)
    if (single?.trim()) {
      out.add(single.trim())
    }
    for (String key : ['paths', 'contentPaths', 'pathList'] as List) {
      def arr = input[key]
      if (!(arr instanceof Collection)) {
        continue
      }
      for (def p : arr) {
        def s = (p ?: '').toString().trim()
        if (s) {
          out.add(s)
        }
      }
    }
    return out.collect {
      plugins.org.craftercms.aiassistant.engine.context.AuthoringPreviewContext.normalizeRepoPath(it)
    }.findAll { it }
  }

}
