package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.general

import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.engine.rag.ExpertSkillVectorRegistry
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas

/**
 * Semantic search over configured expert-skill markdown corpora (in-memory vector store).
 */
class QueryExpertGuidanceTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'QueryExpertGuidance' }

  @Override
  String description() { ToolPrompts.getDESC_QUERY_EXPERT_GUIDANCE() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.QUERY_EXPERT_GUIDANCE }

  /** Permitted during recipe-engine prefetch (read-only). */
  @Override
  boolean recipeEngineReadOnly() { true }

  /** Registered when expert skills and an embedding model were prepared on {@link StudioAiToolContext}. */
  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return ctx.expertEmbeddingModel != null && !ctx.expertUrlBySkillId.isEmpty()
  }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    Map m = (Map) (input ?: [:])
    String sid = m.skillId?.toString()?.trim()
    String q = m.query?.toString()?.trim()
    int tk = 8
    try {
      def tkRaw = m.topK
      if (tkRaw instanceof Number) {
        tk = ((Number) tkRaw).intValue()
      } else if (tkRaw != null) {
        tk = Integer.parseInt(tkRaw.toString().trim())
      }
    } catch (Throwable ignored) {
      tk = 8
    }
    return ExpertSkillVectorRegistry.queryExpertSkill(
      sid,
      q,
      tk,
      ctx.expertUrlBySkillId,
      ctx.expertEmbeddingModel,
      ctx.ops,
      ctx.aiProjectToolCfg
    ) as Map
  }
}
