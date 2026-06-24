package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import org.dom4j.Element

import java.util.Locale

/**
 * Reads author-visible text from content XML without assuming field ids from any one blueprint.
 */
final class ContentXmlTextScan {

  private ContentXmlTextScan() {}

  static String textOfFirst(Element root, String childName) {
    if (root == null || !childName?.trim()) {
      return ''
    }
    Element child = root.element(childName.trim())
    if (child == null) {
      return ''
    }
    String t = child.getTextTrim()
    if (t) {
      return t
    }
    String cdata = child.getStringValue()?.trim()
    return cdata ?: ''
  }

  /** First {@code *_t} field whose id suggests title, subject, or headline. */
  static String firstTitleLikeFieldText(Element root) {
    if (root == null) {
      return ''
    }
    for (Element child : root.elements()) {
      String name = child?.name ?: ''
      String nl = name.toLowerCase(Locale.ROOT)
      if (nl.endsWith('_t') && (nl.contains('title') || nl.contains('subject') || nl.contains('headline'))) {
        String t = elementTextTrim(child)
        if (t) {
          return t
        }
      }
    }
    return ''
  }

  /** Best-effort label for slug alignment — internal-name, then title-like fields, then any {@code *_t}. */
  static String slugLabelCandidate(Element root) {
    String internal = textOfFirst(root, 'internal-name')
    if (internal) {
      return internal
    }
    String titleLike = firstTitleLikeFieldText(root)
    if (titleLike) {
      return titleLike
    }
    if (root == null) {
      return ''
    }
    for (Element child : root.elements()) {
      String name = child?.name ?: ''
      if (name.endsWith('_t')) {
        String t = elementTextTrim(child)
        if (t) {
          return t
        }
      }
    }
    return ''
  }

  private static String elementTextTrim(Element el) {
    if (el == null) {
      return ''
    }
    String t = el.getTextTrim()
    if (t) {
      return t
    }
    return el.getStringValue()?.trim() ?: ''
  }
}
