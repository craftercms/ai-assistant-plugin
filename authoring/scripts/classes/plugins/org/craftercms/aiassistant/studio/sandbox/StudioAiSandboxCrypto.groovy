package plugins.org.craftercms.aiassistant.studio.sandbox

/**
 * Crafter {@code textEncryptor} / {@code encryptionService} calls without {@code metaClass.respondsTo}
 * (blocked by Studio Groovy sandbox as insecure {@code invokeMethod}).
 */
final class StudioAiSandboxCrypto {

  private StudioAiSandboxCrypto() {}

  static Object resolveTextEncryptor(Object applicationContext) {
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

  static Object resolveEncryptionService(Object applicationContext) {
    if (applicationContext == null) {
      return null
    }
    for (String beanName : ['encryptionService', 'encryptionServiceImpl']) {
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

  /**
   * Decrypts {@code ${enc:…}} / {@code CCE-V1#…} ciphertext via {@code textEncryptor.decrypt(String)}.
   */
  static String decryptText(String cipher, Object applicationContext) {
    if (!cipher?.trim()) {
      return ''
    }
    Object textEnc = resolveTextEncryptor(applicationContext)
    if (textEnc == null) {
      return ''
    }
    try {
      return textEnc.decrypt(cipher.trim())?.toString()?.trim() ?: ''
    } catch (Throwable ignored) {
      return ''
    }
  }

  /**
   * Encrypts plaintext for secrets admin save via {@code encryptionService.encrypt(siteId, text)}.
   * @throws IllegalStateException when the bean is missing or encrypt fails
   */
  static String encryptForSite(String siteId, String plaintext, Object applicationContext) {
    String plain = (plaintext ?: '').toString()
    String sid = (siteId ?: '').toString().trim()
    Object encSvc = resolveEncryptionService(applicationContext)
    if (encSvc == null || !sid) {
      throw new IllegalStateException(
        'Crafter encryptionService is not available. Use ${env:VAR} or paste a ${enc:…} value from Studio Encrypt Marked.'
      )
    }
    try {
      String cipher = encSvc.encrypt(sid, plain)?.toString()?.trim()
      if (!cipher) {
        throw new IllegalStateException('encryptionService.encrypt returned empty ciphertext.')
      }
      return cipher
    } catch (IllegalStateException ise) {
      throw ise
    } catch (Throwable t) {
      throw new IllegalStateException("Encrypt failed: ${t.message}", t)
    }
  }
}
