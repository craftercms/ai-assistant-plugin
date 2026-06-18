package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import org.dom4j.Document
import org.dom4j.Element
import org.dom4j.io.SAXReader

import java.io.StringReader
import java.util.Collection
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Set
import java.util.regex.Pattern

/**
 * Shared XML field extraction and normalization for version find/compare tools.
 */
final class CmsContentVersionXml {

  private static final Set<String> SYSTEM_FIELD_IDS = Collections.unmodifiableSet([
    'file-name',
    'internal-name',
    'objectId',
    'content-type',
    'display-template',
    'createdDate_dt',
    'lastModifiedDate_dt',
    'merge-strategy'
  ] as Set)

  private CmsContentVersionXml() {}

  static String roughPlainTextFromHtml(String html) {
    if (!html?.trim()) {
      return ''
    }
    return html
      .replaceAll('(?is)<script[^>]*>[\\s\\S]*?</script>', ' ')
      .replaceAll('(?is)<style[^>]*>[\\s\\S]*?</style>', ' ')
      .replaceAll('<[^>]+>', ' ')
      .replaceAll('\\s+', ' ')
      .trim()
  }

  static boolean plainTextContainsIgnoreCase(String haystackPlain, String needle) {
    if (!needle?.trim() || !haystackPlain) {
      return false
    }
    return haystackPlain.toLowerCase(Locale.ROOT).contains(needle.trim().toLowerCase(Locale.ROOT))
  }

  static String extractFieldRawText(String contentXml, String fieldId) {
    if (!contentXml?.trim() || !fieldId?.trim()) {
      return ''
    }
    Element root = parseRoot(contentXml)
    if (root != null) {
      Element child = root.element(fieldId.trim())
      if (child != null) {
        return elementRawText(child)
      }
    }
    return extractFieldRawTextRegex(contentXml, fieldId.trim())
  }

  static String extractFieldRoughPlainText(String contentXml, String fieldId) {
    String raw = extractFieldRawText(contentXml, fieldId)
    return roughPlainTextFromHtml(raw)
  }

  static String roughPlainTextFromWholeXml(String contentXml) {
    Element root = parseRoot(contentXml)
    if (root == null) {
      return roughPlainTextFromHtml(contentXml)
    }
    StringBuilder sb = new StringBuilder()
    for (Element child : root.elements()) {
      if (isSystemField(child?.name)) {
        continue
      }
      String raw = elementRawText(child)
      if (raw) {
        if (sb.length() > 0) {
          sb.append(' ')
        }
        sb.append(roughPlainTextFromHtml(raw))
      }
    }
    return sb.toString().trim()
  }

  static List<String> listComparableFieldIds(String contentXml) {
    Element root = parseRoot(contentXml)
    if (root == null) {
      return []
    }
    List<String> ids = []
    for (Element child : root.elements()) {
      String name = child?.name ?: ''
      if (!name || isSystemField(name)) {
        continue
      }
      ids.add(name)
    }
    return ids
  }

  static Map<String, String> fieldValuesById(String contentXml, Collection<String> fieldIds) {
    Map<String, String> out = new LinkedHashMap<>()
    if (!contentXml?.trim()) {
      return out
    }
    Element root = parseRoot(contentXml)
    if (root == null) {
      return out
    }
    Set<String> wanted = new LinkedHashSet<>()
    for (def id : (fieldIds ?: [])) {
      String t = (id ?: '').toString().trim()
      if (t) {
        wanted.add(t)
      }
    }
    if (wanted.isEmpty()) {
      for (Element child : root.elements()) {
        String name = child?.name ?: ''
        if (!name || isSystemField(name)) {
          continue
        }
        wanted.add(name)
      }
    }
    for (String fieldId : wanted) {
      Element child = root.element(fieldId)
      out.put(fieldId, child != null ? elementRawText(child) : '')
    }
    return out
  }

  static boolean fieldValuesEqual(String left, String right) {
    String a = normalizeComparableValue(left)
    String b = normalizeComparableValue(right)
    return a == b
  }

  static String truncateForTool(String value, int maxChars = 500) {
    String s = (value ?: '').toString()
    int cap = Math.max(80, maxChars)
    if (s.length() <= cap) {
      return s
    }
    return s.substring(0, cap - 1) + '…'
  }

  static boolean isSystemField(String fieldId) {
    String id = (fieldId ?: '').toString().trim()
    return id && SYSTEM_FIELD_IDS.contains(id)
  }

  static List<String> imagePickerFieldIds(String contentXml) {
    List<String> ids = []
    Element root = parseRoot(contentXml)
    if (root == null) {
      return ids
    }
    for (Element child : root.elements()) {
      String name = child?.name ?: ''
      if (name.endsWith('_s') && !isSystemField(name)) {
        ids.add(name)
      }
    }
    return ids
  }

  private static String normalizeComparableValue(String value) {
    return (value ?: '').toString().replaceAll('\\s+', ' ').trim()
  }

  private static Element parseRoot(String contentXml) {
    if (!contentXml?.trim()) {
      return null
    }
    try {
      SAXReader reader = new SAXReader()
      reader.setValidation(false)
      try {
        reader.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
      } catch (Throwable ignored) {
      }
      Document doc = reader.read(new StringReader(contentXml))
      return doc?.rootElement
    } catch (Throwable ignored) {
      return null
    }
  }

  private static String elementRawText(Element el) {
    if (el == null) {
      return ''
    }
    String t = el.getTextTrim()
    if (t) {
      return t
    }
    return el.getStringValue()?.trim() ?: ''
  }

  private static String extractFieldRawTextRegex(String contentXml, String fieldId) {
    String tagQuoted = Pattern.quote(fieldId)
    def mCdata = (contentXml =~ "(?is)<${tagQuoted}>\\s*<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>\\s*</${tagQuoted}>")
    if (mCdata.find()) {
      return mCdata.group(1)?.toString()?.trim() ?: ''
    }
    def mEsc = (contentXml =~ "(?is)<${tagQuoted}>([\\s\\S]*?)</${tagQuoted}>")
    if (mEsc.find()) {
      return mEsc.group(1)?.toString()?.trim() ?: ''
    }
    return ''
  }
}
