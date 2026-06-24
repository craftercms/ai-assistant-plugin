package plugins.org.craftercms.aiassistant.studio.contrib.llm.wire.openaispec

import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiLlmRuntime
import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiRuntimeBuildRequest
import plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration
import plugins.org.craftercms.aiassistant.studio.engine.catalog.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.studio.contrib.llm.StudioAiProviderCredentials

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.DefaultChatClientBuilder
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi

/**
 * Spring AI session builder for built-in <strong>tools-loop</strong> vendors on the <strong>OpenAISpec</strong>
 * chat/tools wire ({@code /v1/chat/completions} + native {@code tools[]}).
 * <p>
 * Covers {@link StudioAiLlmKind#OPENAI_NATIVE} (<strong>OpenAI</strong> vendor), {@link StudioAiLlmKind#XAI_NATIVE},
 * {@link StudioAiLlmKind#DEEPSEEK_NATIVE}, {@link StudioAiLlmKind#LLAMA_NATIVE}, and {@link StudioAiLlmKind#GEMINI_NATIVE}
 * via Spring AI {@link OpenAiChatModel} (client for that chat-completions wire) plus tools-loop {@code RestClient} execution in
 * {@link AiOrchestration}.
 * </p>
 */
class OpenAiSpecSpringAiLlmRuntime implements StudioAiLlmRuntime {

  private static final Logger log = LoggerFactory.getLogger(OpenAiSpecSpringAiLlmRuntime.class)

  static final OpenAiSpecSpringAiLlmRuntime INSTANCE = new OpenAiSpecSpringAiLlmRuntime()

  /**
   * Private constructor; not for direct use.
   */
private OpenAiSpecSpringAiLlmRuntime() {}

  @Override
  String normalizedKind() {
    return StudioAiLlmKind.OPENAI_NATIVE
  }

  @Override
  /**
   * Supports native studio tools.
   * @return True when the check succeeds.
   */
  boolean supportsNativeStudioTools() {
    return true
  }

  @Override
  Map buildSessionBundle(StudioAiRuntimeBuildRequest req) {
    def orch = req.orchestration
    String llmNorm = (req.llmNormalized ?: StudioAiLlmKind.OPENAI_NATIVE).toString()
    String apiKey = StudioAiProviderCredentials.resolveApiKey(llmNorm, req.llmApiKeyFromRequest, req.llmSecretKeyFromAgent)
    if (!apiKey?.trim()) {
      throw new IllegalStateException(StudioAiProviderCredentials.missingApiKeyMessage(llmNorm))
    }
    if (StudioAiProviderCredentials.isLikelyWidgetOnlyServerKeyMissing(llmNorm, apiKey, req.llmApiKeyFromRequest)) {
      log.warn(
        'API key is taken from widget/request (testing path). llm={} apiKeyPreview={} apiKeyChars={}. Prefer server env/JVM keys for production.',
        llmNorm,
        AiOrchestration.llmApiKeyLogPreview(apiKey),
        apiKey.length()
      )
    }
    String modelName = StudioAiProviderCredentials.resolveChatModelId(llmNorm, req.llmModelParam)
    String wireBase = StudioAiProviderCredentials.wireLlmRestBaseUrl(llmNorm)
    def imageModel = AiOrchestration.imageModelFromRequestOrNull(req.imageModelParam)
    String llmOnlyImageKey = AiOrchestration.resolveLlmApiKey(null)
    def tools
    if (req.enableTools) {
      def expertSpecs = orch.readExpertSkillSpecsFromRequest()
      tools = AiOrchestrationTools.build(
        req.toolResultConverter,
        req.studioOps,
        req.toolProgressListener,
        llmOnlyImageKey,
        imageModel,
        req.fullSuppressRepoWrites,
        req.protectedFormItemPath,
        expertSpecs,
        modelName,
        req.llmNormalized,
        req.imageGeneratorParam,
        req.agentEnabledBuiltInTools
      )
    } else {
      tools = []
    }
    def llmApi = OpenAiApi.builder().baseUrl(wireBase).apiKey(apiKey).build()
    def options = OpenAiChatOptions.builder()
      .model(modelName)
      .internalToolExecutionEnabled(req.enableTools)
      .build()
    def chatModel = OpenAiChatModel.builder()
      .openAiApi(llmApi)
      .defaultOptions(options)
      .build()
    def chatClient = new DefaultChatClientBuilder(chatModel).build()
    log.debug(
      'Spring AI chat client: provider={} model={} imageModel={} enableTools={} wireBaseUrl={} apiKeySource={} apiKeyPreview={} apiKeyChars={}',
      llmNorm,
      modelName,
      imageModel ?: '(unset)',
      req.enableTools,
      wireBase,
      StudioAiProviderCredentials.apiKeyResolutionSourceForLog(llmNorm),
      AiOrchestration.llmApiKeyLogPreview(apiKey),
      apiKey.length()
    )
    return [
      chatClient              : chatClient,
      chatModel               : chatModel,
      tools                   : tools,
      llm                     : llmNorm,
      useTools                : req.enableTools,
      studioOps               : req.studioOps,
      toolsLoopChatApiKey     : apiKey,
      toolsLoopChatBaseUrl    : wireBase,
      resolvedChatModel       : modelName
    ]
  }
}
