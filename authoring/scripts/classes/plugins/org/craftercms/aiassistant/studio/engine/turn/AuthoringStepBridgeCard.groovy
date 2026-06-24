package plugins.org.craftercms.aiassistant.studio.engine.turn

/**
 * Author-visible step-bridge cards: one salient fact line per tool step — not status, not plan text.
 * Tool progress (🛠️ strip) already covers status; {@link #formatSalientContextCard} shows only new context.
 */
final class AuthoringStepBridgeCard {

  static final String KIND_SALIENT_CONTEXT = 'salient_context'

  private AuthoringStepBridgeCard() {}

  /**
   * @param artifact recorded tool artifact with optional {@code salientFact}
   * @return markdown for chat UI, or empty when there is nothing worth showing
   */
  static String formatSalientContextCard(Map artifact) {
    if (!(artifact instanceof Map)) {
      return ''
    }
    String fact = artifact.salientFact?.toString()?.trim()
    if (!fact) {
      return ''
    }
    return fact
  }
}
