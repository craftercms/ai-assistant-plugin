package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsCompareContentVersions
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSupport

/**
 * Read-only field-level diff between two refs (HEAD vs historical versionNumber).
 */
class CompareContentVersionsTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'CompareContentVersions' }

  @Override
  String description() { ToolPrompts.getDESC_COMPARE_CONTENT_VERSIONS() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.COMPARE_CONTENT_VERSIONS }

  @Override
  boolean recipeEngineReadOnly() { true }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def path = StudioAiToolSupport.repoPathFromToolInput(input)
    if (!path) throw new IllegalArgumentException('Missing required field: path (or contentPath)')

    String baseRef = (input?.baseCommitRef ?: input?.baseRef ?: input?.baseCommitId ?: '').toString().trim()
    String compareRef = (input?.compareCommitRef ?: input?.compareRef ?: input?.compareCommitId ?:
      input?.version ?: input?.versionNumber ?: input?.commitId ?: input?.commitRef ?: '').toString().trim()

    List<String> fieldIds = []
    def rawFields = input?.fieldIds ?: input?.fields
    if (rawFields instanceof Collection) {
      for (def item : rawFields) {
        String t = (item ?: '').toString().trim()
        if (t) {
          fieldIds.add(t)
        }
      }
    } else {
      String one = (rawFields ?: '').toString().trim()
      if (one) {
        fieldIds.add(one)
      }
    }

    int maxChars = 500
    try {
      if (input?.maxFieldValueChars != null) {
        maxChars = Integer.parseInt(input.maxFieldValueChars.toString().trim())
      }
    } catch (Throwable ignored) {
    }

    return CmsCompareContentVersions.compare(
      ctx.ops, siteId, path, baseRef, compareRef, fieldIds, maxChars
    ) as Map
  }
}
