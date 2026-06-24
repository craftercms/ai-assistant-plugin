package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.integrations

/**
 * Rewrites ambiguous bare {@code CMS} web-search queries so Google/SerpAPI target
 * <strong>content management systems</strong>, not US healthcare (Centers for Medicare & Medicaid Services).
 */
import java.util.Locale

/**
 * : . Contrib implementation used by the plugin runtime.
 */
final class OpenWebSearchQueryDisambiguation {

  /**
   * Private constructor; not for direct use.
   */
private OpenWebSearchQueryDisambiguation() {}

  /**
   * @param query model-supplied search string
   * @return map with {@code queryOriginal}, {@code querySent}, {@code queryExpanded} (boolean)
   */
  static Map disambiguate(String query) {
    String original = query?.trim() ?: ''
    if (!original) {
      return [queryOriginal: '', querySent: '', queryExpanded: false]
    }
    if (original =~ /(?i)\bcontent management system\b/) {
      return [queryOriginal: original, querySent: original, queryExpanded: false]
    }
    if (!(original =~ /(?i)\bcms\b/)) {
      return [queryOriginal: original, querySent: original, queryExpanded: false]
    }
    if (original =~ /(?i)\b(medicare|medicaid|enrollment|hospice|home health|medical billing)\b/) {
      return [queryOriginal: original, querySent: original, queryExpanded: false]
    }
    String sent = original.replaceAll(/(?i)\bcms\b/, 'content management system')
    if (!(sent =~ /(?i)-medicare\b/)) {
      sent = sent + ' -medicare -medicaid'
    }
    if (!(sent =~ /(?i)\b(headless|digital experience|dxp|wcm|composable)\b/)) {
      sent = sent + ' headless CMS'
    }
    sent = sent.replaceAll(/\s+/, ' ').trim()
    boolean expanded = !sent.equalsIgnoreCase(original)
    return [queryOriginal: original, querySent: sent, queryExpanded: expanded]
  }

  /**
   * When SerpAPI {@code tbs} already bounds recency (e.g. {@code qdr:w}), strip redundant month/year
   * tokens from the model query — they often yield zero Google results and encourage overly broad strings.
   */
  static Map optimizeForTbsRecency(String query, Map serpParams) {
    String original = query?.trim() ?: ''
    String sent = original
    if (!original) {
      return [queryOriginal: '', querySent: '', queryRecencyOptimized: false]
    }
    String tbs = serpParams?.tbs?.toString()?.trim() ?: ''
    if (!tbs || !tbs.toLowerCase(Locale.ROOT).startsWith('qdr:')) {
      return [queryOriginal: original, querySent: sent, queryRecencyOptimized: false]
    }
    String trimmed =
      sent
        .replaceAll(
          /(?i)\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+20\d{2}\b/,
          ' '
        )
        .replaceAll(/(?i)\bpast\s+week\b/, ' ')
        .replaceAll(/\s+/, ' ')
        .trim()
    if (trimmed) {
      sent = trimmed
    }
    boolean optimized = !sent.equalsIgnoreCase(original)
    return [queryOriginal: original, querySent: sent, queryRecencyOptimized: optimized]
  }
}
