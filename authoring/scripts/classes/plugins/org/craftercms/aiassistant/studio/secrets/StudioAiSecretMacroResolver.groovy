package plugins.org.craftercms.aiassistant.studio.secrets

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Expands secret placeholders in configuration strings on the Studio JVM.
 * <ul>
 *   <li>{@code ${env:VAR}} — {@link System#getenv}</li>
 *   <li>{@code ${enc:...}} — Crafter {@code textEncryptor.decrypt(cipher)} (Studio 4.x {@code EncryptionService} is encrypt-only)</li>
 *   <li>{@code ${secret:key}} — resolves another entry from site {@code secrets.json} (no cycles)</li>
 * </ul>
 */
final class StudioAiSecretMacroResolver {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiSecretMacroResolver.class)

  private static final java.util.regex.Pattern ENV_MACRO =
    java.util.regex.Pattern.compile('\\$\\{env:([A-Za-z0-9_.]+)\\}')

  private static final java.util.regex.Pattern ENC_MACRO =
    java.util.regex.Pattern.compile('\\$\\{enc:([^}]+)\\}')

  private static final java.util.regex.Pattern SECRET_MACRO =
    java.util.regex.Pattern.compile('\\$\\{secret:([a-z][a-z0-9_]*)\\}')

  /**
   * Private constructor; not for direct use.
   */
private StudioAiSecretMacroResolver() {}

  /**
   * Wraps bare Crafter ciphertext ({@code CCE-V1#…}) saved without a {@code ${enc:…}} wrapper.
   */
  static String normalizeStoredExpression(String stored) {
    String s = (stored ?: '').toString().trim()
    if (!s) {
      return ''
    }
    if (s.startsWith('CCE-V1#') && !s.contains('${')) {
      return "\${enc:${s}}"
    }
    return s
  }

  /**
   * Expand.
   * @param siteId Studio or repository context for this call.
   * @param applicationContext Caller-supplied input.
   * @param input Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String expand(String siteId, Object applicationContext, String input) {
    return expandWithStack(
      siteId,
      applicationContext,
      normalizeStoredExpression(input),
      new LinkedHashSet<String>(),
      0
    )
  }

  /**
   * Expand with stack.
   * @return Text result, or empty or null when unavailable.
   */
  private static String expandWithStack(
    String siteId,
    Object applicationContext,
    String input,
    Set<String> secretStack,
    int depth
  ) {
    if (input == null) {
      return ''
    }
    String s = input.toString()
    if (!s.contains('${')) {
      return s
    }
    if (depth > 12) {
      LOG.warn('StudioAiSecretMacroResolver: max expansion depth exceeded siteId={}', siteId)
      return s
    }

    s = expandEnvMacros(s)
    s = expandEncMacros(siteId, applicationContext, s)
    s = expandSecretMacros(siteId, applicationContext, s, secretStack, depth)
    return s
  }

  /**
   * Expand env macros.
   * @param input Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String expandEnvMacros(String input) {
    if (input == null) {
      return ''
    }
    String s = input.toString()
    if (!s.contains('${env:')) {
      return s
    }
    java.util.regex.Matcher m = ENV_MACRO.matcher(s)
    StringBuffer sb = new StringBuffer()
    while (m.find()) {
      String name = m.group(1)
      String val = ''
      try {
        String gv = System.getenv(name)
        if (gv != null) {
          val = gv
        }
      } catch (Throwable ignored) {
      }
      m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val))
    }
    m.appendTail(sb)
    return sb.toString()
  }

  /**
   * Expand enc macros.
   * @param siteId Studio or repository context for this call.
   * @param applicationContext Caller-supplied input.
   * @param input Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String expandEncMacros(String siteId, Object applicationContext, String input) {
    String s = (input ?: '').toString()
    if (!s.contains('${enc:')) {
      return s
    }
    if (resolveTextEncryptor(applicationContext) == null) {
      LOG.debug('StudioAiSecretMacroResolver: no textEncryptor; ${enc:…} left unchanged')
      return s
    }
    java.util.regex.Matcher m = ENC_MACRO.matcher(s)
    StringBuffer sb = new StringBuffer()
    while (m.find()) {
      String cipher = m.group(1)?.toString()?.trim() ?: ''
      String plain = decryptEncCipher(siteId, applicationContext, cipher)
      m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(plain))
    }
    m.appendTail(sb)
    return sb.toString()
  }

  /** Decrypts ciphertext from {@code ${enc:…}} (CCE-V1#…) saved via Studio Encrypt Marked / Secrets UI. */
  private static String decryptEncCipher(String siteId, Object applicationContext, String cipher) {
    if (!cipher?.trim()) {
      return ''
    }
    Object textEnc = resolveTextEncryptor(applicationContext)
    if (textEnc != null && textEnc.metaClass.respondsTo(textEnc, 'decrypt', String)) {
      try {
        return textEnc.decrypt(cipher.trim())?.toString()?.trim() ?: ''
      } catch (Throwable t) {
        LOG.warn('StudioAiSecretMacroResolver: textEncryptor.decrypt failed siteId={}: {}', siteId, t.message)
      }
    }
    return ''
  }

  /**
   * Expand secret macros.
   * @return Text result, or empty or null when unavailable.
   */
  private static String expandSecretMacros(
    String siteId,
    Object applicationContext,
    String input,
    Set<String> secretStack,
    int depth
  ) {
    String s = (input ?: '').toString()
    if (!s.contains('${secret:')) {
      return s
    }
    java.util.regex.Matcher m = SECRET_MACRO.matcher(s)
    StringBuffer sb = new StringBuffer()
    while (m.find()) {
      String secretKey = m.group(1)?.toString()?.trim() ?: ''
      String val = ''
      if (secretKey && !secretStack.contains(secretKey)) {
        secretStack.add(secretKey)
        try {
          String stored = StudioAiAssistantSecretsService.rawStoredValue(siteId, applicationContext, secretKey)
          val = expandWithStack(siteId, applicationContext, stored, secretStack, depth + 1)
        } finally {
          secretStack.remove(secretKey)
        }
      }
      m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val))
    }
    m.appendTail(sb)
    return sb.toString()
  }

  /**
   * Resolves text encryptor from request and plugin context.
   * @param applicationContext Caller-supplied input.
   * @return Object result.
   */
  private static Object resolveTextEncryptor(Object applicationContext) {
    if (applicationContext == null) {
      return null
    }
    for (String beanName : ['crafter.textEncryptor', 'textEncryptor']) {
      try {
        Object bean = applicationContext.get(beanName)
        if (bean != null) {
          return bean
        }
      } catch (Throwable ignored) {
      }
    }
    return null
  }

  /** Classifies a stored value for admin UI (never returns decrypted plaintext for literals). */
  static String classifyStoredValue(String stored) {
    String s = normalizeStoredExpression(stored)
    if (!s) {
      return 'empty'
    }
    if (ENV_MACRO.matcher(s).matches()) {
      return 'env'
    }
    if (s.startsWith('${enc:') || s.startsWith('CCE-V1#')) {
      return 'enc'
    }
    if (SECRET_MACRO.matcher(s).matches()) {
      return 'secret_ref'
    }
    return 'literal'
  }

  /**
   * Env var from expression.
   * @param stored Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String envVarFromExpression(String stored) {
    String s = (stored ?: '').toString().trim()
    java.util.regex.Matcher m = ENV_MACRO.matcher(s)
    if (m.matches()) {
      return m.group(1) ?: ''
    }
    return ''
  }
}
