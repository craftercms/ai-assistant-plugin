package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal

import org.dom4j.DocumentException
import org.dom4j.io.SAXReader
import plugins.org.craftercms.aiassistant.engine.prompt.ToolPrompts

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

/** Shared repository path/XML helpers for CMS built-in tools. */
final class CmsRepositorySupport {

  static final String CONTENT_REF_HEAD = 'HEAD'
  private static final Pattern REPOSITORY_PATH_VERSION_SUFFIX =
    Pattern.compile('^(.*)-v(\\d+)$', Pattern.CASE_INSENSITIVE)

  /**
   * Private constructor; not for direct use.
   */
private CmsRepositorySupport() {}

  /**
   * Ensures a repository path is non-empty and starts with {@code /}.
   * @param value Path or URL fragment from tool arguments.
   * @param fieldName Parameter label used in validation errors.
   * @return Normalized path with leading slash.
   */
  static String normalizeLeadingSlash(def value, String fieldName) {
    String normalized = (value ?: '').toString().trim()
    if (!normalized) {
      throw new IllegalArgumentException("Missing required parameter: ${fieldName}")
    }
    if (!normalized.startsWith('/')) {
      throw new IllegalArgumentException("${fieldName} must start with '/': ${normalized}")
    }
    return normalized
  }

