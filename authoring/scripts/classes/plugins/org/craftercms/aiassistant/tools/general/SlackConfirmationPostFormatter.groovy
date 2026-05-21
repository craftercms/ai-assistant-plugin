package plugins.org.craftercms.aiassistant.tools.general

/**
 * Converts assistant markdown to Slack mrkdwn for recipe confirmation when {@code text} is omitted.
 * <p>No workflow-specific parsing — sites choose what the model writes in chat; this helper only
 * strips orchestration plan blocks and applies generic markdown → mrkdwn rules. Used from
 * {@link SlackPostMessageTool#applyRecipeConfirmationArgDefaults}.</p>
 */
final class SlackConfirmationPostFormatter {

  private static final int MAX_SLACK_CHARS = 39_000

  private SlackConfirmationPostFormatter() {}

  /**
   * @param raw assistant markdown (caller should already strip {@code CRAFTERRQ_ORCH} / plan blocks when possible)
   * @return Slack mrkdwn body, or empty when {@code raw} is blank
   */
  static String formatAssistantProseForSlack(String raw) {
    String s = (raw ?: '').trim()
    if (!s) {
      return ''
    }
    s = stripPlanBlock(s).trim()
    if (!s) {
      return ''
    }
    String result = markdownToSlackMrkdwn(s)
    if (result.length() > MAX_SLACK_CHARS) {
      result = result.substring(0, MAX_SLACK_CHARS - 80) + '\n\n… _(truncated for Slack limit)_'
    }
    return result
  }

  /** Removes leading {@code ## Plan} / {@code # Plan} sections so confirmation posts focus on outcomes. */
  private static String stripPlanBlock(String s) {
    String t = s
    t = t.replaceAll('(?ism)^##\\s*Plan\\s*$.*?(?=^##\\s|$)', '')
    t = t.replaceAll('(?ism)^#\\s*Plan\\s*$.*?(?=^#\\s|$)', '')
    return t.trim()
  }

  /**
   * Converts common markdown patterns to Slack mrkdwn ({@code *bold*}, {@code <url|label>}, bullets).
   *
   * @param md markdown text
   * @return Slack mrkdwn string
   */
  static String markdownToSlackMrkdwn(String md) {
    if (!(md?.trim())) {
      return ''
    }
    String s = md.trim()
    s = s.replaceAll('(?m)^#{3,6}\\s+(.+)$') { _, title ->
      "\n:small_blue_diamond: *${title.trim()}*\n"
    }
    s = s.replaceAll('(?m)^#{1,2}\\s+(.+)$') { _, title ->
      "\n:bookmark: *${title.trim()}*\n"
    }
    s = s.replaceAll('(?m)^\\*\\*([A-Za-z][^*\\n]{1,60})\\*\\*\\s*$') { _, t ->
      "\n:small_blue_diamond: *${t.trim()}*\n"
    }
    s = s.replaceAll(/\*\*([^*]+)\*\*/, '*$1*')
    s = s.replaceAll(/__([^_]+)__/, '*$1*')
    s = s.replaceAll(/\[([^\]]+)\]\(([^)]+)\)/, '<$2|$1>')
    s = s.replaceAll('(?m)^\\s*[-*]\\s+', '• ')
    s = s.replaceAll('(?m)^\\s*\\d+\\.\\s+', '• ')
    s = s.replaceAll('(?m)^---+$', '')
    s = s.replaceAll('(?is)```[\\w]*\\n?', '')
    s = s.replaceAll('```', '')
    s = s.replaceAll(/\n{3,}/, '\n\n').trim()
    return s
  }
}
