package plugins.org.craftercms.aiassistant.http

import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.config.StudioAiSiteModuleText
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration

/**
 * Merges missing stream/chat POST fields from site {@code config/studio/ai-assistant/agents.json}
 * (Project Tools → Agents). Reads the catalog server-side, picks the matching chat agent row, then copies
 * catalog defaults into the POST body only when fields were omitted.
 * When {@code imageModel} is still absent and transport is OpenAI native,
 * applies the same default as the central agents catalog UI ({@code gpt-image-1}).
 */
final class AiAssistantCentralAgentsMerge {

  private static final Logger log = LoggerFactory.getLogger(AiAssistantCentralAgentsMerge)

  /** Studio {@code configurationService} path (under {@code config/studio/}). */
  private static final String CENTRAL_AGENTS_STUDIO_PATH = '/ai-assistant/agents.json'

  /** Default OpenAI Images API model when authors use {@code openAI} and omit {@code imageModel}. */
  static final String DEFAULT_OPENAI_IMAGE_MODEL = 'gpt-image-1'

  /**
   * Prevents instantiation — merge helpers are {@code static}.
   */
  private AiAssistantCentralAgentsMerge() {}

  /**
   * Detects {@code mode: autonomous} rows that should be ignored when merging chat POST defaults.
   * Trims + lowercases mode tokens for stability.
   */
  private static boolean isAutonomousEntry(Map entry) {
    String mode = (entry?.mode ?: '').toString().trim().toLowerCase()
    return 'autonomous' == mode
  }

  /**
   * Locates the catalog row matching {@code wantedAgentId} when provided (legacy + modern id fields).
   * Skips autonomous rows when searching by id.
   * Falls back to the first non-autonomous chat row so anonymous POSTs still inherit defaults.
   */
  private static Map findChatAgentRow(List agents, String wantedAgentId) {
    if (!agents || agents.isEmpty()) {
      return null
    }
    String wanted = (wantedAgentId ?: '').toString().trim()
    if (wanted) {
      for (Object raw : agents) {
        if (!(raw instanceof Map)) {
          continue
        }
        Map entry = (Map) raw
        if (isAutonomousEntry(entry)) {
          continue
        }
        // Match crafterQAgentId or id on the catalog row.
        String id = (entry.crafterQAgentId ?: entry.id ?: '').toString().trim()
        if (wanted == id) {
          return entry
        }
      }
    }
    for (Object raw : agents) {
      if (!(raw instanceof Map)) {
        continue
      }
      Map entry = (Map) raw
      if (!isAutonomousEntry(entry)) {
        return entry
      }
    }
    return null
  }

  /**
   * Loads `/config/studio/ai-assistant/agents.json` via {@link StudioAiSiteModuleText} when present.
   * Parses JSON into a Map using JsonSlurper leniently.
   * Logs debug failures without bubbling errors into chat handlers.
   */
  private static Map readCentralAgentsFile(Object applicationContext, String siteId) {
    String json = StudioAiSiteModuleText.readUtf8IfPresent(applicationContext, siteId, CENTRAL_AGENTS_STUDIO_PATH)
    if (json == null || !json.toString().trim()) {
      return null
    }
    try {
      Object parsed = new JsonSlurper().parseText(json.toString())
      return parsed instanceof Map ? (Map) parsed : null
    } catch (Throwable t) {
      log.debug('readCentralAgentsFile: parse failed siteId={}: {}', siteId, t.message)
      return null
    }
  }

  /**
   * Reads string-ish fields from catalog rows defensively.
   * Returns trimmed text or empty string for missing keys.
   */
  private static String textField(Map entry, String key) {
    if (!(entry instanceof Map) || !key) {
      return ''
    }
    Object v = entry.get(key)
    return v != null ? v.toString().trim() : ''
  }

