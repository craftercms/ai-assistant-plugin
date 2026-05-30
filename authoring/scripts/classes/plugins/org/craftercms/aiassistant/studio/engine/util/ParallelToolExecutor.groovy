package plugins.org.craftercms.aiassistant.studio.engine.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.config.StudioAiPlatformSettings
import plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxConcurrency

import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM-wide bounded executor for parallel server tool work (e.g. {@code TranslateContentBatch} cells).
 * <p>Uses named <strong>daemon</strong> threads (not Tomcat HTTP workers). Callers bound per-job parallelism with a
 * {@link java.util.concurrent.Semaphore}; this pool only provides stable capacity + a bounded queue so bursts do not
 * spawn unbounded short-lived pools or risk queue growth without limit.</p>
 */
final class ParallelToolExecutor {
  private static final Logger log = LoggerFactory.getLogger(ParallelToolExecutor.class)
  private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1)
  private static volatile ThreadPoolExecutor INSTANCE

  /**
   * Private constructor; not for direct use.
   */
private ParallelToolExecutor() {}

  /**
   * Resolves int prop from request and plugin context.
   * @param sysKey Caller-supplied input.
   * @param defaultValue Caller-supplied input.
   * @param min Caller-supplied input.
   * @param max Caller-supplied input.
   * @return int result.
   */
  private static int resolveIntProp(String sysKey, int defaultValue, int min, int max) {
    return StudioAiPlatformSettings.propertyInt(sysKey, defaultValue, min, max)
  }

  /**
   * Executor.
   * @return ThreadPoolExecutor result.
   */
  static ThreadPoolExecutor executor() {
    ThreadPoolExecutor ex = INSTANCE
    if (ex != null && !ex.isShutdown()) {
      return ex
    }
    synchronized (ParallelToolExecutor.class) {
      ex = INSTANCE
      if (ex != null && !ex.isShutdown()) {
        return ex
      }
      int n = StudioAiSandboxConcurrency.availableProcessors()
      int maxPool = resolveIntProp('aiassistant.parallelToolPoolMax', Math.min(32, Math.max(8, n * 2)), 2, 64)
      int corePool = resolveIntProp('aiassistant.parallelToolPoolCore', Math.min(maxPool, Math.max(2, n)), 1, maxPool)
      if (corePool > maxPool) {
        corePool = maxPool
      }
      int queueCap = resolveIntProp('aiassistant.parallelToolPoolQueue', 512, 16, 4096)
      def tf = new ParallelToolThreadFactory(THREAD_SEQ)
      ex = new ThreadPoolExecutor(
        corePool,
        maxPool,
        120L,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(queueCap),
        tf,
        new ThreadPoolExecutor.CallerRunsPolicy()
      )
      ex.allowCoreThreadTimeOut(true)
      INSTANCE = ex
      log.info(
        'ParallelToolExecutor: started core={} max={} queueCap={}',
        corePool,
        maxPool,
        queueCap
      )
      return ex
    }
  }

  static <T> Future<T> submit(Callable<T> task) {
    return executor().submit(task)
  }
}
