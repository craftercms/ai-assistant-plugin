package plugins.org.craftercms.aiassistant.tools.general

/**
 * Author-visible and Slack formatting for {@link ConsultCrafterQTool} {@code answer} text.
 */
final class CrafterQConsultFeedbackFormatter {

  static final String CHAT_SECTION_HEADING = 'CrafterQ feedback'
  private static final int MAX_CHAT_CHARS = 12_000
  private static final int MAX_SLACK_CHARS = 3_500

  private CrafterQConsultFeedbackFormatter() {}

  static String chatSectionMarkdown(String answer) {
    String body = truncateForDisplay((answer ?: '').toString().trim(), MAX_CHAT_CHARS)
    if (!body) {
      return ''
    }
    return "## ${CHAT_SECTION_HEADING}\n\n${body}"
  }

  /** Slack mrkdwn thread reply body (emoji title + answer). */
  static String slackThreadBody(String answer) {
    String body = truncateForDisplay(
      SlackConfirmationPostFormatter.markdownToSlackMrkdwn((answer ?: '').toString().trim()),
      MAX_SLACK_CHARS
    )
    if (!body) {
      return ''
    }
    return ":thought_balloon: *${CHAT_SECTION_HEADING}*\n\n${body}"
  }

  private static String truncateForDisplay(String text, int maxChars) {
    if (!text) {
      return ''
    }
    if (text.length() <= maxChars) {
      return text
    }
    return text.substring(0, Math.max(0, maxChars - 80)) + '\n\n… _(truncated for display)_'
  }
}
