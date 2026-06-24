package plugins.org.craftercms.aiassistant.studio.engine.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Named {@link ThreadFactory} for {@link ParallelToolExecutor}.
 * <p>Must not be a Groovy closure — sandbox blocks {@code new Thread} invoked via closure
 * {@code invokeMethod('newThread', …)}.</p>
 */
final class ParallelToolThreadFactory implements ThreadFactory {

  private static final Logger log = LoggerFactory.getLogger(ParallelToolThreadFactory)

  private final AtomicInteger threadSeq

  ParallelToolThreadFactory(AtomicInteger threadSeq) {
    this.threadSeq = threadSeq
  }

  @Override
  Thread newThread(Runnable r) {
    Thread t = new Thread(r, 'aiassistant-parallel-tools-' + threadSeq.getAndIncrement())
    t.setDaemon(true)
    t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
      @Override
      void uncaughtException(Thread th, Throwable err) {
        log.error('Uncaught exception on parallel tool thread {}', th?.name, err)
      }
    })
    return t
  }
}
