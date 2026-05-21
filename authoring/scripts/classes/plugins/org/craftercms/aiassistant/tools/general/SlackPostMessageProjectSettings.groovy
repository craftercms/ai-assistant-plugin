package plugins.org.craftercms.aiassistant.tools.general

import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.secrets.StudioAiAssistantSecretsService
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext

/**
 * Site {@code tools.json} block for {@link SlackPostMessageTool} ({@code builtInToolSettings.SlackPostMessage}).
 * Bot token is configured on Project Tools → Secrets ({@link #SECRET_KEY}), not in this block.
 */
final class SlackPostMessageProjectSettings {

  static final String WIRE = 'SlackPostMessage'

  /** Built-in Secrets catalog key (value on Project Tools → Secrets only). */
  static final String SECRET_KEY = 'slack_bot_token'

  private SlackPostMessageProjectSettings() {}

  static String secretKeyId(Map cfg) {
    Object raw = StudioAiAssistantProjectConfig.builtInToolSettingsForWire(cfg, WIRE).get('secretKey')
    String custom = raw?.toString()?.trim()
    return custom ?: SECRET_KEY
  }

  static Map defaultDefaults() {
    return Collections.unmodifiableMap([:])
  }

  /** Merges {@code builtInToolSettings.SlackPostMessage.defaults} over {@link #defaultDefaults()}. */
  static Map resolveDefaults(Map cfg) {
    Object raw = StudioAiAssistantProjectConfig.builtInToolSettingsForWire(cfg, WIRE).get('defaults')
    Map base = defaultDefaults()
    if (!(raw instanceof Map) || ((Map) raw).isEmpty()) {
      return base
    }
    Map merged = new LinkedHashMap<>(base)
    for (Map.Entry e : ((Map) raw).entrySet()) {
      if (e.value != null && e.value.toString().trim()) {
        merged.put(e.key.toString(), e.value)
      }
    }
    return merged
  }

  static String missingTokenMessage(StudioAiToolContext ctx, Map cfg, String secretKeyUsed) {
    if (ctx?.ops == null) {
      return 'Slack is not set up for this site. Add a Bot User OAuth token under Project Tools → Secrets (Slack), then try again.'
    }
    String key = (secretKeyUsed ?: '').trim() ?: secretKeyId(cfg ?: [:])
    Map status = StudioAiAssistantSecretsService.secretResolutionStatus(ctx.ops, key)
    if (!Boolean.TRUE.equals(status.configured)) {
      return 'Slack is not set up for this site. Add a Bot User OAuth token (chat:write) under Project Tools → Secrets (Slack), then try again.'
    }
    String kind = status.storedKind?.toString() ?: ''
    if ('env' == kind) {
      String envVar = (status.envVar ?: '').toString().trim()
      if (!envVar) {
        return 'Slack is set to use a server environment variable, but the variable name is missing in Secrets. ' +
          'Open Project Tools → Secrets and fix the Slack entry.'
      }
      return "Slack is set to use server environment variable ${envVar}, but it is not set on this Studio host. " +
        'Add the bot token under Project Tools → Secrets (Slack), or set that variable on the server.'
    }
    if ('enc' == kind) {
      if (Boolean.TRUE.equals(status.unresolvedMacro)) {
        return 'Slack is saved in Secrets as an encrypted value but could not be decrypted on this Studio host. ' +
          'Open Project Tools → Secrets, re-save the Slack bot token, or verify Studio encryption matches this environment.'
      }
      return 'Slack is listed in Secrets but did not resolve to a bot token. Open Project Tools → Secrets and check the Slack entry.'
    }
    if ('secret_ref' == kind) {
      return 'Slack Secrets entry references another secret that could not be resolved. Check Project Tools → Secrets.'
    }
    return 'Slack is listed in Secrets but did not resolve to a bot token. Open Project Tools → Secrets and check the Slack entry.'
  }
}
