package plugins.org.craftercms.aiassistant.tools.general

import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig

/**
 * Site {@code tools.json} block for {@link ConsultCrafterQTool} ({@code builtInToolSettings.ConsultCrafterQ}).
 */
final class ConsultCrafterQProjectSettings {

  static final String WIRE = 'ConsultCrafterQ'
  static final String DEFAULT_API_BASE = 'https://api.crafterq.ai'

  private ConsultCrafterQProjectSettings() {}

  static String apiBaseUrl(Map cfg) {
    Object raw = StudioAiAssistantProjectConfig.builtInToolSettingsForWire(cfg, WIRE).get('apiBaseUrl')
    String base = raw?.toString()?.trim()
    base ?: DEFAULT_API_BASE
  }

  /** Optional override for {@code X-CrafterQ-Chat-User}; normally minted via {@code GET …/chat_config}. */
  static String chatUser(Map cfg) {
    Map block = StudioAiAssistantProjectConfig.builtInToolSettingsForWire(cfg, WIRE)
    String s = (block.get('chatUser') ?: block.get('chatUserJwt') ?: '').toString().trim()
    s
  }
}