  /**
   * Fills missing {@code imageModel}, {@code llmModel}, {@code llm}, and/or {@code imageGenerator} on {@code body}
   * from the site's central agents catalog. Matches {@code crafterQAgentId} or {@code id} when
   * {@code agentId} is set; otherwise uses the first {@code mode: chat} row (or omitted mode).
   */
  static void mergeStreamAgentFieldsFromSiteAgentsFileIfMissing(
    Object applicationContext,
    Map body,
    String siteId,
    String agentId
  ) {
    if (!(body instanceof Map) || body == null) {
      return
    }
    String imgBody = (body.imageModel ?: body.get('image-model') ?: body.image_model)?.toString()?.trim() ?: ''
    String llmModelBody = (body.llmModel ?: body.get('llm-model') ?: body.llm_model)?.toString()?.trim() ?: ''
    String imgGenBody =
      (body.imageGenerator ?: body.get('image-generator') ?: body.image_generator)?.toString()?.trim() ?: ''
    String llmTransportBody = (body.llm ?: body.get('llm'))?.toString()?.trim() ?: ''
    if (imgBody && llmModelBody && imgGenBody && llmTransportBody) {
      return
    }
    String site = (siteId ?: '').toString().trim()
    if (!site) {
      return
    }
    Map file = readCentralAgentsFile(applicationContext, site)
    List agents = file?.agents instanceof List ? (List) file.agents : null
    Map row = findChatAgentRow(agents, (agentId ?: '').toString().trim())
    if (row == null) {
      log.debug(
        'Central agents merge: no chat row for agentId={} siteId={} (missing or empty agents.json)',
        agentId,
        site
      )
      return
    }
    String catalogImg = textField(row, 'imageModel')
    String catalogLlmModel = textField(row, 'llmModel')
    String catalogImgGen = textField(row, 'imageGenerator')
    String catalogLlm = textField(row, 'llm')
    if (!imgBody && catalogImg) {
      String imgNorm = AiOrchestration.normalizeImagesApiModelId(catalogImg)
      body.put('imageModel', imgNorm)
      log.info(
        'Central agents merge: copied imageModel="{}" into POST body siteId={} agent={}',
        imgNorm,
        site,
        agentId ?: '(first chat agent)'
      )
    }
    if (!llmModelBody && catalogLlmModel) {
      body.put('llmModel', catalogLlmModel)
      log.info(
        'Central agents merge: copied llmModel="{}" into POST body siteId={} agent={}',
        catalogLlmModel,
        site,
        agentId ?: '(first chat agent)'
      )
    }
    if (!imgGenBody && catalogImgGen) {
      body.put('imageGenerator', catalogImgGen)
      log.info(
        'Central agents merge: copied imageGenerator="{}" into POST body siteId={} agent={}',
        catalogImgGen,
        site,
        agentId ?: '(first chat agent)'
      )
    }
    if (!llmTransportBody && catalogLlm) {
      body.put('llm', catalogLlm)
      log.info(
        'Central agents merge: copied llm="{}" into POST body siteId={} agent={}',
        catalogLlm,
        site,
        agentId ?: '(first chat agent)'
      )
    }
  }

  /**
   * When transport is OpenAI native and {@code imageModel} is still unset, use {@link #DEFAULT_OPENAI_IMAGE_MODEL}
   * unless {@code imageGenerator} explicitly disables bitmap generation.
   */
  static void applyOpenAiDefaultImageModelIfMissing(Map body, String llmNormalized) {
    if (!(body instanceof Map) || body == null) {
      return
    }
    String imgBody = (body.imageModel ?: body.get('image-model') ?: body.image_model)?.toString()?.trim() ?: ''
    if (imgBody) {
      return
    }
    String genSl = (body.imageGenerator ?: body.get('image-generator') ?: body.image_generator)?.toString()?.trim()?.toLowerCase() ?: ''
    if ('none' == genSl || 'off' == genSl || 'disabled' == genSl) {
      return
    }
    String llmRaw = (llmNormalized ?: body.llm ?: '').toString().trim()
    if (!llmRaw) {
      return
    }
    String kind
    try {
      kind = StudioAiLlmKind.normalize(llmRaw)
    } catch (Throwable ignored) {
      return
    }
    if (StudioAiLlmKind.OPENAI_NATIVE != kind) {
      return
    }
    String imgNorm = AiOrchestration.normalizeImagesApiModelId(DEFAULT_OPENAI_IMAGE_MODEL)
    body.put('imageModel', imgNorm)
    log.info(
      'Central agents merge: applied default OpenAI imageModel="{}" (POST omitted imageModel) siteId={}',
      imgNorm,
      (body.siteId ?: '').toString().trim() ?: '(request)'
    )
  }
}
