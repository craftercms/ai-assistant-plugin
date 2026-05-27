package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsGetContent
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsGetContentTypeFormDefinition
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSupport

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * LLM tool that returns a content type's {@code form-definition.xml} so the model can map author field labels
 * to element ids before editing XML.
 */
class GetContentTypeFormDefinitionTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(GetContentTypeFormDefinitionTool)

  /** Returns the Spring AI wire name {@code GetContentTypeFormDefinition}. */
  @Override
  String wireName() { 'GetContentTypeFormDefinition' }

  /** Site-overridable tool description from {@link ToolPrompts}. */
  @Override
  String description() { ToolPrompts.getDESC_GET_CONTENT_TYPE_FORM_DEFINITION() }

  /** JSON Schema for {@code contentPath} and/or {@code contentTypeId}. */
  @Override
  String inputSchemaJson() { StudioAiToolSchemas.GET_CONTENT_TYPE }

  /** Permitted during recipe-engine prefetch (read-only). */
  @Override
  boolean recipeEngineReadOnly() { true }

  /**
   * Resolves {@code contentTypeId} from the item XML at {@code contentPath} when provided, warns on mismatched
   * explicit ids, then loads the form definition via {@link plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations#getContentTypeFormDefinition}.
   */
  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def contentPath = input?.contentPath?.toString()?.trim()
    def contentTypeId = input?.contentTypeId?.toString()?.trim()
    if (contentPath) {
      def item = CmsGetContent.read(ctx.ops, siteId, contentPath)
      def xml = item?.contentXml?.toString()
      def fromXml = StudioAiToolSupport.extractContentTypeIdFromItemXml(xml)
      if (fromXml) {
        if (contentTypeId && !contentTypeId.equals(fromXml)) {
          log.warn(
            'GetContentTypeFormDefinition: ignoring contentTypeId={} (differs from <content-type> in {}); using {}',
            contentTypeId, contentPath, fromXml
          )
        }
        contentTypeId = fromXml
      } else if (!contentTypeId) {
        throw new IllegalArgumentException(
          "No <content-type> element found in XML at '${contentPath}'. Open the item in Studio or use GetContent; pass contentTypeId only if you copy the exact value from that element."
        )
      }
    }
    if (!contentTypeId) {
      throw new IllegalArgumentException(
        'Provide contentPath (page/component XML path — server reads <content-type>) or contentTypeId (exact value from that element, never inferred from filename).'
      )
    }
    return CmsGetContentTypeFormDefinition.load(ctx.ops, siteId, contentTypeId) as Map
  }
}
