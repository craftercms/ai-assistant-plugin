package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

/**
 * Repository read used by {@link plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.GetContentTool} and orchestration helpers.
 */
final class CmsGetContent {

  private static final Logger log = LoggerFactory.getLogger(CmsGetContent)

  /**
   * Private constructor; not for direct use.
   */
private CmsGetContent() {}

  /**
   * Loads read from configuration or input.
   * @param ops Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @param path Studio or repository context for this call.
   * @param commitOrRef Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  static Map read(StudioToolOperations ops, String siteId, String path, String commitOrRef = null) {
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      String normalized = CmsRepositorySupport.normalizeLeadingSlash(path, 'path')
      String ref = (commitOrRef ?: '').toString().trim()
      if (!ref) {
        ref = CmsRepositorySupport.CONTENT_REF_HEAD
      }
      String xml
      if (isCurrentSandboxRef(ref)) {
        xml = readCurrentSandboxUtf8(ops, siteId, normalized)
        ref = CmsRepositorySupport.CONTENT_REF_HEAD
      } else {
        xml = readByCommitIdUtf8(ops, siteId, normalized, ref)
      }
      if (xml == null || !xml.toString().trim()) {
        throw new IllegalStateException(
          "No content returned for site '${siteId}' path '${normalized}' ref '${ref}'."
        )
      }
      Map out = [siteId: siteId, path: normalized, commitRef: ref, contentXml: xml]
      CmsRepositorySupport.attachXmlReadDiagnostics(normalized, xml, out)
      CmsRepositorySupport.attachSiteItemContentTypeFromXml(normalized, xml, out)
      return out
    }
  }

  /**
   * Resolves template path from request and plugin context.
   * @param ops Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @param contentPath Studio or repository context for this call.
   * @return Text result, or empty or null when unavailable.
   */
  static String resolveTemplatePath(StudioToolOperations ops, String siteId, String contentPath) {
    if (!siteId || !contentPath) {
      return null
    }
    Map content = read(ops, siteId, contentPath)
    String xml = content?.contentXml?.toString()
    String tpl = CmsRepositorySupport.extractFirstTagValue(xml, 'display-template')
    return (tpl && tpl.startsWith('/')) ? tpl : null
  }

  /** {@code HEAD}, blank, or omitted — read working-copy sandbox tip, not a historical commit. */
  private static boolean isCurrentSandboxRef(String ref) {
    String r = (ref ?: '').toString().trim()
    if (!r) {
      return true
    }
    return CmsRepositorySupport.CONTENT_REF_HEAD.equalsIgnoreCase(r)
  }

  /**
   * Current sandbox file at {@code path}: prefer v2 {@code getContentAsResource}, then v1 {@code getContent},
   * then {@code getContentByCommitId(HEAD)} as last resort.
   */
  private static String readCurrentSandboxUtf8(StudioToolOperations ops, String siteId, String normalized) {
    String fromResource = readViaContentAsResource(ops, siteId, normalized)
    if (fromResource?.trim()) {
      return fromResource
    }
    String fromV1 = readViaCstudioGetContent(ops, siteId, normalized)
    if (fromV1?.trim()) {
      return fromV1
    }
    return readByCommitIdUtf8(ops, siteId, normalized, CmsRepositorySupport.CONTENT_REF_HEAD)
  }

  /**
   * Loads via content as resource from configuration or input.
   * @param ops Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @param normalized Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String readViaContentAsResource(StudioToolOperations ops, String siteId, String normalized) {
    Object cs = ops?.contentServiceBean
    if (cs == null) {
      return null
    }
    try {
      if (!cs.metaClass.respondsTo(cs, 'getContentAsResource', String, String)) {
        return null
      }
      def resource = cs.getContentAsResource(siteId, normalized)
      if (resource == null) {
        return null
      }
      def stream = resource.getInputStream()
      try {
        return CmsRepositorySupport.slurpInputStreamUtf8(stream)
      } finally {
        try {
          stream?.close()
        } catch (Throwable ignored) {
        }
      }
    } catch (Throwable t) {
      log.debug(
        'getContentAsResource failed siteId={} path={}: {}',
        siteId,
        normalized,
        t.message ?: t.toString()
      )
      return null
    }
  }

  /**
   * Loads via cstudio get content from configuration or input.
   * @param ops Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @param normalized Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String readViaCstudioGetContent(StudioToolOperations ops, String siteId, String normalized) {
    Object v1 = ops?.cstudioContentServiceBean
    if (v1 == null) {
      return null
    }
    try {
      if (!v1.metaClass.respondsTo(v1, 'getContent', String, String)) {
        return null
      }
      def body = v1.getContent(siteId, normalized)
      return body != null ? body.toString() : null
    } catch (Throwable t) {
      log.debug(
        'cstudioContentService.getContent failed siteId={} path={}: {}',
        siteId,
        normalized,
        t.message ?: t.toString()
      )
      return null
    }
  }

  /**
   * Loads by commit id utf8 from configuration or input.
   * @param ops Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @param normalized Caller-supplied input.
   * @param ref Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String readByCommitIdUtf8(StudioToolOperations ops, String siteId, String normalized, String ref) {
    try {
      def optional = ops.contentServiceBean.getContentByCommitId(siteId, normalized, ref)
      if (optional == null || !optional.isPresent()) {
        throw new IllegalStateException(
          "No content at site '${siteId}' path '${normalized}' for ref '${ref}' (getContentByCommitId empty)."
        )
      }
      def resource = optional.get()
      def stream = resource.getInputStream()
      try {
        return CmsRepositorySupport.slurpInputStreamUtf8(stream)
      } finally {
        try {
          stream?.close()
        } catch (Throwable ignored) {
        }
      }
    } catch (IllegalStateException e) {
      throw e
    } catch (Throwable t) {
      throw new IllegalStateException(
        "contentService.getContentByCommitId failed for path '${normalized}' ref '${ref}': ${t.message}", t
      )
    }
  }
}
