package plugins.org.craftercms.aiassistant.secrets

/**
 * Request-scoped site id for secret resolution during orchestration (stream, tools, MCP).
 * Bind at REST entry points; always {@link #clear()} in a {@code finally} block.
 */
final class StudioAiAssistantSecretsContext {

  private static final ThreadLocal<String> SITE_ID = new ThreadLocal<>()
  private static final ThreadLocal<Object> APPLICATION_CONTEXT = new ThreadLocal<>()

  private StudioAiAssistantSecretsContext() {}

  static void bind(String siteId, Object applicationContext) {
    String s = (siteId ?: '').toString().trim()
    if (s) {
      SITE_ID.set(s)
    } else {
      SITE_ID.remove()
    }
    if (applicationContext != null) {
      APPLICATION_CONTEXT.set(applicationContext)
    } else {
      APPLICATION_CONTEXT.remove()
    }
  }

  static void clear() {
    SITE_ID.remove()
    APPLICATION_CONTEXT.remove()
  }

  static String currentSiteId() {
    return (SITE_ID.get() ?: '').toString().trim()
  }

  static Object currentApplicationContext() {
    return APPLICATION_CONTEXT.get()
  }
}
