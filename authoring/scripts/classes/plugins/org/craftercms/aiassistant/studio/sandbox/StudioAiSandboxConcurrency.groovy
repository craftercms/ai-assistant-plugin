package plugins.org.craftercms.aiassistant.studio.sandbox

import plugins.org.craftercms.aiassistant.studio.config.StudioAiPlatformSettings

/**
 * Concurrency sizing without {@code Runtime.getRuntime()} (blocked by Studio Groovy sandbox blacklist).
 */
final class StudioAiSandboxConcurrency {

  private static final int DEFAULT_PROCESSORS = 4

  private StudioAiSandboxConcurrency() {}

  /**
   * Logical CPU count for pool defaults. Override with {@code aiassistant.runtime.availableProcessors}
   * in {@code platform-settings.json} (range 1–64).
   */
  static int availableProcessors() {
    return StudioAiPlatformSettings.propertyInt(
      'aiassistant.runtime.availableProcessors',
      DEFAULT_PROCESSORS,
      1,
      64)
  }
}
