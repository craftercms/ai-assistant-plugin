package plugins.org.craftercms.aiassistant.tools.cms.support

import org.dom4j.DocumentException
import org.dom4j.io.SAXReader
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Shared repository path/XML helpers for CMS built-in tools. */
final class CmsRepositorySupport {

  static final String CONTENT_REF_HEAD = 'HEAD'

  private CmsRepositorySupport() {}

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

  static boolean isLikelyXmlRepositoryPath(String fullPath) {
    if (!fullPath) {
      return false
    }
    return fullPath.toLowerCase(Locale.ROOT).endsWith('.xml')
  }

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
      "This file's <content-type> is **${ct}**. For **GetContentTypeFormDefinition** targeting **this same repository file**, pass **contentTypeId='${ct}'** or **contentPath='${p}'**. Do **not** substitute **/page/page_generic** unless **${ct}** is literally **/page/page_generic**."
    )
  }

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

  private static SAXReader newHardenedSaxReader() {
    SAXReader reader = new SAXReader()
    reader.setValidation(false)
    try {
      reader.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
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
    return reader
  }
}
