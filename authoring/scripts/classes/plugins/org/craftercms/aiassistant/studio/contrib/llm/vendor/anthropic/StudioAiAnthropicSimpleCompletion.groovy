package plugins.org.craftercms.aiassistant.studio.contrib.llm.vendor.anthropic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import plugins.org.craftercms.aiassistant.studio.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxHttp

import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Non-streaming Anthropic Messages API completions for auxiliary LLM calls (recipe refine, translate inner loops, etc.)
 * that must not use the OpenAI {@code /v1/chat/completions} wire.
 */
final class StudioAiAnthropicSimpleCompletion {

  private static final Logger log = LoggerFactory.getLogger(StudioAiAnthropicSimpleCompletion)

  private static final String ANTHROPIC_VERSION = '2023-06-01'
  private static final String DEFAULT_MESSAGES_URL = 'https://api.anthropic.com/v1/messages'
  private static final Pattern TRY_AGAIN_SECONDS = Pattern.compile('try again in (\\d+(?:\\.\\d+)?)\\s*s', Pattern.CASE_INSENSITIVE)

  private StudioAiAnthropicSimpleCompletion() {}

  /**
   * POST {@code /v1/messages} and return concatenated text blocks from the assistant message.
   */
  static String assistantText(
    String apiKey,
    String model,
    String systemText,
    String userText,
    int maxOutTokens,
    int readTimeoutMs,
    String workerPhasePrefix = null
  ) {
    String key = (apiKey ?: '').trim()
    String mdl = (model ?: '').trim()
    if (!key || !mdl) {
      throw new IllegalStateException('Anthropic simple completion: missing apiKey or model')
    }
    int maxTokens = Math.max(256, Math.min(maxOutTokens > 0 ? maxOutTokens : 4096, 65536))
    Map reqMap = [
      model     : mdl,
      max_tokens: maxTokens,
      messages  : [[role: 'user', content: (userText ?: '').toString()]]
    ]
    String sys = (systemText ?: '').trim()
    if (sys) {
      reqMap.system = sys
    }
    String jsonBody = JsonOutput.toJson(reqMap)
    String phase = (workerPhasePrefix ?: 'AnthropicSimple').toString().trim()
    int readTimeoutEffective = (int) Math.max(60_000, readTimeoutMs as int)
    int maxTries = 3
    for (int attempt = 1; attempt <= maxTries; attempt++) {
      def ex = StudioAiSandboxHttp.postBytes(
        URI.create(DEFAULT_MESSAGES_URL),
        jsonBody.getBytes(StandardCharsets.UTF_8),
        MediaType.APPLICATION_JSON_VALUE,
        [
          headers         : [
            'x-api-key'        : key,
            'anthropic-version': ANTHROPIC_VERSION
          ],
          connectTimeoutMs: 30_000,
          readTimeoutMs   : readTimeoutEffective,
          maxRedirects    : 0,
          ssrfCheck       : false
        ]
      )
      if (ex.errorMessage && !ex.bodyText) {
        throw new IllegalStateException("Anthropic simple completion I/O: ${ex.errorMessage}")
      }
      int code = ex.statusCode
      String raw = ex.bodyText ?: ''
      if (code >= 200 && code < 300) {
        if (!raw.trim()) {
          throw new IllegalStateException('Anthropic simple completion: empty response body')
        }
        return extractAssistantText(raw, phase)
      }
      if (StudioAiAnthropicClientConfig.isRetryableStatusCode(code) && attempt < maxTries) {
        long ms = backoffMs(attempt, raw, ex.responseHeaders)
        log.warn(
          'Anthropic simple completion HTTP {} phase={}; backing off {} ms then retry {}/{}',
          code,
          phase,
          ms,
          attempt + 1,
          maxTries
        )
        Thread.sleep(ms)
        continue
      }
      log.error('Anthropic simple completion HTTP {} phase={} body=\n{}', code, phase, AiHttpProxy.elideForLog(raw, 4000))
      throw new IllegalStateException(
        "Anthropic simple completion HTTP ${code}: ${AiHttpProxy.elideForLog(raw, 800)}"
      )
    }
    throw new IllegalStateException('Anthropic simple completion: exhausted retries')
  }

  private static String extractAssistantText(String raw, String phase) {
    Object parsed = new JsonSlurper().parseText(raw)
    if (!(parsed instanceof Map)) {
      throw new IllegalStateException('Anthropic simple completion: expected JSON object')
    }
    Map root = (Map) parsed
    def err = root.get('error')
    if (err instanceof Map && err.message) {
      throw new IllegalStateException('Anthropic simple completion: ' + err.message.toString())
    }
    def content = root.get('content')
    if (!(content instanceof List) || content.isEmpty()) {
      throw new IllegalStateException('Anthropic simple completion: missing content blocks')
    }
    StringBuilder sb = new StringBuilder()
    content.each { block ->
      if (block instanceof Map && 'text'.equals(block.get('type')?.toString()) && block.text != null) {
        sb.append(block.text.toString())
      }
    }
    String text = sb.toString()
    if (!text.trim()) {
      throw new IllegalStateException('Anthropic simple completion: no text in content blocks')
    }
    log.debug('Anthropic simple completion ok phase={} assistantChars={}', phase, text.length())
    return text
  }

  private static long backoffMs(int zeroBasedAttempt, String body, HttpHeaders headers) {
    if (headers != null) {
      String ra = headers.getFirst('Retry-After') ?: headers.getFirst('retry-after')
      if (ra?.trim()?.isInteger()) {
        return Math.min(120_000L, Math.max(1000L, (ra.trim() as Integer) * 1000L))
      }
    }
    Matcher m = TRY_AGAIN_SECONDS.matcher((body ?: '').toString())
    if (m.find()) {
      try {
        double sec = Double.parseDouble(m.group(1))
        return Math.min(120_000L, Math.max(1000L, (long) (sec * 1000d)))
      } catch (Throwable ignored) {
      }
    }
    long base = 2000L * (1L << Math.min(4, Math.max(0, zeroBasedAttempt)))
    return Math.min(90_000L, base)
  }
}
