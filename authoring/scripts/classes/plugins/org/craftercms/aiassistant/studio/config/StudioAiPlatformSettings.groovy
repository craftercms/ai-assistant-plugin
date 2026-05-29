package plugins.org.craftercms.aiassistant.studio.config

import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

import java.util.Collections
import java.util.LinkedHashMap
import java.util.Map

/**
 * Per-site platform settings from {@link #PLATFORM_JSON_PATH} (sandbox-safe; no {@code System} access).
 * Call {@link #enter} before orchestration and {@link #exit} in {@code finally} on the request thread.
 */
final class StudioAiPlatformSettings {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiPlatformSettings.class)

  static final String PLATFORM_JSON_PATH = '/scripts/aiassistant/config/platform-settings.json'

  private static final ThreadLocal<Map> TL = new ThreadLocal<>()

  private StudioAiPlatformSettings() {}

  static void enter(Object applicationContext, String siteId) {
    Map ctx = new LinkedHashMap(4)
    ctx.put('applicationContext', applicationContext)
    ctx.put('siteId', (siteId ?: '').toString().trim())
    ctx.put('values', loadValues(applicationContext, ctx.get('siteId') as String))
    TL.set(ctx)
  }

  static void exit() {
    TL.remove()
  }

  static String property(String key, String defaultValue = '') {
    String k = (key ?: '').toString().trim()
    if (!k) {
      return (defaultValue ?: '').toString()
    }
    Map values = currentValues()
    if (values != null) {
      Object v = values.get(k)
      if (v != null) {
        String s = v.toString().trim()
        if (s) {
          return s
        }
      }
    }
    return (defaultValue ?: '').toString()
  }

  static boolean propertyBoolean(String key, boolean defaultValue) {
    String raw = property(key, defaultValue ? 'true' : 'false')?.toString()?.trim()
    if (!raw) {
      return defaultValue
    }
    return !'false'.equalsIgnoreCase(raw) && !'0'.equals(raw) && !'no'.equalsIgnoreCase(raw)
  }

  static int propertyInt(String key, int defaultValue, int min, int max) {
    try {
      String raw = property(key, '')?.toString()?.trim()
      if (raw) {
        int v = Integer.parseInt(raw)
        return Math.max(min, Math.min(max, v))
      }
    } catch (Throwable ignored) {
    }
    return defaultValue
  }

  static boolean anyPropertyPresent(String... keys) {
    if (keys == null) {
      return false
    }
    for (String key : keys) {
      if (property(key, '')?.trim()) {
        return true
      }
    }
    return false
  }

  private static Map currentValues() {
    Map ctx = TL.get()
    return ctx != null ? (ctx.get('values') as Map) : null
  }

  private static Map loadValues(Object applicationContext, String siteId) {
    if (!siteId || applicationContext == null) {
      return Collections.emptyMap()
    }
    try {
      def ops = new StudioToolOperations(null, applicationContext, [:], null)
      String raw = ops.readStudioConfigurationUtf8(siteId, PLATFORM_JSON_PATH) ?: ''
      if (!raw.trim()) {
        return Collections.emptyMap()
      }
      def parsed = new JsonSlurper().parseText(raw)
      if (parsed instanceof Map) {
        return Collections.unmodifiableMap(new LinkedHashMap(parsed))
      }
    } catch (Throwable t) {
      LOG.debug('StudioAiPlatformSettings: could not load {} siteId={}: {}', PLATFORM_JSON_PATH, siteId, t.message)
    }
    return Collections.emptyMap()
  }
}
