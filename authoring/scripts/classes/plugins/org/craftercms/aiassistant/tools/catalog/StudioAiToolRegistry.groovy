package plugins.org.craftercms.aiassistant.tools.catalog

import plugins.org.craftercms.aiassistant.tools.cms.GetContentTool
import plugins.org.craftercms.aiassistant.tools.cms.GetContentTypeFormDefinitionTool
import plugins.org.craftercms.aiassistant.tools.cms.GetContentVersionHistoryTool
import plugins.org.craftercms.aiassistant.tools.cms.GetPreviewHtmlTool
import plugins.org.craftercms.aiassistant.tools.cms.ListContentDependencyScopeTool
import plugins.org.craftercms.aiassistant.tools.cms.ListPagesAndComponentsTool
import plugins.org.craftercms.aiassistant.tools.cms.ListStudioContentTypesTool
import plugins.org.craftercms.aiassistant.tools.cms.PublishContentTool
import plugins.org.craftercms.aiassistant.tools.cms.RevertChangeTool
import plugins.org.craftercms.aiassistant.tools.cms.UpdateContentTool
import plugins.org.craftercms.aiassistant.tools.cms.UpdateContentTypeTool
import plugins.org.craftercms.aiassistant.tools.cms.WriteContentTool
import plugins.org.craftercms.aiassistant.tools.development.AnalyzeTemplateTool
import plugins.org.craftercms.aiassistant.tools.development.GetCrafterizingPlaybookTool
import plugins.org.craftercms.aiassistant.tools.development.UpdateTemplateTool
import plugins.org.craftercms.aiassistant.tools.cms.ResearchSiteContentTool
import plugins.org.craftercms.aiassistant.tools.general.FetchHttpUrlTool
import plugins.org.craftercms.aiassistant.tools.general.WebSearchTool
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext

/**
 * Composes core {@link plugins.org.craftercms.aiassistant.tools.spi.StudioAiOrchestrationTool} classes into Spring AI callbacks.
 */
final class StudioAiToolRegistry {

  private StudioAiToolRegistry() {}

  private static List<AbstractStudioAiTool> coreTools() {
    return [
      new GetContentTool(),
      new ListContentDependencyScopeTool(),
      new ListStudioContentTypesTool(),
      new GetContentTypeFormDefinitionTool(),
      new GetContentVersionHistoryTool(),
      new GetPreviewHtmlTool(),
      new FetchHttpUrlTool(),
      new WebSearchTool(),
      new ResearchSiteContentTool(),
      new WriteContentTool(),
      new ListPagesAndComponentsTool(),
      new UpdateTemplateTool(),
      new UpdateContentTool(),
      new UpdateContentTypeTool(),
      new AnalyzeTemplateTool(),
      new PublishContentTool(),
      new GetCrafterizingPlaybookTool(),
      new RevertChangeTool(),
    ]
  }

  static List buildCoreToolCallbacks(StudioAiToolContext ctx) {
    List out = []
    for (AbstractStudioAiTool tool : coreTools()) {
      if (tool.enabled(ctx)) {
        out.add(tool.toFunctionToolCallback(ctx))
      }
    }
    return out
  }
}
