package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.integrations

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.http.CrafterQChatApiClient
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas

/**
 * Consult a CrafterQ agent ({@code agentId} + {@code prompt}) via {@code api.crafterq.ai}.
 * HTTP/SSE lives in {@link CrafterQChatApiClient} only.
 */
class ConsultCrafterQTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(ConsultCrafterQTool)
  private static final String DRAFT_SEPARATOR = '\n\n---\n\nDRAFT:\n\n'
  private static final String DRAFT_TRUNC_SUFFIX = '\n…[draft excerpt truncated for CrafterQ]'
  private static final String PROMPT_TRUNC_SUFFIX = '\n…[prompt truncated for CrafterQ]'

  @Override
  String wireName() { ConsultCrafterQProjectSettings.WIRE }

  @Override
  /**
   * Recipe engine read only.
   * @return True when the check succeeds.
   */
  boolean recipeEngineReadOnly() { true }

  @Override
  /**
   * Recipe engine confirmation step.
   * @return True when the check succeeds.
   */
  boolean recipeEngineConfirmationStep() { true }

  @Override
  String description() { ToolPrompts.getDESC_CONSULT_CRAFTERQ() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CONSULT_CRAFTERQ }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    Map m = input instanceof Map ? (Map) input : [:]
    String agentId = CrafterQChatApiClient.normalizeAgentId(
      (m.agentId ?: m.agent_id ?: m.agent)?.toString()
    )
    if (!agentId) {
      return [
        ok     : false,
        tool   : wireName(),
        message: 'Missing required field: agentId'
      ]
    }
    String prompt = (m.prompt ?: m.question ?: '').toString().trim()
    if (!prompt) {
      return [
        ok     : false,
        tool   : wireName(),
        message: 'Missing required field: prompt (or question)'
      ]
    }
    String draft = (m.draft ?: m.context ?: '').toString().trim()
    String merged = mergePromptAndDraftForCrafterQ(prompt, draft)

    Map cfg = ctx?.aiProjectToolCfg instanceof Map ? (Map) ctx.aiProjectToolCfg : [:]
    String apiBase = ConsultCrafterQProjectSettings.apiBaseUrl(cfg)
    String chatUserOverride = resolveChatUserOverride(m, ctx, cfg)
    try {
      String answer = (CrafterQChatApiClient.streamChat(apiBase, agentId, merged, chatUserOverride) ?: '').toString()
      String feedbackMarkdown = CrafterQConsultFeedbackFormatter.chatSectionMarkdown(answer)
      String feedbackSlack = CrafterQConsultFeedbackFormatter.slackThreadBody(answer)
      return [
        ok                : true,
        tool              : wireName(),
        agentId           : agentId,
        apiBaseUrl        : apiBase,
        answer            : answer,
        feedbackMarkdown  : feedbackMarkdown,
        feedbackSlack     : feedbackSlack,
        charCount         : answer.length(),
        promptChars       : merged.length()
      ]
    } catch (Throwable t) {
      log.warn('ConsultCrafterQ failed agentId={}: {}', agentId, t.message)
      return [
        ok      : false,
        tool    : wireName(),
        agentId : agentId,
        message : (t.message ?: t.toString())
      ]
    }
  }

  /**
   * CrafterQ stream POST accepts ~1 KiB total {@code prompt}; longer bodies return HTTP 401.
   * Preserve the instruction {@code prompt} and fit as much {@code draft} excerpt as possible.
   */
  private static String mergePromptAndDraftForCrafterQ(String prompt, String draft) {
    String instructions = (prompt ?: '').trim()
    String draftBody = (draft ?: '').trim()
    int max = CrafterQChatApiClient.MAX_STREAM_PROMPT_CHARS
    if (!draftBody) {
      if (instructions.length() <= max) {
        return instructions
      }
      return instructions.substring(0, max) + PROMPT_TRUNC_SUFFIX
    }
    if (instructions.length() >= max) {
      return instructions.substring(0, max) + PROMPT_TRUNC_SUFFIX
    }
    int draftBudget = max - instructions.length() - DRAFT_SEPARATOR.length()
    if (draftBudget <= 0) {
      int cutAt = Math.max(0, max - PROMPT_TRUNC_SUFFIX.length())
      return instructions.substring(0, Math.min(instructions.length(), cutAt)) + PROMPT_TRUNC_SUFFIX
    }
    String excerpt = draftBody
    if (draftBody.length() > draftBudget) {
      int cut = Math.max(0, draftBudget - DRAFT_TRUNC_SUFFIX.length())
      excerpt = draftBody.substring(0, cut) + DRAFT_TRUNC_SUFFIX
    }
    (instructions + DRAFT_SEPARATOR + excerpt).trim()
  }

  /**
   * Optional override only; default path mints JWT via {@code GET …/chat_config} like the embed.
   * Recipe-engine confirmation never reuses servlet/chat POST headers (avoids stale API keys).
   */
  private static String resolveChatUserOverride(Map input, StudioAiToolContext ctx, Map cfg) {
    String fromArg = (input?.chatUser ?: input?.crafterQChatUser ?: input?.chat_user ?: '').toString().trim()
    if (fromArg && CrafterQChatApiClient.looksLikeChatUserJwt(fromArg)) {
      return fromArg
    }
    if (ctx?.recipeEngineRun) {
      String cfgJwt = ConsultCrafterQProjectSettings.chatUser(cfg)
      return CrafterQChatApiClient.looksLikeChatUserJwt(cfgJwt) ? cfgJwt : ''
    }
    String fromReq = StudioToolOperations.readCrafterQChatUserFromServletRequest(ctx?.ops?.request)
    if (fromReq && CrafterQChatApiClient.looksLikeChatUserJwt(fromReq)) {
      return fromReq
    }
    String cfgJwt = ConsultCrafterQProjectSettings.chatUser(cfg)
    return CrafterQChatApiClient.looksLikeChatUserJwt(cfgJwt) ? cfgJwt : ''
  }
}
