package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import groovy.json.JsonSlurper

/**
 * Filters tools-loop invocations on {@code new_content_item} fast path: prefetch already loaded catalog,
 * form definition, taxonomy keys, and suggested path — discovery tools and placeholder WriteContent are dropped
 * before execution so the model is nudged to emit one native {@code WriteContent} with full XML.
 */
final class NewContentItemFastPathToolFilter {

  private static final List<String> DISCOVERY_UNTIL_WRITE = Collections.unmodifiableList([
    'ListStudioContentTypes',
    'GetContentTypeFormDefinition',
    'GetContent',
    'ContentExists',
    'ResearchSiteContent',
    'ListPagesAndComponents'
  ])

  private NewContentItemFastPathToolFilter() {}

  static boolean isFastPath(Map intentTelLoop) {
    if (!(intentTelLoop instanceof Map)) {
      return false
    }
    if (!Boolean.TRUE.equals(intentTelLoop.get('toolsLoopFastPath'))) {
      return false
    }
    String supplement = intentTelLoop.get('toolsLoopPrefetchSupplement')?.toString()?.trim() ?: ''
    if ('newContentItem'.equals(supplement)) {
      return true
    }
    return 'new_content_item'.equals(intentTelLoop.get('recipeId')?.toString()?.trim())
  }

  /**
   * @return {@code [filtered: List, placeholderWriteDropped: boolean, discoveryDropped: int]}
   */
  static Map filterRunList(List runList, Map intentTelLoop, JsonSlurper slurper) {
    Map out = [filtered: runList ?: [], placeholderWriteDropped: Boolean.FALSE, discoveryDropped: 0]
    if (!(runList instanceof List) || runList.isEmpty() || !isFastPath(intentTelLoop)) {
      return out
    }
    List filtered = []
    boolean placeholderWriteDropped = false
    int discoveryDropped = 0
    for (Object tcObj : runList) {
      if (!(tcObj instanceof Map)) {
        continue
      }
      Map tc = (Map) tcObj
      Map fn = tc.get('function') instanceof Map ? (Map) tc.get('function') : [:]
      String fnName = (fn.get('name') ?: '').toString().trim()
      if (!fnName) {
        continue
      }
      if (DISCOVERY_UNTIL_WRITE.contains(fnName)) {
        discoveryDropped++
        continue
      }
      if ('WriteContent'.equals(fnName)) {
        String contentXml = extractContentXmlFromArgs(fn.get('arguments')?.toString(), slurper)
        if (contentXmlLooksLikePlaceholder(contentXml)) {
          placeholderWriteDropped = true
          continue
        }
      }
      filtered.add(tc)
    }
    out.filtered = filtered
    out.placeholderWriteDropped = placeholderWriteDropped
    out.discoveryDropped = discoveryDropped
    return out
  }

  static String buildPlaceholderWriteNudge(Map intentTelLoop) {
    String suggested = (intentTelLoop?.toolsLoopSuggestedNewItemPath ?: '').toString().trim()
    List required = []
    Object reqObj = intentTelLoop?.toolsLoopFormDefinitionValidationPlan instanceof Map ?
      ((Map) intentTelLoop.toolsLoopFormDefinitionValidationPlan).requiredFieldIds :
      null
    if (reqObj instanceof List) {
      for (Object o : (List) reqObj) {
        String r = o?.toString()?.trim()
        if (r) {
          required.add(r)
        }
      }
    }
    StringBuilder sb = new StringBuilder()
    sb.append('[aiassistant: new-content-item fast path — internal]\n')
    sb.append(
      'Server **prefetch is complete** (catalog, form definition, **writeContentMaterials**, taxonomy keys, suggested path). '
    )
    sb.append(
      'Do **not** use **## Plan** or fenced JSON `tool_uses` with placeholder **contentXml** (comments like `<!-- declare XML here -->` are rejected).\n\n'
    )
    sb.append(
      'Reply with **native `tool_calls` only**: **one** **WriteContent** whose **contentXml** is the **complete** `<page>` or `<component>` document inline — envelope fields, every required field, minSize collections, and **full original author copy** in repeat RTE fields (**at least 3 `<p>` paragraphs**, ~150+ words when the author asked for an article/story).\n'
    )
    if (required) {
      sb.append('\nRequired fields: `').append(required.join('`, `')).append('`.\n')
    }
    if (suggested) {
      sb.append('WriteContent path: `').append(suggested).append('`.\n')
    }
    sb.append('Use a plausible **author_s** — not "Author Name". Match tone to the author request (e.g. kid-friendly when they asked for kids).\n')
    return sb.toString()
  }

  static String buildAuthorCopyQualityHint(String wirePrompt) {
    String prompt = (wirePrompt ?: '').trim()
    if (!prompt) {
      return ''
    }
    String lower = prompt.toLowerCase(Locale.ROOT)
    StringBuilder sb = new StringBuilder()
    sb.append('**Author copy quality:** Write **original** prose for this request — not one-sentence placeholders. ')
    if (lower.contains('kid') || lower.contains('child') || lower.contains('children')) {
      sb.append('Use a **kid-friendly** voice (short sentences, wonder, safe adventure). ')
    }
    sb.append(
      'Fill repeat RTE fields with **multiple paragraphs** of story/body copy. **summary_t** should tease the piece; **subject_t** reflects the topic.\n'
    )
    return sb.toString()
  }

  static boolean contentXmlLooksLikePlaceholder(String contentXml) {
    String s = (contentXml ?: '').toString().trim()
    if (!s) {
      return true
    }
    if ('NEW_CONTENT_XML_HERE'.equalsIgnoreCase(s)) {
      return true
    }
    if (s.contains('PLACEHOLDER_IMAGE_URL')) {
      return true
    }
    String lower = s.toLowerCase(Locale.ROOT)
    if (lower.contains('_here') || lower.contains('constructed_content') ||
      lower.contains('content_xml_here') || lower.contains('your_content')) {
      return true
    }
    if (lower.contains('declare the full') || lower.contains('well-formed xml for the new') ||
      lower.contains('xml for the new article here')) {
      return true
    }
    if (s.startsWith('<!--') && !s.contains('<page>') && !s.contains('<component>')) {
      return true
    }
    if (s.length() < 400 && (lower.contains('placeholder') || lower.contains('todo:'))) {
      return true
    }
    return !s.contains('<page>') && !s.contains('<component>')
  }

  private static String extractContentXmlFromArgs(String argsStr, JsonSlurper slurper) {
    try {
      Object parsed = slurper.parseText(argsStr ?: '{}')
      if (!(parsed instanceof Map)) {
        return ''
      }
      Map args = (Map) parsed
      return (args.get('contentXml') ?: args.get('content') ?: '').toString()
    } catch (Throwable ignored) {
      return ''
    }
  }
}
