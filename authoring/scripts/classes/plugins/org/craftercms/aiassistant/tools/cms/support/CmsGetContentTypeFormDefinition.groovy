package plugins.org.craftercms.aiassistant.tools.cms.support

import plugins.org.craftercms.aiassistant.tools.StudioToolOperations
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsRepositorySupport
/** CMS tool implementation extracted from StudioToolOperations. */
final class CmsGetContentTypeFormDefinition {

  private static final Logger log = LoggerFactory.getLogger(CmsGetContentTypeFormDefinition)

  private CmsGetContentTypeFormDefinition() {}
  static Map load(StudioToolOperations ops, String siteId, String contentTypeId) {
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      def normalized = (contentTypeId ?: '').toString().trim()
      if (!normalized) throw new IllegalArgumentException('Missing required parameter: contentTypeId')
      if (!normalized.startsWith('/')) normalized = "/${normalized}"
      def cfgPath = "/content-types${normalized}/form-definition.xml"
      String xml

      try {
        xml = ops.configurationServiceBean.getConfigurationAsString(siteId, 'studio', cfgPath, '')
      } catch (Throwable t) {
        log.error('getContentTypeFormDefinition failed for site {} path {}', siteId, cfgPath, t)
        throw new IllegalStateException("configurationService.getConfigurationAsString failed for '${cfgPath}': ${t.message}", t)
      }

      if (xml == null || !xml.toString().trim()) {
        throw new IllegalStateException(
          "No form definition returned for site '${siteId}' content type '${normalized}' at '${cfgPath}'."
        )
      }
      Map out = [siteId: siteId, contentTypeId: normalized, path: cfgPath, formDefinitionXml: xml]
      CmsRepositorySupport.attachXmlReadDiagnostics(cfgPath, xml, out)
      out
    }
  }

}
