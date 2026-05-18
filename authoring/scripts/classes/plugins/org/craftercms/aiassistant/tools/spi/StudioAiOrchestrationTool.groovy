package plugins.org.craftercms.aiassistant.tools.spi

/**
 * Contract for a built-in CMS tool registered on the orchestration catalog.
 */
interface StudioAiOrchestrationTool {

  String wireName()

  String description()

  String inputSchemaJson()

  Map execute(Map input, StudioAiToolContext ctx)

  boolean enabled(StudioAiToolContext ctx)

  /** Optional recipe-router phase hint ({@code verification}, etc.). */
  String pipelineStage()

  /**
   * When {@code true}, {@link plugins.org.craftercms.aiassistant.recipes.AuthoringIntentRecipeEngine}
   * may invoke this tool during recipe prefetch (no LLM tool-call).
   */
  boolean recipeEngineReadOnly()
}
