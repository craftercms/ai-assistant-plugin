package plugins.org.craftercms.aiassistant.catalog

/** Stable agent id fields in {@code config/studio/ai-assistant/agents.json}. */
final class AgentCatalogIds {

  private AgentCatalogIds() {}

  static String readId(Map entry) {
    if (!(entry instanceof Map)) {
      return ''
    }
    return (entry.agentId ?: entry.id ?: '').toString().trim()
  }
}
