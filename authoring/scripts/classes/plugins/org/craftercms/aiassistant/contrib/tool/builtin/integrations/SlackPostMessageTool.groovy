package plugins.org.craftercms.aiassistant.contrib.tool.builtin.integrations

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.secrets.StudioAiAssistantSecretsService
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.http.OutboundHttpPolicy
import plugins.org.craftercms.aiassistant.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSchemas

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

/**
 * Posts to Slack {@code chat.postMessage} ({@link https://docs.slack.dev/reference/methods/chat.postMessage/}).
 * <p>Bot token from site {@code secrets.json} ({@link SlackPostMessageProjectSettings#SECRET_KEY}).
 * Site {@code tools.json} may set {@code defaultChannel} and optional {@code secretKey}.</p>
 * <p>Supports recipe-engine <strong>confirmation</strong> steps ({@link #recipeEngineConfirmationStep()}):
 * recipes must pass {@code text} (or {@code message}) on each step, usually via {@code $refineBinding.key}
 * after an {@code llmRefine} step with {@code outputFormat: "json"}. {@link #applyRecipeConfirmationArgDefaults}
 * applies generic mrkdwn conversion ({@link SlackConfirmationPostFormatter}) when {@code mrkdwn} is not false.</p>
 */
class SlackPostMessageTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(SlackPostMessageTool)
  private static final String SLACK_POST_URL = 'https://slack.com/api/chat.postMessage'
  private static final String SLACK_LIST_URL = 'https://slack.com/api/conversations.list'
  private static final Pattern SLACK_CHANNEL_ID = Pattern.compile('^C[A-Z0-9]{8,}$')
  private static final int MAX_TEXT_CHARS = 40_000
  private static final int MAX_RESPONSE_CHARS = 32_000
  private static final String USER_AGENT =
    'CrafterCMS-AI-Assistant/1.0 (+https://craftercms.org)'

  @Override
  String wireName() { SlackPostMessageProjectSettings.WIRE }

  /** {@inheritDoc} */
  @Override
  boolean recipeEngineConfirmationStep() { true }

  /**
   * {@inheritDoc}
   * <p>When {@code text} / {@code message} are set, applies generic mrkdwn conversion and defaults {@code mrkdwn=true}.</p>
   */
  @Override
  Map applyRecipeConfirmationArgDefaults(Map resolvedArgs, String lastAssistantMarkdown) {
    Map args = resolvedArgs instanceof Map ? new LinkedHashMap<>(resolvedArgs) : [:]
    String existing = (args.get('text') ?: args.get('message') ?: '').toString().trim()
    if (!existing) {
      return args
    }
    String text = SlackConfirmationPostFormatter.formatAssistantProseForSlack(existing)
    if (text) {
      args.put('text', text)
      if (!args.containsKey('mrkdwn')) {
        args.put('mrkdwn', true)
      }
    }
    return args
  }

  @Override
  String description() { ToolPrompts.getDESC_SLACK_POST_MESSAGE() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.SLACK_POST_MESSAGE }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    Map cfg = ctx?.aiProjectToolCfg instanceof Map ? (Map) ctx.aiProjectToolCfg : [:]
    Map siteDefaults = SlackPostMessageProjectSettings.resolveDefaults(cfg)
    def tokenResolved = resolveBotTokenWithKey(input, cfg, ctx)
    String token = tokenResolved.token
    if (!token) {
      return [
        ok     : false,
        tool   : wireName(),
        message: SlackPostMessageProjectSettings.missingTokenMessage(ctx, cfg, tokenResolved.secretKey)
      ]
    }
    String channel = resolveChannel(input, siteDefaults)
    if (!channel) {
      throw new IllegalArgumentException('Missing required field: channel (Slack channel ID or name, e.g. C01234567 or #general)')
    }
    String apiChannel = resolveChannelForPost(token, channel)
    Map body = buildSlackBody(input, siteDefaults, apiChannel)
    if (!body.text && !body.blocks) {
      log.error('SlackPostMessage: missing text/blocks — confirmation steps must set text (e.g. $slackOutbound.root)')
      return [
        ok     : false,
        tool   : wireName(),
        message: 'Provide text and/or blocks for the Slack message (recipe confirmation args)',
        skipped: true
      ]
    }
    Map postResult = postToSlack(token, body)
    if (!Boolean.TRUE.equals(postResult?.ok)) {
      log.error(
        'SlackPostMessage: post failed channel={} message={}',
        body.channel,
        postResult?.message ?: '(none)'
      )
    }
    return postResult
  }

  /** Resolves Slack bot token from call args, tool settings, or {@link SlackPostMessageProjectSettings#SECRET_KEY}. */
  private static Map resolveBotTokenWithKey(Map input, Map cfg, StudioAiToolContext ctx) {
    String secretKey = SlackPostMessageProjectSettings.secretKeyId(cfg)
    String fromCall = input?.secretKey?.toString()?.trim()
    if (fromCall) {
      secretKey = fromCall
    }
    if (ctx?.ops == null) {
      return [token: '', secretKey: secretKey]
    }
    String resolved = StudioAiAssistantSecretsService.resolveSecretKey(ctx.ops, secretKey)
    String trimmed = (resolved ?: '').trim()
    if (trimmed && !trimmed.contains('${')) {
      return [token: trimmed, secretKey: secretKey]
    }
    return [token: '', secretKey: secretKey]
  }

  /**
   * Maps a channel name ({@code #random}, {@code random}) to a {@code C…} id via {@code conversations.list}
   * when the bot can see that channel (must be invited to private channels).
   */
  private String resolveChannelForPost(String token, String channel) {
    String ch = (channel ?: '').trim()
    if (!ch || SLACK_CHANNEL_ID.matcher(ch).matches()) {
      return ch
    }
    String name = ch.startsWith('#') ? ch.substring(1) : ch
    if (!name) {
      return ch
    }
    String id = lookupChannelIdByName(token, name)
    return id ?: ch
  }

  /**
   * Lookup channel id by name.
   * @param token Caller-supplied input.
   * @param channelName Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private String lookupChannelIdByName(String token, String channelName) {
    String want = (channelName ?: '').trim().toLowerCase(Locale.ROOT)
    if (!want || !token?.trim()) {
      return ''
    }
    String cursor = ''
    int pages = 0
    while (pages < 8) {
      pages++
      String qs =
        'types=' + URLEncoder.encode('public_channel,private_channel', 'UTF-8') +
          '&limit=200' +
          (cursor ? '&cursor=' + URLEncoder.encode(cursor, 'UTF-8') : '')
      Map resp = slackApiGet(token, SLACK_LIST_URL + '?' + qs)
      if (!Boolean.TRUE.equals(resp?.get('ok'))) {
        log.warn(
          'SlackPostMessage conversations.list failed: {}',
          resp?.get('error') ?: resp?.get('message')
        )
        return ''
      }
      Object channels = resp.get('channels')
      if (channels instanceof List) {
        for (Object row : (List) channels) {
          if (!(row instanceof Map)) {
            continue
          }
          String name = ((Map) row).get('name')?.toString()?.trim()?.toLowerCase(Locale.ROOT)
          if (want == name) {
            return ((Map) row).get('id')?.toString()?.trim() ?: ''
          }
        }
      }
      Map meta = resp.get('response_metadata') instanceof Map ? (Map) resp.get('response_metadata') : [:]
      String next = meta.get('next_cursor')?.toString()?.trim()
      if (!next) {
        break
      }
      cursor = next
    }
    return ''
  }

  /**
   * Slack api get.
   * @param token Caller-supplied input.
   * @param url Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  private Map slackApiGet(String token, String url) {
    String hopErr = OutboundHttpPolicy.validateUrl(url)
    if (hopErr) {
      return [ok: false, message: hopErr]
    }
    HttpURLConnection conn = null
    InputStream inStream = null
    try {
      conn = (HttpURLConnection) new URI(url).toURL().openConnection()
      conn.setRequestMethod('GET')
      conn.setConnectTimeout(15000)
      conn.setReadTimeout(60_000)
      conn.setRequestProperty('Authorization', "Bearer ${token}")
      conn.setRequestProperty('Accept', 'application/json')
      conn.setRequestProperty('User-Agent', USER_AGENT)
      int status = conn.responseCode
      inStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream()
      String responseText = readUtf8(inStream, MAX_RESPONSE_CHARS)
      Object parsed = new JsonSlurper().parseText(responseText ?: '{}')
      if (parsed instanceof Map) {
        return (Map) parsed
      }
      return [ok: false, message: 'Unexpected Slack API response']
    } catch (Throwable t) {
      log.warn('SlackPostMessage Slack API GET failed: {}', t.message)
      return [ok: false, message: t.message ?: t.toString()]
    } finally {
      if (inStream != null) {
        try {
          inStream.close()
        } catch (Throwable ignored) {
        }
      }
      if (conn != null) {
        conn.disconnect()
      }
    }
  }

  /**
   * Resolves channel from request and plugin context.
   * @param input Caller-supplied input.
   * @param siteDefaults Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String resolveChannel(Map input, Map siteDefaults) {
    String ch = input?.channel?.toString()?.trim()
    if (!ch && input?.channelId != null) {
      ch = input.channelId.toString().trim()
    }
    if (!ch && siteDefaults?.defaultChannel != null) {
      ch = siteDefaults.defaultChannel.toString().trim()
    }
    return ch ?: ''
  }

  /** Builds {@code chat.postMessage} JSON body from call args and site defaults. */
  private static Map buildSlackBody(Map input, Map siteDefaults, String channel) {
    Map body = new LinkedHashMap()
    body.channel = channel
    String text = input?.text?.toString()?.trim()
    if (!text && input?.message != null) {
      text = input.message.toString().trim()
    }
    if (text) {
      if (text.length() > MAX_TEXT_CHARS) {
        text = text.substring(0, MAX_TEXT_CHARS)
      }
      body.text = text
    }
    Object blocks = input?.blocks
    if (blocks != null) {
      body.blocks = parseJsonArrayOrValue(blocks, 'blocks')
    }
    Object attachments = input?.attachments
    if (attachments != null) {
      body.attachments = parseJsonArrayOrValue(attachments, 'attachments')
    }
    Object metadata = input?.metadata
    if (metadata != null) {
      body.metadata = metadata instanceof Map ? metadata : parseJsonObjectOrValue(metadata, 'metadata')
    }
    putIfPresent(body, 'thread_ts', firstNonEmpty(input, ['threadTs', 'thread_ts']))
    putIfPresent(body, 'username', firstNonEmpty(input, ['username']))
    putIfPresent(body, 'icon_emoji', firstNonEmpty(input, ['iconEmoji', 'icon_emoji']))
    putIfPresent(body, 'icon_url', firstNonEmpty(input, ['iconUrl', 'icon_url']))
    putIfPresent(body, 'parse', firstNonEmpty(input, ['parse']))
    putBooleanIfPresent(body, 'mrkdwn', input, ['mrkdwn'])
    putBooleanIfPresent(body, 'unfurl_links', input, ['unfurlLinks', 'unfurl_links'])
    putBooleanIfPresent(body, 'unfurl_media', input, ['unfurlMedia', 'unfurl_media'])
    putBooleanIfPresent(body, 'reply_broadcast', input, ['replyBroadcast', 'reply_broadcast'])
    putBooleanIfPresent(body, 'link_names', input, ['linkNames', 'link_names'])
    return body
  }

  /**
   * Put if present.
   * @param body Caller-supplied input.
   * @param slackKey Caller-supplied input.
   * @param value Caller-supplied input.
   */
  private static void putIfPresent(Map body, String slackKey, String value) {
    if (value?.trim()) {
      body.put(slackKey, value.trim())
    }
  }

  /**
   * First non empty.
   * @param input Caller-supplied input.
   * @param keys Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String firstNonEmpty(Map input, List<String> keys) {
    if (!(input instanceof Map)) {
      return null
    }
    for (String k : keys) {
      Object v = input.get(k)
      if (v != null && v.toString().trim()) {
        return v.toString().trim()
      }
    }
    return null
  }

  /**
   * Put boolean if present.
   * @param body Caller-supplied input.
   * @param slackKey Caller-supplied input.
   * @param input Caller-supplied input.
   * @param keys Caller-supplied input.
   */
  private static void putBooleanIfPresent(Map body, String slackKey, Map input, List<String> keys) {
    if (!(input instanceof Map)) {
      return
    }
    for (String k : keys) {
      if (!input.containsKey(k)) {
        continue
      }
      Object v = input.get(k)
      if (v == null) {
        return
      }
      if (v instanceof Boolean) {
        body.put(slackKey, v)
        return
      }
      String s = v.toString().trim().toLowerCase()
      if (s in ['true', '1', 'yes']) {
        body.put(slackKey, true)
      } else if (s in ['false', '0', 'no']) {
        body.put(slackKey, false)
      }
      return
    }
  }

  /**
   * Parse json array or value.
   * @param raw Caller-supplied input.
   * @param fieldName Caller-supplied input.
   * @return Object result.
   */
  private static Object parseJsonArrayOrValue(Object raw, String fieldName) {
    if (raw instanceof List) {
      return raw
    }
    if (raw instanceof Map) {
      return [raw]
    }
    String s = raw?.toString()?.trim()
    if (!s) {
      throw new IllegalArgumentException("${fieldName} must be a JSON array or list")
    }
    Object parsed = new JsonSlurper().parseText(s)
    if (!(parsed instanceof List)) {
      throw new IllegalArgumentException("${fieldName} must be a JSON array")
    }
    return parsed
  }

  /**
   * Parse json object or value.
   * @param raw Caller-supplied input.
   * @param fieldName Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  private static Map parseJsonObjectOrValue(Object raw, String fieldName) {
    if (raw instanceof Map) {
      return (Map) raw
    }
    String s = raw?.toString()?.trim()
    if (!s) {
      throw new IllegalArgumentException("${fieldName} must be a JSON object")
    }
    Object parsed = new JsonSlurper().parseText(s)
    if (!(parsed instanceof Map)) {
      throw new IllegalArgumentException("${fieldName} must be a JSON object")
    }
    return (Map) parsed
  }

  /**
   * Post to slack.
   * @param token Caller-supplied input.
   * @param body Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  private Map postToSlack(String token, Map body) {
    String hopErr = OutboundHttpPolicy.validateUrl(SLACK_POST_URL)
    if (hopErr) {
      return [ok: false, tool: wireName(), message: hopErr]
    }
    String json = JsonOutput.toJson(body)
    HttpURLConnection conn = null
    InputStream inStream = null
    try {
      conn = (HttpURLConnection) new URI(SLACK_POST_URL).toURL().openConnection()
      conn.setRequestMethod('POST')
      conn.setDoOutput(true)
      conn.setConnectTimeout(15000)
      conn.setReadTimeout(60_000)
      conn.setRequestProperty('Authorization', "Bearer ${token}")
      conn.setRequestProperty('Content-Type', 'application/json; charset=utf-8')
      conn.setRequestProperty('Accept', 'application/json')
      conn.setRequestProperty('User-Agent', USER_AGENT)
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8)
      conn.setFixedLengthStreamingMode(bytes.length)
      OutputStream out = conn.getOutputStream()
      out.write(bytes)
      out.flush()
      out.close()
      int status = conn.responseCode
      inStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream()
      String responseText = readUtf8(inStream, MAX_RESPONSE_CHARS)
      Map slack = [:]
      try {
        Object parsed = new JsonSlurper().parseText(responseText ?: '{}')
        if (parsed instanceof Map) {
          slack = (Map) parsed
        }
      } catch (Throwable parseErr) {
        log.warn('SlackPostMessage response parse failed: {}', parseErr.message)
      }
      boolean slackOk = Boolean.TRUE.equals(slack.ok)
      String err = slack.error?.toString()?.trim()
      if (!slackOk || status < 200 || status >= 300) {
        log.error(
          'SlackPostMessage: Slack API error status={} error={}',
          status,
          err ?: '(none)'
        )
      }
      return [
        ok        : slackOk && status >= 200 && status < 300,
        tool      : wireName(),
        statusCode: status,
        channel   : slack.channel,
        ts        : slack.ts,
        message   : slackOk
          ? 'Message posted to Slack.'
          : (err ? "Slack API error: ${err}" : "HTTP ${status} from Slack API."),
        slack     : slack
      ]
    } catch (Throwable t) {
      log.error('SlackPostMessage failed: {}', t.toString())
      return [
        ok     : false,
        tool   : wireName(),
        message: (t.message ?: t.toString())
      ]
    } finally {
      try {
        inStream?.close()
      } catch (Throwable ignored) {
      }
      try {
        conn?.disconnect()
      } catch (Throwable ignored) {
      }
    }
  }

  /**
   * Loads utf8 from configuration or input.
   * @param inStream Caller-supplied input.
   * @param maxChars Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String readUtf8(InputStream inStream, int maxChars) {
    if (inStream == null) {
      return ''
    }
    StringBuilder sb = new StringBuilder(Math.min(maxChars + 16, 8192))
    BufferedReader reader = new BufferedReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))
    char[] cbuf = new char[4096]
    int total = 0
    while (true) {
      int n = reader.read(cbuf)
      if (n < 0) {
        break
      }
      if (total + n <= maxChars) {
        sb.append(cbuf, 0, n)
        total += n
      } else {
        int take = maxChars - total
        if (take > 0) {
          sb.append(cbuf, 0, take)
        }
        break
      }
    }
    return sb.toString()
  }
}
