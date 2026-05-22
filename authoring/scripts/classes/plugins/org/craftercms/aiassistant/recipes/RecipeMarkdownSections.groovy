package plugins.org.craftercms.aiassistant.recipes

/**
 * Generic markdown section extract/replace for confirmation {@code llmRefine} when {@code outputFormat} is
 * {@code markdown} and {@code markdownSection} names one {@code ## <heading>} block.
 */
final class RecipeMarkdownSections {

  private RecipeMarkdownSections() {}

  static String extractSection(String markdown, String sectionHeading) {
    String s = (markdown ?: '').toString().trim()
    if (!s) {
      return ''
    }
    String heading = (sectionHeading ?: '').trim()
    if (!heading) {
      return s
    }
    String escaped = java.util.regex.Pattern.quote(heading)
    def h2 = (s =~ /(?ism)^##\s*${escaped}\s*\r?\n(.*?)(?=^##\s|\z)/)
    if (h2.find()) {
      return (h2.group(1) ?: '').trim()
    }
    def h3 = (s =~ /(?ism)^###\s*${escaped}\s*\r?\n(.*?)(?=^#{1,3}\s|\z)/)
    if (h3.find()) {
      return (h3.group(1) ?: '').trim()
    }
    def plain = (s =~ /(?ism)^${escaped}\s*\r?\n(.*?)(?=^(?:##\s+|###\s+|\z))/)
    if (plain.find()) {
      return (plain.group(1) ?: '').trim()
    }
    return ''
  }

  static String replaceSection(String fullMarkdown, String sectionHeading, String newBody) {
    String full = (fullMarkdown ?: '').toString().trim()
    String body = (newBody ?: '').trim()
    if (!full) {
      return body
    }
    if (!body) {
      return full
    }
    String heading = (sectionHeading ?: '').trim()
    if (!heading) {
      return body
    }
    String escaped = java.util.regex.Pattern.quote(heading)
    def h2 = (full =~ /(?ism)^##\s*${escaped}\s*\r?\n(.*?)(?=^##\s|\z)/)
    if (h2.find()) {
      int start = h2.start(1)
      int end = h2.end(1)
      return full.substring(0, start) + body + (end < full.length() ? full.substring(end) : '')
    }
    def h3 = (full =~ /(?ism)^###\s*${escaped}\s*\r?\n(.*?)(?=^#{1,3}\s|\z)/)
    if (h3.find()) {
      int start = h3.start(1)
      int end = h3.end(1)
      return full.substring(0, start) + body + (end < full.length() ? full.substring(end) : '')
    }
    def plain = (full =~ /(?ism)^${escaped}\s*\r?\n(.*?)(?=^(?:##\s+|###\s+|\z))/)
    if (plain.find()) {
      int start = plain.start(1)
      int end = plain.end(1)
      return full.substring(0, start) + body + (end < full.length() ? full.substring(end) : '')
    }
    return full + '\n\n## ' + heading + '\n\n' + body + '\n'
  }
}
