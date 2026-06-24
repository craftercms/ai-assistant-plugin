package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

/**
 * Validates {@link CmsWriteContent} bodies for {@code .css} / {@code .scss} / {@code .less} paths so LLM
 * rewrites cannot replace a full stylesheet with stubs, XML wrappers, or truncated rule sets.
 */
final class CmsStylesheetWriteGuard {

  private static final Logger log = LoggerFactory.getLogger(CmsStylesheetWriteGuard)

  /** Prefix on {@link IllegalArgumentException} messages — orchestration maps this to author-facing SSE copy. */
  static final String AUTHOR_PROTECTED_MARKER = 'STYLESHEET_PROTECTED'

  private CmsStylesheetWriteGuard() {}

  static boolean isAuthorProtectedMessage(String message) {
    return (message ?: '').toString().contains(AUTHOR_PROTECTED_MARKER)
  }

  /** Author-visible sentence(s) only — no assistant retry block. */
  static String authorVisibleFromGuardMessage(String message) {
    String m = (message ?: '').toString()
    m = m.replace(AUTHOR_PROTECTED_MARKER, '').trim()
    int split = m.indexOf('\n\nFor the assistant:')
    return split >= 0 ? m.substring(0, split).trim() : m
  }

  private static String guardMessage(String authorFacing, String assistantRetry) {
    return "${AUTHOR_PROTECTED_MARKER} ${authorFacing}\n\nFor the assistant: ${assistantRetry}"
  }

  static boolean isStylesheetPath(String path) {
    String low = (path ?: '').toString().trim().toLowerCase(Locale.ROOT)
    return low.endsWith('.css') || low.endsWith('.scss') || low.endsWith('.less')
  }

  static String normalizeBody(String raw) {
    String s = (raw ?: '').toString()
    if (!s.trim()) {
      return ''
    }
    s = s.replaceFirst(/(?is)^<\?xml[^?]*\?>\s*/, '')
    return s
  }

  static boolean looksLikePlaceholder(String body) {
    String s = normalizeBody(body)?.trim()
    if (!s) {
      return true
    }
    String lower = s.toLowerCase(Locale.ROOT)
    if (lower == '/*placeholder*/' || lower == 'placeholder') {
      return true
    }
    if (lower.contains('placeholder') && s.length() < 512) {
      return true
    }
    if (lower.contains('todo:') && s.length() < 512) {
      return true
    }
    if (s.length() < 32) {
      return true
    }
    return false
  }

  /**
   * Prepares stylesheet text for write: strip accidental XML prolog, reject stubs/truncation vs baseline.
   */
  static String prepareForWrite(StudioToolOperations ops, String siteId, String normalizedPath, String proposedBody) {
    if (!isStylesheetPath(normalizedPath)) {
      return proposedBody
    }
    String proposed = normalizeBody(proposedBody)
    if (!proposed?.trim()) {
      throw new IllegalArgumentException(
        guardMessage(
          "Your stylesheet `${normalizedPath}` was **not** changed. The LLM returned an **empty** response — Studio expected the **full** CSS file body.",
          "Call **GetContent** on `${normalizedPath}`, edit the returned CSS in place, then **WriteContent** the **full** file body."
        )
      )
    }
    if (looksLikePlaceholder(proposed)) {
      int pLen = proposed.length()
      throw new IllegalArgumentException(
        guardMessage(
          "Your stylesheet `${normalizedPath}` was **not** changed. The LLM returned a **smaller response than expected** (${pLen} characters — a placeholder or snippet, not the full file). Saving it would break most styles on the site.",
          'Send the **complete** CSS from **GetContent** with only the requested value changes — not `/*placeholder*/`, comments-only bodies, or XML. If the file is very large, tell the author which variables/selectors to edit manually in Studio.'
        )
      )
    }
    String baseline = readBaseline(ops, siteId, normalizedPath)
    if (baseline?.trim()) {
      assertPreservedStructure(normalizedPath, baseline, proposed)
    }
    log.info(
      'stylesheet write guard OK path={} baselineChars={} proposedChars={}',
      normalizedPath,
      baseline?.length() ?: 0,
      proposed.length()
    )
    return proposed
  }

  private static String readBaseline(StudioToolOperations ops, String siteId, String normalizedPath) {
    if (!ops || !siteId?.trim() || !normalizedPath?.trim()) {
      return ''
    }
    if (!CmsContentExists.existsAtPath(ops, siteId, normalizedPath)) {
      return ''
    }
    try {
      Map read = CmsGetContent.read(ops, siteId, normalizedPath, null) as Map
      return (read?.contentXml ?: '').toString()
    } catch (Throwable t) {
      log.debug('stylesheet write guard: baseline read failed path={}: {}', normalizedPath, t.message)
      return ''
    }
  }

  private static void assertPreservedStructure(String path, String baseline, String proposed) {
    String b = normalizeBody(baseline)
    String p = normalizeBody(proposed)
    int bLen = b.length()
    int pLen = p.length()
    if (bLen > 400 && pLen < (int) (bLen * 0.75d)) {
      String sizeHint = bLen > 40_000 ?
        ' Files this large are often bigger than the LLM can return in one response — tell the author which CSS variables or hex values to edit in Studio instead.' :
        ''
      throw new IllegalArgumentException(
        guardMessage(
          "Your stylesheet `${path}` was **not** changed. The LLM returned a **smaller response than expected** (${pLen} characters vs ${bLen} in the current file). Studio requires the **full** stylesheet with only your requested value edits — accepting a partial response would remove most of your site's CSS.${sizeHint}",
          "You must **WriteContent** the **entire** stylesheet returned by **GetContent**, changing only the values the author asked for. LLM response ${pLen} chars vs file ${bLen} chars."
        )
      )
    }
    int bBlocks = countChar(b, '{')
    int pBlocks = countChar(p, '{')
    if (bBlocks >= 4 && pBlocks < bBlocks - 1) {
      throw new IllegalArgumentException(
        guardMessage(
          "Your stylesheet `${path}` was **not** changed. The LLM returned a **smaller response than expected** — it dropped CSS rules or blocks (${pBlocks} vs ${bBlocks} in the current file). Studio blocked the write so selectors and layout stay intact.",
          'Preserve every selector, property name, `@` rule, and block — edit **values only** unless the author asked to restructure CSS.'
        )
      )
    }
    int bSemi = countChar(b, ';')
    int pSemi = countChar(p, ';')
    if (bSemi >= 8 && pSemi < (int) (bSemi * 0.70d)) {
      throw new IllegalArgumentException(
        guardMessage(
          "Your stylesheet `${path}` was **not** changed. The LLM returned a **smaller response than expected** — it dropped too many CSS declarations (${pSemi} vs ${bSemi} in the current file). Studio blocked the write on purpose.",
          'Do not rewrite or summarize the stylesheet — copy **GetContent** output and change only the requested tokens/values.'
        )
      )
    }
  }

  private static int countChar(String s, char ch) {
    if (!s) {
      return 0
    }
    int n = 0
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == ch) {
        n++
      }
    }
    return n
  }
}
