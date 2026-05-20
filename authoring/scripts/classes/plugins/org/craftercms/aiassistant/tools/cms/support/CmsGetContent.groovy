package plugins.org.craftercms.aiassistant.tools.cms.support

import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Repository read used by {@link plugins.org.craftercms.aiassistant.tools.cms.GetContentTool} and orchestration helpers.
 */
final class CmsGetContent {

  private CmsGetContent() {}

  static Map read(StudioToolOperations ops, String siteId, String path, String commitOrRef = null) {
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      String normalized = CmsRepositorySupport.normalizeLeadingSlash(path, 'path')
      String ref = (commitOrRef ?: '').toString().trim()
      if (!ref) {
        ref = CmsRepositorySupport.CONTENT_REF_HEAD
      }
      String xml
      try {
        def optional = ops.contentServiceBean.getContentByCommitId(siteId, normalized, ref)
        if (optional == null || !optional.isPresent()) {
          throw new IllegalStateException(
            "No content at site '${siteId}' path '${normalized}' for ref '${ref}' (getContentByCommitId empty)."
          )
        }
        def resource = optional.get()
        xml = CmsRepositorySupport.slurpInputStreamUtf8(resource.getInputStream())
      } catch (IllegalStateException e) {
        throw e
      } catch (Throwable t) {
        throw new IllegalStateException(
          "contentService.getContentByCommitId failed for path '${normalized}' ref '${ref}': ${t.message}", t
        )
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

  static String resolveTemplatePath(StudioToolOperations ops, String siteId, String contentPath) {
    if (!siteId || !contentPath) {
      return null
    }
    Map content = read(ops, siteId, contentPath)
    String xml = content?.contentXml?.toString()
    String tpl = CmsRepositorySupport.extractFirstTagValue(xml, 'display-template')
    return (tpl && tpl.startsWith('/')) ? tpl : null
  }
}
