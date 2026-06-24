package plugins.org.craftercms.aiassistant.studio.config

import java.util.Locale

/**
 * Studio host environment variables for secrets and {@code ${env:…}} macros.
 * <p>Only names with the {@value #PREFIX} prefix are read ({@link System#getenv} is sandbox-allowed for these).</p>
 */
final class StudioAiCrafterEnv {

  static final String PREFIX = 'crafter_'

  private StudioAiCrafterEnv() {}

  /** e.g. {@code openai_api_key} → {@code crafter_openai_api_key}. */
  static String envNameForSecretKey(String secretKey) {
    String k = (secretKey ?: '').toString().trim().toLowerCase(Locale.ROOT)
    if (!k) {
      return ''
    }
    if (k.startsWith(PREFIX)) {
      return k
    }
    return PREFIX + k
  }

  /** Default {@code secrets.json} expression for a secret slot. */
  static String defaultEnvExpression(String secretKey) {
    String name = envNameForSecretKey(secretKey)
    return name ? "\${env:${name}}" : ''
  }

  /**
   * Reads a {@code crafter_*} host environment variable (sandbox-safe).
   * @param crafterEnvVarName must start with {@value #PREFIX}
   */
  static String get(String crafterEnvVarName) {
    String name = (crafterEnvVarName ?: '').toString().trim()
    if (!name.startsWith(PREFIX)) {
      return ''
    }
    try {
      String v = System.getenv(name)
      return v != null ? v.trim() : ''
    } catch (Throwable ignored) {
      return ''
    }
  }
}
