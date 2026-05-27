package plugins.org.craftercms.aiassistant.engine.context

import plugins.org.craftercms.aiassistant.studio.config.StudioAiSiteModuleText
import plugins.org.craftercms.aiassistant.engine.prompt.ToolPromptsSiteContext

/**
 * Optional per-site markdown appended to every orchestration user prompt (chat / tools).
 * Stored at {@link #STUDIO_MODULE_REL}; distinct from per-key tool prompt overrides under {@code prompts/}.
 */
final class SiteProjectContext {

  /** Studio module path (under {@code config/studio}). */
  static final String STUDIO_MODULE_REL = '/scripts/aiassistant/context/site-authoring.md'

  /**
   * Private constructor; not for direct use.
   */
private SiteProjectContext() {}

  /**
   * Loads utf8 if present from configuration or input.
   * @param applicationContext Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @return Text result, or empty or null when unavailable.
   */
  static String readUtf8IfPresent(Object applicationContext, String siteId) {
    if (applicationContext == null || siteId == null || !siteId.toString().trim()) {
      return null
    }
    String body = StudioAiSiteModuleText.readUtf8IfPresent(applicationContext, siteId.toString().trim(), STUDIO_MODULE_REL)
    return meaningfulBodyOrNull(body)
  }

  /**
   * Loads from current site context from configuration or input.
   * @return Text result, or empty or null when unavailable.
   */
  static String readFromCurrentSiteContext() {
    def ctx = ToolPromptsSiteContext.current()
    if (ctx == null) {
      return null
    }
    return readUtf8IfPresent(ctx.get('applicationContext'), ctx.get('siteId')?.toString())
  }

  /**
   * Meaningful body or null.
   * @param raw Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String meaningfulBodyOrNull(String raw) {
    def t = (raw ?: '').toString().trim()
    return t ? t : null
  }

  /**
   * Context block.
   * @param body Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String contextBlock(String body) {
    def t = (body ?: '').toString().trim()
    if (!t) {
      return ''
    }
    return """--- Studio project context (metadata; not the author's request) ---
${t}
---"""
  }

  /**
   * Appends the site project-context block when {@code siteId} + {@code applicationContext} are available,
   * or when {@link ToolPromptsSiteContext} is active on the current thread.
   */
  static String appendToOrchestrationPrompt(String prompt, Object siteIdRaw, Object applicationContext) {
    def base = (prompt ?: '').toString()
    if (base.contains('--- Studio project context')) {
      return base
    }
    String body = readUtf8IfPresent(applicationContext, siteIdRaw)
    if (body == null) {
      body = readFromCurrentSiteContext()
    }
    if (!body) {
      return base
    }
    def block = contextBlock(body)
    if (!block) {
      return base
    }
    return base + '\n\n' + block
  }
}
