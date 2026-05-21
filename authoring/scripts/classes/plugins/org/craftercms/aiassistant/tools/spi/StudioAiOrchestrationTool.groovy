package plugins.org.craftercms.aiassistant.tools.spi

/**
 * Contract for a built-in tool registered on the orchestration catalog.
 * Implementations expose JSON-schema-shaped inputs plus Studio execution hooks consumed by Spring AI adapters.
 */
interface StudioAiOrchestrationTool {

  /** Stable snake_case identifier matching Chat Completions {@code tools[].function.name}. */
  String wireName()

  /** Markdown/plain summary surfaced to models during tool registration. */
  String description()

  /** JSON Schema describing accepted arguments after normalization. */
  String inputSchemaJson()

  /** Executes tool logic with resolved Studio beans via {@link StudioAiToolContext#getOps()}. */
  Map execute(Map input, StudioAiToolContext ctx)

  /** Lets orchestration omit disabled entries without reloading schemas. */
  boolean enabled(StudioAiToolContext ctx)

  /** Optional recipe-router phase hint ({@code verification}, etc.). */
  String pipelineStage()

  /**
   * When {@code true}, {@link plugins.org.craftercms.aiassistant.recipes.AuthoringIntentRecipeEngine}
   * may invoke this tool during recipe prefetch (no LLM tool-call).
   */
  boolean recipeEngineReadOnly()

  /**
   * When {@code true}, {@link plugins.org.craftercms.aiassistant.recipes.AuthoringIntentRecipeEngine}
   * may invoke this tool on the JVM after Action-phase chat work ({@code phases.confirmation} {@code engineSteps}).
   */
  boolean recipeEngineConfirmationStep()

  /**
   * Merges resolved recipe {@code phases.confirmation} {@code engineSteps} {@code args} with optional
   * Action-phase assistant prose before JVM confirmation execution.
   * <p>Default implementation returns {@code resolvedArgs} unchanged. Tools that opt into
   * {@link #recipeEngineConfirmationStep()} may fill empty args (for example post body text for outbound
   * notifications). The recipe engine calls this via
   * {@link plugins.org.craftercms.aiassistant.tools.catalog.StudioAiToolRegistry#mergeRecipeConfirmationArgs}.</p>
   *
   * @param resolvedArgs template args after binding expansion
   * @param lastAssistantMarkdown final assistant markdown from the tools loop (may be empty)
   * @return args map passed to {@link #execute}
   */
  Map applyRecipeConfirmationArgDefaults(Map resolvedArgs, String lastAssistantMarkdown)
}
