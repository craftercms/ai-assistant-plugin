package plugins.org.craftercms.aiassistant.studio.secrets

/**
 * Request-scoped site id for secret resolution during orchestration (stream, tools, MCP).
 * Bind at REST entry points; always {@link #clear()} in a {@code finally} block.
 */
final class StudioAiAssistantSecretsContext {

  private static final ThreadLocal<String> SITE_ID = new ThreadLocal<>()
  private static final ThreadLocal<Object> APPLICATION_CONTEXT = new ThreadLocal<>()

  /**
   * Private constructor; not for direct use.
   */
private StudioAiAssistantSecretsContext() {}

  /**
   * Binds the working site id and Spring application context for secret resolution on this thread.
   * @param siteId CMS site id for macro lookup.
   * @param applicationContext Spring context for secret services.
   */
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

  /**
   * Clears thread-local secret resolution state; call from a finally block at REST boundaries.
   */
  static void clear() {
    SITE_ID.remove()
    APPLICATION_CONTEXT.remove()
  }

  /**
   * Returns the site id bound for secret macro resolution on this thread, or empty when unset.
   * @return Trimmed site id, or empty when not bound.
   */
  static String currentSiteId() {
    return (SITE_ID.get() ?: '').toString().trim()
  }

  /**
   * Returns the application context bound for secret resolution on this thread, or null when unset.
   * @return Bound context, or null when not bound.
   */
  static Object currentApplicationContext() {
    return APPLICATION_CONTEXT.get()
  }
}
