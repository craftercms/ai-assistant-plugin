package plugins.org.craftercms.aiassistant.studio.sandbox

import java.util.concurrent.atomic.AtomicLong

/**
 * Time helpers that avoid {@code System.nanoTime()} (blocked by Studio Groovy sandbox blacklist).
 * Uses {@code System.currentTimeMillis()} (allowed under default blacklist policy).
 */
final class StudioAiSandboxClock {

  private static final AtomicLong UNIQUE_SEQ = new AtomicLong()

  private StudioAiSandboxClock() {}

  /** Milliseconds since epoch (timing and unique suffixes). */
  static long millis() {
    return System.currentTimeMillis()
  }

  /** Elapsed milliseconds since {@code startMillis}. */
  static long elapsedMs(long startMillis) {
    return millis() - startMillis
  }

  /**
   * Lowercase hex suitable for thread / correlation ids.
   * {@code millis}-hex plus a monotonic JVM sequence — unique across rapid calls in the same millisecond.
   */
  static String uniqueHexSuffix() {
    return Long.toHexString(millis()) + '-' + Long.toHexString(UNIQUE_SEQ.incrementAndGet())
  }
}
