package plugins.org.craftercms.aiassistant.studio.engine.catalog

import plugins.org.craftercms.aiassistant.studio.spi.imagegen.StudioAiImageGenContext
import plugins.org.craftercms.aiassistant.studio.spi.imagegen.StudioAiImageGenerator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.contrib.llm.StudioAiProviderCredentials
import plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import plugins.org.craftercms.aiassistant.studio.contrib.imagegen.CompatibleImageGenerator
import plugins.org.craftercms.aiassistant.studio.contrib.imagegen.StudioAiScriptImageGenLoader

import java.util.Locale
import java.util.Map

/**
 * Resolves which {@link StudioAiImageGenerator} backs the {@code GenerateImage} tool for a session.
 */
final class StudioAiImageGeneratorFactory {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiImageGeneratorFactory.class)

  /**
   * Private constructor; not for direct use.
   */
private StudioAiImageGeneratorFactory() {}

  /**
   * @param imageGeneratorSpec agent / request {@code imageGenerator}: blank (default), {@code llmWire},
   *        {@code none}|{@code off}|{@code disabled}, or {@code script:id}
   * @param llmImagesApiKey key used for the built-in Images API wire (historically {@link AiOrchestration#resolveLlmApiKey})
   * @param defaultImageModel agent/request image model id when applicable
   */
  static StudioAiImageGenerator resolve(
    StudioToolOperations ops,
    String llmNormalized,
    String imageGeneratorSpec,
    String llmImagesApiKey,
    String defaultImageModel
  ) {
    String spec = (imageGeneratorSpec ?: '').toString().trim()
    String sl = spec.toLowerCase(Locale.US)
    if ('none' == sl || 'off' == sl || 'disabled' == sl) {
      return null
    }
    if (sl.startsWith('script:')) {
      String id = sl.substring('script:'.length()).trim().toLowerCase(Locale.US)
      return new ScriptImageGenerator(ops, id)
    }
    if (spec && !('openaiwire' == sl || 'open_ai_wire' == sl || 'openai' == sl || 'toolsloopwire' == sl || 'tools_loop_wire' == sl)) {
      LOG.warn(
        'StudioAiImageGeneratorFactory: unrecognized imageGenerator="{}" — falling back to built-in images wire when an images API key and imageModel are configured.',
        spec
      )
    }
    String key = (llmImagesApiKey ?: '').toString().trim()
    if (!key) {
      return null
    }
    if (!AiOrchestration.imageModelFromRequestOrNull(defaultImageModel)) {
      return null
    }
    return new CompatibleImageGenerator()
  }

  /**
   * Builds context for tool or orchestration output.
   * @return StudioAiImageGenContext result.
   */
  static StudioAiImageGenContext buildContext(
    StudioToolOperations ops,
    String llmNormalized,
    String imageGeneratorSpec,
    String llmImagesApiKey,
    String defaultImageModel
  ) {
    String siteId = ''
    try {
      siteId = ops != null ? ops.resolveEffectiveSiteId('') : ''
    } catch (Throwable ignored) {
    }
    String key = (llmImagesApiKey ?: '').toString().trim()
    String postUrl = StudioAiProviderCredentials.httpLlmImagesGenerationsUrl()
    return new StudioAiImageGenContext(
      ops,
      siteId,
      (llmNormalized ?: '').toString(),
      defaultImageModel ?: '',
      key,
      postUrl,
      (imageGeneratorSpec ?: '').toString()
    )
  }

  private static final class ScriptImageGenerator implements StudioAiImageGenerator {

    private final StudioToolOperations ops
    private final String id

    ScriptImageGenerator(StudioToolOperations ops, String id) {
      this.ops = ops
      this.id = id
    }

    @Override
    Map generate(Map input, StudioAiImageGenContext ctx) {
      def cl = StudioAiScriptImageGenLoader.loadGenerateClosure(ops, id)
      Map ctxMap = ctx != null ? ctx.asMap() : [:]
      Object r = cl.call((Map) (input ?: [:]), ctxMap)
      if (r instanceof Map) {
        return (Map) r
      }
      return [error: true, message: "Script image generator '${id}' returned non-Map: ${r?.class?.name}"]
    }
  }
}
