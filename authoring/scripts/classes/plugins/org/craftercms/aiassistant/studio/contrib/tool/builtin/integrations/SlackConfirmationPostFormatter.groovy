package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.integrations

/**
 * Generic markdown → Slack mrkdwn helper for recipe confirmation when {@code text} is omitted.
 * <p>Does not interpret workflow-specific sections or build Block Kit layouts — recipes define message
 * structure in confirmation {@code engineSteps} ({@code text} / JSON {@code outputKeys}); this class
 * only removes orchestration plan noise and applies common mrkdwn transforms.</p>
 */
final class SlackConfirmationPostFormatter {

  private static final int MAX_SLACK_CHARS = 39_000

  /**
   * Private constructor; not for direct use.
   */
private SlackConfirmationPostFormatter() {}

  /**
   * @param raw assistant markdown (orchestration plan should already be stripped by caller when possible)
   * @return Slack mrkdwn body
   */
  static String formatAssistantProseForSlack(String raw) {
    String s = (raw ?: '').trim()
    if (!s) {
      return ''
    }
    s = ensureEmojiLabelLineBreaks(s)
    s = stripPlanBlock(s).trim()
    if (!s) {
      return ''
    }
    String outbound = extractOptionalOutboundSection(s)
    if (outbound) {
      s = outbound
    }
    String result = markdownToSlackMrkdwn(s)
    if (result.length() > MAX_SLACK_CHARS) {
      result = result.substring(0, MAX_SLACK_CHARS - 80) + '\n\n… _(truncated for Slack limit)_'
    }
    return result
  }

  /**
   * When several Slack-style emoji shortcodes introduce labeled fields on one line
   * ({@code :writing_hand: Author voice: … :hook: Hook: …}), break before each subsequent shortcode
   * so chat markdown and Slack mrkdwn render one field per line.
   */
  static String ensureEmojiLabelLineBreaks(String text) {
    String s = (text ?: '').trim()
    if (!s) {
      return ''
    }
    java.util.regex.Pattern label = java.util.regex.Pattern.compile('(?i)(?:^|\\s)(:[a-z0-9_+-]+:)\\s+')
    java.util.regex.Matcher m = label.matcher(s)
    int count = 0
    while (m.find()) {
      count++
      if (count >= 2) {
        break
      }
    }
    if (count < 2) {
      return s
    }
    return s.replaceAll(/(?i)\s+(?=:[a-z0-9_+-]+:\s)/, '\n\n').trim()
  }

  /**
   * Generic outbound marker (not recipe-specific): content under {@code ## Slack message} is the post body.
   */
  private static String extractOptionalOutboundSection(String s) {
    def m = (s =~ /(?ism)^##\\s*Slack\\s+message\\s*\r?\n(.*?)(?=^##\\s|\\z)/)
    if (m.find()) {
      return (m.group(1) ?: '').trim()
    }
    return ''
  }

  /**
   * Strip plan block.
   * @param s Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String stripPlanBlock(String s) {
    String t = s
    t = t.replaceAll('(?ism)^##\\s*Plan\\s*$.*?(?=^##\\s|$)', '')
    t = t.replaceAll('(?ism)^#\\s*Plan\\s*$.*?(?=^#\\s|$)', '')
    return t.trim()
  }

  /**
   * Common markdown → Slack mrkdwn transforms (bold, links, bullets, headings).
   */
  static String markdownToSlackMrkdwn(String md) {
    if (!(md?.trim())) {
      return ''
    }
    String s = md.trim()
    s = s.replaceAll('(?m)^#{3,6}\\s+(.+)$') { _, title ->
      "\n*${title.trim()}*\n"
    }
    s = s.replaceAll('(?m)^#{1,2}\\s+(.+)$') { _, title ->
      "\n*${title.trim()}*\n"
    }
    s = s.replaceAll('(?m)^\\*\\*([A-Za-z][^*\\n]{1,60})\\*\\*\\s*$') { _, t ->
      "\n*${t.trim()}*\n"
    }
    s = s.replaceAll(/\*\*([^*]+)\*\*/, '*$1*')
    s = s.replaceAll(/__([^_]+)__/, '*$1*')
    s = s.replaceAll(/\[([^\]]+)\]\(([^)]+)\)/) { _, label, url ->
      String u = (url ?: '').trim()
      if (u.startsWith('http://') || u.startsWith('https://')) {
        return "<${u}|${label}>"
      }
      return label
    }
    s = s.replaceAll('(?m)^\\s*[-*]\\s+', '• ')
    s = s.replaceAll('(?m)^\\s*\\d+\\.\\s+', '• ')
    s = s.replaceAll('(?m)^---+$', '')
    s = s.replaceAll('(?is)```[\\w]*\\n?', '')
    s = s.replaceAll('```', '')
    s = s.replaceAll(/\n{3,}/, '\n\n').trim()
    return s
  }
}
