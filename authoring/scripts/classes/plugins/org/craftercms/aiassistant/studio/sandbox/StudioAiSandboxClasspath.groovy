package plugins.org.craftercms.aiassistant.studio.sandbox

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

import java.nio.charset.StandardCharsets

/**
 * Classpath and site-config text loading without {@code Class.getResource*} or {@code java.io.File}
 * (blocked by Studio Groovy sandbox blacklist).
 */
final class StudioAiSandboxClasspath {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiSandboxClasspath)

  private StudioAiSandboxClasspath() {}

  /**
   * Reads UTF-8 text from the plugin classloader (leading {@code /} optional).
   */
  static String readUtf8FromClassLoader(String resourcePath) {
    String p = (resourcePath ?: '').toString().trim()
    if (!p) {
      return ''
    }
    if (!p.startsWith('/')) {
      p = '/' + p
    }
    for (ClassLoader cl : [Thread.currentThread()?.contextClassLoader, StudioAiSandboxClasspath.class.classLoader]) {
      if (cl == null) {
        continue
      }
      try {
        def is = cl.getResourceAsStream(p.startsWith('/') ? p.substring(1) : p)
        if (is == null && p.startsWith('/')) {
          is = cl.getResourceAsStream(p)
        }
        if (is != null) {
          try {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8)
          } finally {
            try {
              is.close()
            } catch (Throwable ignored) {
            }
          }
        }
      } catch (Throwable t) {
        LOG.debug('readUtf8FromClassLoader failed path={}: {}', p, t.message)
      }
    }
    return ''
  }

  /**
   * Reads a site sandbox config path via {@link StudioToolOperations} (e.g. {@code /scripts/...}).
   */
  static String readUtf8FromSiteConfig(StudioToolOperations ops, String siteId, String modulePath) {
    if (ops == null) {
      return ''
    }
    String path = (modulePath ?: '').toString().trim()
    if (!path) {
      return ''
    }
    String sid = (siteId ?: ops.resolveEffectiveSiteId('') ?: '').toString().trim()
    if (!sid) {
      return ''
    }
    try {
      return ops.readStudioConfigurationUtf8(sid, path) ?: ''
    } catch (Throwable t) {
      LOG.debug('readUtf8FromSiteConfig failed siteId={} path={}: {}', sid, path, t.message)
      return ''
    }
  }

  /**
   * Platform-settings path override: only site-relative {@code /scripts/...} paths (not host filesystem paths).
   */
  static String readUtf8PlatformScriptsPath(
    String platformSettingsKey,
    StudioToolOperations ops,
    String sessionSiteId
  ) {
    String path = plugins.org.craftercms.aiassistant.studio.config.StudioAiPlatformSettings
      .property(platformSettingsKey, '')?.trim()
    if (!path) {
      return ''
    }
    if (!path.startsWith('/scripts/')) {
      LOG.warn(
        'Platform setting {}={} must be a site sandbox path under /scripts/... (not a host filesystem path)',
        platformSettingsKey,
        path)
      return ''
    }
    return readUtf8FromSiteConfig(ops, sessionSiteId, path)
  }
}
