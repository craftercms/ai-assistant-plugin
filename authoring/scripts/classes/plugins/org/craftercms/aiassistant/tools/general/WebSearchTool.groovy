package plugins.org.craftercms.aiassistant.tools.general

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.http.OutboundHttpPolicy
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Open-web search via DuckDuckGo HTML (no API keys). Studio must reach the public internet.
 */
class WebSearchTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(WebSearchTool)
  private static final int DEFAULT_MAX_RESULTS = 8
  private static final String USER_AGENT =
    'Mozilla/5.0 (compatible; CrafterCMS-AI-Assistant/1.0; +https://craftercms.org)'

  @Override
  String wireName() { 'WebSearch' }

  @Override
  String description() { ToolPrompts.getDESC_WEB_SEARCH() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.WEB_SEARCH }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    String query = parseQuery(input)
    int maxResults = maxResults(parseMaxResults(input))
    List<Map> results = duckDuckGoResults(query, maxResults)
    if (results.isEmpty()) {
      return [
        ok         : false,
        tool       : wireName(),
        query      : query,
        message    : 'Web search returned no results (the search service may be unreachable or blocked from Studio).',
        resultCount: 0,
        results    : []
      ]
    }
    return [
      ok         : true,
      tool       : wireName(),
      query      : query,
      resultCount: results.size(),
      results    : results
    ]
  }

  private static String parseQuery(Map input) {
    String query = input?.query?.toString()?.trim()
    if (!query) {
      query = input?.q?.toString()?.trim()
    }
    if (!query) {
      throw new IllegalArgumentException('Missing required field: query')
    }
    return query
  }

  private static Integer parseMaxResults(Map input) {
    if (input?.maxResults == null) {
      return null
    }
    try {
      return (input.maxResults instanceof Number) ?
        ((Number) input.maxResults).intValue() :
        Integer.parseInt(input.maxResults.toString().trim())
    } catch (Throwable ignored) {
      return null
    }
  }

  private static int maxResults(Integer toolRequested) {
    int r = (toolRequested != null) ? toolRequested.intValue() : DEFAULT_MAX_RESULTS
    return Math.min(15, Math.max(1, r))
  }

  private List<Map> duckDuckGoResults(String q, int maxResults) {
    List<Map> fromLite = duckDuckGoPost('https://lite.duckduckgo.com/lite/', q, maxResults, true)
    if (!fromLite.isEmpty()) {
      return fromLite
    }
    return duckDuckGoPost('https://html.duckduckgo.com/html/', q, maxResults, false)
  }

  private List<Map> duckDuckGoPost(String endpoint, String q, int maxResults, boolean liteParser) {
    URI uri = new URI(endpoint)
    String hopErr = OutboundHttpPolicy.validateUrl(uri.toString())
    if (hopErr) {
      log.warn('WebSearch DuckDuckGo blocked by SSRF policy: {}', hopErr)
      return []
    }
    String body = 'q=' + URLEncoder.encode(q, StandardCharsets.UTF_8.name())
    HttpURLConnection conn = null
    InputStream is = null
    try {
      conn = (HttpURLConnection) uri.toURL().openConnection()
      conn.setRequestMethod('POST')
      conn.setDoOutput(true)
      conn.setInstanceFollowRedirects(true)
      conn.setConnectTimeout(15000)
      conn.setReadTimeout(60_000)
      conn.setRequestProperty('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8')
      conn.setRequestProperty('Accept', 'text/html,application/xhtml+xml')
      conn.setRequestProperty('User-Agent', USER_AGENT)
      conn.outputStream.withWriter(StandardCharsets.UTF_8.name()) { it.write(body) }
      int status = conn.responseCode
      if (status < 200 || status >= 300) {
        log.warn('WebSearch DuckDuckGo HTTP {} endpoint={}', status, endpoint)
        return []
      }
      String html = ''
      is = conn.inputStream
      if (is != null) {
        html = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)).text
      }
      return liteParser ?
        parseDuckDuckGoLiteHtml(html, maxResults) :
        parseDuckDuckGoHtml(html, maxResults)
    } catch (Throwable t) {
      log.warn('WebSearch DuckDuckGo failed endpoint={}: {}', endpoint, t.message)
      return []
    } finally {
      try {
        is?.close()
      } catch (Throwable ignored) {
      }
      try {
        conn?.disconnect()
      } catch (Throwable ignored) {
      }
    }
  }

  private static List<Map> parseDuckDuckGoLiteHtml(String html, int maxResults) {
    List<Map> results = []
    if (!html?.trim() || maxResults < 1) {
      return results
    }
    java.util.regex.Pattern linkPat =
      java.util.regex.Pattern.compile("(?is)<a[^>]*class=['\"]result-link['\"][^>]*href=['\"]([^'\"]+)['\"][^>]*>(.*?)</a>")
    java.util.regex.Pattern snippetPat =
      java.util.regex.Pattern.compile("(?is)<td[^>]*class=['\"]result-snippet['\"][^>]*>(.*?)</td>")
    def linkMatcher = linkPat.matcher(html)
    def snippetMatcher = snippetPat.matcher(html)
    while (linkMatcher.find() && results.size() < maxResults) {
      String url = WebSearchResultTextUtil.decodeHtmlEntities(linkMatcher.group(1)?.trim())
      String title = WebSearchResultTextUtil.stripHtml(linkMatcher.group(2))
      String snippet = ''
      if (snippetMatcher.find()) {
        snippet = WebSearchResultTextUtil.stripHtml(snippetMatcher.group(1))
      }
      if (!url || WebSearchResultTextUtil.skipResultUrl(url)) {
        continue
      }
      results.add([title: title ?: url, url: url, snippet: snippet])
    }
    results
  }

  private static List<Map> parseDuckDuckGoHtml(String html, int maxResults) {
    List<Map> results = []
    if (!html?.trim() || maxResults < 1) {
      return results
    }
    java.util.regex.Pattern linkPat =
      java.util.regex.Pattern.compile("(?is)<a[^>]*class=['\"]result__a['\"][^>]*href=['\"]([^'\"]+)['\"][^>]*>(.*?)</a>")
    java.util.regex.Pattern snippetPat =
      java.util.regex.Pattern.compile("(?is)<a[^>]*class=['\"]result__snippet['\"][^>]*>(.*?)</a>")
    def linkMatcher = linkPat.matcher(html)
    def snippetMatcher = snippetPat.matcher(html)
    while (linkMatcher.find() && results.size() < maxResults) {
      String url = WebSearchResultTextUtil.decodeHtmlEntities(linkMatcher.group(1)?.trim())
      String title = WebSearchResultTextUtil.stripHtml(linkMatcher.group(2))
      String snippet = snippetMatcher.find() ? WebSearchResultTextUtil.stripHtml(snippetMatcher.group(1)) : ''
      if (!url || WebSearchResultTextUtil.skipResultUrl(url)) {
        continue
      }
      results.add([title: title ?: url, url: url, snippet: snippet])
    }
    results
  }
}
