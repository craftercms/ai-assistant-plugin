package plugins.org.craftercms.aiassistant.studio.engine.turn

import groovy.json.JsonSlurper

import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.List
import java.util.Map
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Per-turn registry of compact facts from successful tools-loop tool calls.
 * Injected before the next LLM round so multi-step plans use prior tool output — not literal
 * paraphrases of the author's routing words when concrete tool data is already available.
 */
final class ToolsLoopTurnArtifacts {

  private static final String BUNDLE_KEY = 'toolsLoopTurnArtifacts'
  private static final int MAX_FIELD_CHARS = 480
  private static final int MAX_SNIPPET_CHARS = 600

  private ToolsLoopTurnArtifacts() {}

  static void clear(Map toolsLoopSessionBundle) {
    if (toolsLoopSessionBundle instanceof Map) {
      toolsLoopSessionBundle.remove(BUNDLE_KEY)
      toolsLoopSessionBundle.remove('toolsLoopTurnArtifactsInjectedCount')
      toolsLoopSessionBundle.remove('toolsLoopStepBridgeArtifactEmitted')
      toolsLoopSessionBundle.remove('toolsLoopStepBridgeLastKey')
    }
  }

  /**
   * Seeds artifacts from routing-engine prefetch (WebSearch / FetchHttpUrl before the tools loop).
   */
  static void seedFromRoutingPrefetch(Map toolsLoopSessionBundle, JsonSlurper slurper = null) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return
    }
    List outputs = toolsLoopSessionBundle.routingPrefetchToolOutputs instanceof List ?
      (List) toolsLoopSessionBundle.routingPrefetchToolOutputs :
      []
    if (outputs.isEmpty()) {
      return
    }
    JsonSlurper parser = slurper != null ? slurper : new JsonSlurper()
    for (Object o : outputs) {
      if (!(o instanceof Map)) {
        continue
      }
      Map row = (Map) o
      String tool = row.tool?.toString()?.trim()
      Map result = row.result instanceof Map ? (Map) row.result : null
      if (!tool || !result) {
        continue
      }
      try {
        record(toolsLoopSessionBundle, tool, groovy.json.JsonOutput.toJson(result), null, parser)
      } catch (Throwable ignored) {
      }
    }
  }

  /**
   * Records a compact artifact from a completed tool call (raw JSON string preferred).
   *
   * @param toolInputJson optional tool call arguments JSON (for GenerateImage prompt, etc.)
   */
  static void record(
    Map toolsLoopSessionBundle,
    String wireName,
    String toolOutJson,
    String toolInputJson = null,
    JsonSlurper slurper = null
  ) {
    if (!(toolsLoopSessionBundle instanceof Map) || !wireName?.trim() || !toolOutJson?.trim()) {
      return
    }
    JsonSlurper parser = slurper != null ? slurper : new JsonSlurper()
    Object parsed
    try {
      parsed = parser.parseText(toolOutJson.toString())
    } catch (Throwable ignored) {
      return
    }
    if (!(parsed instanceof Map)) {
      return
    }
    Map m = (Map) parsed
    if (Boolean.FALSE.equals(m.get('ok')) && !'written'.equalsIgnoreCase(m.get('result')?.toString()?.trim())) {
      return
    }
    Map artifact = buildArtifact(wireName.trim(), m, toolInputJson, parser)
    if (artifact.isEmpty()) {
      return
    }
    List<Map> list = artifactsList(toolsLoopSessionBundle)
    list.add(artifact)
    toolsLoopSessionBundle.put(BUNDLE_KEY, list)
    String salient = artifact.salientFact?.toString()?.trim()
    if (salient && Boolean.TRUE.equals(artifact.usableExternalFact)) {
      toolsLoopSessionBundle.toolsLoopUsableExternalFact = Boolean.TRUE
      toolsLoopSessionBundle.toolsLoopLastSalientFact = salient
    }
  }

  /**
   * User-role block for the next tools-loop LLM round when artifacts exist.
   */
  static String formatInjectionBlock(Map toolsLoopSessionBundle) {
    List<Map> list = artifactsList(toolsLoopSessionBundle)
    if (list.isEmpty()) {
      return ''
    }
    StringBuilder sb = new StringBuilder()
    sb.append('[Studio — prior tool outputs this turn]\n')
    sb.append(
      'Use the **factual data** below for subsequent tool calls (writes, updates, image generation). '
    )
    sb.append(
      'When a prior step returned concrete values, pass them forward — do **not** substitute generic placeholders '
    )
    sb.append('derived only from the author\'s routing words.\n\n')
    int i = 1
    for (Map art : list) {
      String tool = (art.tool ?: '').toString()
      String line = (art.summary ?: '').toString().trim()
      if (!line) {
        continue
      }
      sb.append(i).append('. **').append(tool).append(':** ').append(line).append('\n')
      Map facts = art.facts instanceof Map ? (Map) art.facts : [:]
      if (facts.topSearchTitle) {
        sb.append('   - headline: `').append(facts.topSearchTitle).append('`\n')
      }
      if (facts.topSearchSnippet) {
        sb.append('   - snippet: ').append(facts.topSearchSnippet).append('\n')
      }
      if (facts.topSearchUrl) {
        sb.append('   - source: `').append(facts.topSearchUrl).append('`\n')
      }
      if (facts.pageTitle) {
        sb.append('   - pageTitle: `').append(facts.pageTitle).append('`\n')
      }
      if (facts.fetchUrl) {
        sb.append('   - fetched: `').append(facts.fetchUrl).append('`\n')
      }
      if (facts.repoPath) {
        sb.append('   - path: `').append(facts.repoPath).append('`\n')
      }
      if (facts.title_t) {
        sb.append('   - title_t: `').append(facts.title_t).append('`\n')
      }
      if (art.salientFact) {
        sb.append('   - salientFact: **').append(art.salientFact).append('**\n')
      }
      if (Boolean.TRUE.equals(facts.shallowUrl) || Boolean.TRUE.equals(facts.weakTitle)) {
        sb.append('   - note: shallow or weak fetch — not verified for writes\n')
      }
      if (facts.pageCopyHint) {
        sb.append('   - page copy hint: ').append(facts.pageCopyHint).append('\n')
      }
      if (facts.generateImagePrompt) {
        sb.append('   - image prompt used: ').append(facts.generateImagePrompt).append('\n')
      }
      i++
    }
    sb.append(
      '\n**Chaining:** later tools must use the facts above in their arguments when this turn\'s plan depends on them.\n'
    )
    sb.append(
      '**Search:** prefer titles/snippets from search tools — avoid **FetchHttpUrl** on every result URL unless the author named a specific page to read.\n'
    )
    return sb.toString()
  }

  /**
   * Author-visible card for new artifacts since the last step-bridge emission (chat UI only).
   */
  static String formatAuthorBridgeCard(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return ''
    }
    List<Map> list = artifactsList(toolsLoopSessionBundle)
    int emitted = 0
    Object prev = toolsLoopSessionBundle.get('toolsLoopStepBridgeArtifactEmitted')
    if (prev instanceof Number) {
      emitted = ((Number) prev).intValue()
    }
    if (list.size() <= emitted) {
      return ''
    }
    StringBuilder sb = new StringBuilder()
    sb.append('## Carrying forward\n\n')
    sb.append('These results feed the **next** step in your request:\n\n')
    int n = 1
    for (int i = emitted; i < list.size(); i++) {
      Map art = list.get(i)
      String tool = (art.tool ?: '').toString()
      String line = (art.summary ?: '').toString().trim()
      if (!line) {
        continue
      }
      sb.append(n++).append('. **').append(tool).append('** — ').append(line).append('\n')
      Map facts = art.facts instanceof Map ? (Map) art.facts : [:]
      if (facts.topSearchTitle) {
        sb.append('   - headline: `').append(facts.topSearchTitle).append('`\n')
      }
      if (facts.pageTitle) {
        sb.append('   - pageTitle: `').append(facts.pageTitle).append('`\n')
      }
      if (facts.topSearchUrl) {
        sb.append('   - source: `').append(facts.topSearchUrl).append('`\n')
      }
      if (facts.fetchUrl) {
        sb.append('   - fetched: `').append(facts.fetchUrl).append('`\n')
      }
      if (facts.repoPath) {
        sb.append('   - path: `').append(facts.repoPath).append('`\n')
      }
      if (facts.title_t) {
        sb.append('   - title: `').append(facts.title_t).append('`\n')
      }
      sb.append('\n')
    }
    if (n == 1) {
      return ''
    }
    toolsLoopSessionBundle.put('toolsLoopStepBridgeArtifactEmitted', list.size())
    return sb.toString().trim()
  }

  /** Read-only view of artifacts recorded this turn (for author-visible step-bridge cards). */
  static List<Map> allArtifacts(Map toolsLoopSessionBundle) {
    return new ArrayList<>(artifactsList(toolsLoopSessionBundle))
  }

  private static List<Map> artifactsList(Map toolsLoopSessionBundle) {
    if (!(toolsLoopSessionBundle instanceof Map)) {
      return new ArrayList<>()
    }
    Object existing = toolsLoopSessionBundle.get(BUNDLE_KEY)
    if (existing instanceof List) {
      return (List<Map>) existing
    }
    List<Map> list = new ArrayList<>()
    toolsLoopSessionBundle.put(BUNDLE_KEY, list)
    return list
  }

  private static Map buildArtifact(String wireName, Map toolOut, String toolInputJson, JsonSlurper parser) {
    Map art = new LinkedHashMap<>()
    art.tool = wireName
    Map facts = new LinkedHashMap<>()
    String summary = ''

    if ('WebSearch'.equals(wireName) || 'SerpApiWebSearch'.equals(wireName)) {
      Map top = firstSearchResult(toolOut)
      if (top) {
        String title = cap((top.title ?: '').toString().trim(), MAX_FIELD_CHARS)
        String snippet = cap((top.snippet ?: '').toString().trim(), MAX_SNIPPET_CHARS)
        String url = cap((top.url ?: '').toString().trim(), MAX_FIELD_CHARS)
        if (title) {
          facts.topSearchTitle = title
          facts.topSearchSnippet = snippet
          facts.topSearchUrl = url
          summary = 'Search returned candidates — pick an article URL, call **FetchHttpUrl**, read the body, then use those facts in writes.'
          art.salientFact = title
        }
      }
    } else if ('GetContent'.equals(wireName)) {
      String path = (toolOut.path ?: toolOut.contentPath ?: '').toString().trim()
      String xml = (toolOut.contentXml ?: '').toString()
      if (path) {
        facts.repoPath = path
      }
      String titleT = xmlFieldText(xml, 'title_t')
      if (titleT) {
        facts.title_t = cap(titleT, MAX_FIELD_CHARS)
      }
      summary = path ? ('Loaded `' + path + '`') : 'Loaded content item'
      if (titleT) {
        summary += ' (current title_t: "' + cap(titleT, 120) + '")'
      }
    } else if ('update_content'.equals(wireName)) {
      String path = (toolOut.path ?: toolOut.contentPath ?: '').toString().trim()
      String xml = (toolOut.contentXml ?: '').toString()
      if (path) {
        facts.repoPath = path
      }
      if (xml?.trim()) {
        facts.pageCopyHint = cap(extractPlainTextHints(xml), MAX_SNIPPET_CHARS)
      }
      summary = path ? ('Prepared update for `' + path + '`') : 'Prepared content update'
      String hint = facts.pageCopyHint?.toString()?.trim()
      if (hint) {
        int colon = hint.indexOf(':')
        String body = colon >= 0 ? hint.substring(colon + 1).trim() : hint
        if (body.length() >= 12) {
          art.salientFact = cap(body, MAX_FIELD_CHARS)
        }
      }
    } else if ('WriteContent'.equals(wireName)) {
      String path = (toolOut.path ?: toolOut.contentPath ?: '').toString().trim()
      if (path) {
        facts.repoPath = path
      }
      summary = path ? ('Wrote `' + path + '`') : 'WriteContent succeeded'
    } else if ('GenerateImage'.equals(wireName)) {
      String prompt = generateImagePromptFrom(toolOut, toolInputJson, parser)
      if (prompt) {
        facts.generateImagePrompt = cap(prompt, MAX_SNIPPET_CHARS)
        summary = 'Generated image (prompt: "' + cap(prompt, 120) + '")'
      }
    } else if ('FetchHttpUrl'.equals(wireName)) {
      String url = ''
      if (toolInputJson?.trim()) {
        try {
          Object args = parser.parseText(toolInputJson.toString())
          if (args instanceof Map) {
            url = (args.url ?: '').toString().trim()
          }
        } catch (Throwable ignored) {
        }
      }
      if (!url) {
        url = (toolOut.url ?: '').toString().trim()
      }
      String body = (toolOut.body ?: '').toString()
      Map extracted = AuthoringFetchedPageFacts.extract(body, url)
      String primary = AuthoringFetchedPageFacts.extractPrimaryFact(body, url)
      if (url) {
        facts.fetchUrl = cap(url, MAX_FIELD_CHARS)
      }
      if (extracted.pageTitle) {
        facts.pageTitle = cap((extracted.pageTitle ?: '').toString(), MAX_FIELD_CHARS)
      }
      if (Boolean.TRUE.equals(extracted.shallowUrl)) {
        facts.shallowUrl = Boolean.TRUE
      }
      if (Boolean.TRUE.equals(extracted.weakTitle)) {
        facts.weakTitle = Boolean.TRUE
      }
      if (primary) {
        art.salientFact = cap(primary, MAX_FIELD_CHARS)
        art.usableExternalFact = Boolean.TRUE
        summary = 'Fetched: **' + cap(primary, 120) + '**'
      } else if (Boolean.TRUE.equals(facts.shallowUrl) || Boolean.TRUE.equals(facts.weakTitle)) {
        summary = 'Fetched shallow URL — no specific fact extracted; fetch a deeper URL before writes.'
      } else {
        summary = url ? ('Fetched `' + cap(url, 100) + '`') : 'Fetched URL body'
      }
    }

    if (!summary?.trim()) {
      return [:]
    }
    art.summary = summary
    art.facts = facts
    return art
  }

  private static Map firstSearchResult(Map toolOut) {
    List results = toolOut.results instanceof List ? (List) toolOut.results : []
    if (results.isEmpty()) {
      return null
    }
    Object top = results[0]
    return top instanceof Map ? (Map) top : null
  }

  private static String generateImagePromptFrom(Map toolOut, String toolInputJson, JsonSlurper parser) {
    String revised = (toolOut.revised_prompt ?: '').toString().trim()
    if (revised) {
      return revised
    }
    if (!toolInputJson?.trim()) {
      return ''
    }
    try {
      Object args = parser.parseText(toolInputJson.toString())
      if (args instanceof Map) {
        return (args.prompt ?: args.description ?: '').toString().trim()
      }
    } catch (Throwable ignored) {
    }
    return ''
  }

  private static String xmlFieldText(String xml, String fieldId) {
    if (!xml?.trim() || !fieldId) {
      return ''
    }
    Matcher cdata = (xml =~ /(?is)<${Pattern.quote(fieldId)}>\s*<!\[CDATA\[([\s\S]*?)\]\]>\s*<\/${Pattern.quote(fieldId)}>/)
    if (cdata.find()) {
      return stripHtml(cdata.group(1))
    }
    Matcher plain = (xml =~ /(?is)<${Pattern.quote(fieldId)}>([^<]*)<\/${Pattern.quote(fieldId)}>/)
    if (plain.find()) {
      return plain.group(1)?.trim() ?: ''
    }
    return ''
  }

  private static String extractPlainTextHints(String xml) {
    if (!xml?.trim()) {
      return ''
    }
    List<String> parts = []
    for (String fieldId : ['hero_title_html', 'hero_text_html', 'title_t', 'body_html', 'description_html']) {
      String t = xmlFieldText(xml, fieldId)
      if (t?.trim()) {
        parts.add(fieldId + ': ' + cap(stripHtml(t), 200))
      }
      if (parts.size() >= 3) {
        break
      }
    }
    return parts.join('; ')
  }

  private static String stripHtml(String html) {
    if (!html) {
      return ''
    }
    return html.replaceAll(/(?is)<[^>]+>/, ' ').replaceAll(/\s+/, ' ').trim()
  }

  private static String cap(String s, int max) {
    String t = (s ?: '').toString().trim()
    if (!t || t.length() <= max) {
      return t
    }
    return t.substring(0, max) + '…'
  }
}