  /**
   * Reads an input stream to a UTF-8 string and closes the stream.
   * @param streamLike Open stream from repository read APIs.
   * @return File body as UTF-8 text, or null when the stream is null.
   */
  static String slurpInputStreamUtf8(Object streamLike) {
    if (streamLike == null) {
      return null
    }
    InputStream is = streamLike as InputStream
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(32, is.available() > 0 ? is.available() : 8192))
      byte[] buf = new byte[8192]
      int n
      while ((n = is.read(buf)) != -1) {
        baos.write(buf, 0, n)
      }
      return new String(baos.toByteArray(), StandardCharsets.UTF_8)
    } finally {
      try {
        is.close()
      } catch (Throwable ignored) {
      }
    }
  }

  /**
   * Extracts first tag value from repository XML or related text.
   * @param xml Caller-supplied input.
   * @param tagName Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String extractFirstTagValue(String xml, String tagName) {
    if (!xml || !tagName) {
      return null
    }
    try {
      def re = ~/(?s)<${java.util.regex.Pattern.quote(tagName)}>(.*?)<\/${java.util.regex.Pattern.quote(tagName)}>/
      def m = re.matcher(xml)
      if (m.find()) {
        return m.group(1)?.toString()?.trim()
      }
    } catch (Throwable ignored) {
    }
    return null
  }

  /**
   * True when likely xml repository path.
   * @param fullPath Caller-supplied input.
   * @return True when the check succeeds.
   */
  static boolean isLikelyXmlRepositoryPath(String fullPath) {
    if (!fullPath) {
      return false
    }
    return fullPath.toLowerCase(Locale.ROOT).endsWith('.xml')
  }

  /**
   * Adds derived site item content type from xml entries to the tool result map when applicable.
   * @param normalizedPath Caller-supplied input.
   * @param xmlBody Caller-supplied input.
   * @param sink Mutable map receiving tool diagnostics or output fields.
   */
  static void attachSiteItemContentTypeFromXml(String normalizedPath, String xmlBody, Map sink) {
    if (!sink || !normalizedPath || xmlBody == null) {
      return
    }
    String p = normalizedPath.toString()
    if (!p.startsWith('/site/') || !p.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      return
    }
    String ct = extractFirstTagValue(xmlBody.toString(), 'content-type')
    if (!ct) {
      return
    }
    ct = ct.trim()
    if (!ct.startsWith('/')) {
      return
    }
    sink.put('contentTypeIdFromXml', ct)
    sink.put(
      'contentTypeCatalogHint',
      "This file's <content-type> is **${ct}**. For **GetContentTypeFormDefinition** targeting **this same repository file**, pass **contentTypeId='${ct}'** or **contentPath='${p}'**. Do **not** substitute a different **contentTypeId** than **${ct}** for this path."
    )
  }

  /**
   * Adds derived xml read diagnostics entries to the tool result map when applicable.
   * @param normalizedPath Caller-supplied input.
   * @param xmlBody Caller-supplied input.
   * @param sink Mutable map receiving tool diagnostics or output fields.
   */
  static void attachXmlReadDiagnostics(String normalizedPath, String xmlBody, Map sink) {
    if (!sink || !isLikelyXmlRepositoryPath(normalizedPath) || xmlBody == null) {
      return
    }
    if (!xmlBody.toString().trim()) {
      sink.put('xmlWellFormed', false)
      sink.put('xmlParseError', 'Empty or whitespace-only repository file body')
      sink.put('xmlRepairReminder', ToolPrompts.XML_REPAIR_REMINDER_AFTER_BAD_READ)
      return
    }
    try {
      newHardenedSaxReader().read(new StringReader(xmlBody))
      sink.put('xmlWellFormed', true)
    } catch (DocumentException e) {
      String msg = e.message ?: e.toString()
      sink.put('xmlWellFormed', false)
      sink.put('xmlParseError', msg)
      sink.put('xmlRepairReminder', ToolPrompts.XML_REPAIR_REMINDER_AFTER_BAD_READ)
    }
  }

  /**
   * When a repository path already exists, suggest a sibling filename (site-agnostic suffix pattern).
   */
  static String suggestAlternateRepositoryPath(String existingPath) {
    String p = (existingPath ?: '').toString().trim()
    if (!p || !p.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      return ''
    }
    int slash = p.lastIndexOf('/')
    String dir = slash >= 0 ? p.substring(0, slash + 1) : ''
    String file = slash >= 0 ? p.substring(slash + 1) : p
    if (!file.endsWith('.xml')) {
      return ''
    }
    String base = file.substring(0, file.length() - 4)
    def versioned = REPOSITORY_PATH_VERSION_SUFFIX.matcher(base)
    if (versioned.find()) {
      int n = Integer.parseInt(versioned.group(2)) + 1
      return dir + versioned.group(1) + '-v' + n + '.xml'
    }
    return dir + base + '-v2.xml'
  }

  /**
   * Walks {@link #suggestAlternateRepositoryPath} until {@code exists} is false or {@code maxAttempts} exhausted.
   */
  static String resolveFirstAvailableRepositoryPath(
    Closure<Map> existsProbe,
    String initialPath,
    int maxAttempts = 10
  ) {
    String candidate = (initialPath ?: '').toString().trim()
    if (!candidate || !(existsProbe instanceof Closure)) {
      return candidate
    }
    int attempts = Math.max(1, maxAttempts)
    for (int i = 0; i < attempts; i++) {
      Map res = existsProbe.call(candidate) as Map
      if (!Boolean.TRUE.equals(res?.get('exists'))) {
        return candidate
      }
      String next = suggestAlternateRepositoryPath(candidate)
      if (!next || next.equalsIgnoreCase(candidate)) {
        break
      }
      candidate = next
    }
    return candidate
  }

  /**
   * Creates a configured hardened sax reader.
   * @return SAXReader result.
   */
  /** SAXReader with DOCTYPE and external entities disabled (untrusted model/tool XML). */
  static SAXReader newHardenedSaxReader() {
    SAXReader reader = new SAXReader()
    reader.setValidation(false)
    try {
      reader.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
    } catch (Throwable ignored) {
    }
    try {
      reader.setFeature('http://apache.org/xml/features/nonvalidating/load-external-dtd', false)
    } catch (Throwable ignored) {
    }
    try {
      reader.setFeature('http://xml.org/sax/features/external-general-entities', false)
    } catch (Throwable ignored) {
    }
    try {
      reader.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
    } catch (Throwable ignored) {
    }
    try {
      reader.setEntityResolver({ String publicId, String systemId ->
        new org.xml.sax.InputSource(new StringReader(''))
      })
    } catch (Throwable ignored) {
    }
    return reader
  }
}
