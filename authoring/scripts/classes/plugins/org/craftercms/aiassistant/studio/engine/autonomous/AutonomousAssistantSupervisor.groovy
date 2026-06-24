package plugins.org.craftercms.aiassistant.studio.engine.autonomous

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.config.StudioAiPlatformSettings
import plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxConcurrency

/**
 * Prototype supervisor: wakes on a fixed interval ({@link #TICK_MS}) and evaluates every registered agent.
 * <p><b>Important:</b> {@link #TICK_MS} is only how often we <em>check</em> — it is <b>not</b> each agent’s run cadence.
 * Per-agent timing uses {@code definition.schedule} → {@link AutonomousScheduleProbe#inferPeriodMillis} plus
 * {@code state.lastRunMillis} (and {@code nextStepRequired}) inside {@link #tick()}.
 */
final class AutonomousAssistantSupervisor {

  private static final Logger log = LoggerFactory.getLogger(AutonomousAssistantSupervisor)

  /** Global poll interval (ms): how often {@link #tick()} runs — not the same as any one agent’s {@code schedule}. */
  private static final long TICK_MS = 10_000L

  /** Off until an author calls {@link #enableSupervisor()} from the widget (tick thread may run but {@link #tick()} no-ops while false). */
  private static volatile boolean supervisorEnabledFlag = false
  private static ScheduledExecutorService supervisorExec
  private static ExecutorService workerPool
  private static volatile ScheduledFuture<?> supervisorFuture
  private static final ConcurrentHashMap<String, Boolean> RUNNING = new ConcurrentHashMap<>()

  /** When set, {@link #haltSupervisorAfterAgentFailure} disabled the supervisor (legacy); worker failures no longer set this. */
  private static volatile String supervisorHaltReasonText = ''

  /**
   * Private constructor; not for direct use.
   */
private AutonomousAssistantSupervisor() {}

  /**
   * Carries {@link #agentId} so a saturated pool reject handler can {@link ConcurrentHashMap#remove(Object)} from
   * {@link #RUNNING} — otherwise {@link #tick()} would leak RUNNING and the agent would never reschedule.
   */
  private static final class AutonomousAgentRunRunnable implements Runnable {
    final String siteId
    final String agentId
    final Map agentDef

    AutonomousAgentRunRunnable(String siteId, String agentId, Map agentDef) {
      this.siteId = siteId
      this.agentId = agentId
      this.agentDef = agentDef
    }

    @Override
    /**
     * Runs run using Studio services and returns the tool payload.
     */
    void run() {
      try {
        AutonomousAssistantWorker.runStep(siteId, agentId, agentDef)
      } finally {
        RUNNING.remove(agentId)
      }
    }
  }

  /** {@link ThreadPoolExecutor#submit} wraps runnables in {@code FutureTask}; {@link #execute} does not. */
  private static AutonomousAgentRunRunnable autonomousRunnableFromRejectedTask(Runnable r) {
    if (r instanceof AutonomousAgentRunRunnable) {
      return (AutonomousAgentRunRunnable) r
    }
    return null
  }

  /** Named class — Studio sandbox blocks anonymous {@link ThreadFactory} implementations. */
  private static final class SupervisorThreadFactory implements ThreadFactory {
    @Override
    Thread newThread(Runnable r) {
      Thread t = new Thread(r, 'aiassistant-autonomous-supervisor')
      t.setDaemon(true)
      return t
    }
  }

  private static final AtomicInteger WORKER_THREAD_SEQ = new AtomicInteger(1)

  /** Named class — Studio sandbox blocks anonymous {@link ThreadFactory} implementations. */
  private static final class WorkerThreadFactory implements ThreadFactory {
    @Override
    Thread newThread(Runnable r) {
      Thread t = new Thread(r, 'aiassistant-autonomous-worker-' + WORKER_THREAD_SEQ.getAndIncrement())
      t.setDaemon(true)
      return t
    }
  }

  /** Named class — Studio sandbox blocks anonymous {@link RejectedExecutionHandler} implementations. */
  private static final class AutonomousWorkerRejectedHandler implements RejectedExecutionHandler {
    @Override
    void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
      AutonomousAgentRunRunnable ar = autonomousRunnableFromRejectedTask(r)
      if (ar != null) {
        RUNNING.remove(ar.agentId)
      }
      log.warn(
        'Autonomous worker pool saturated; task rejected (not run on supervisor thread). active={} poolSize={} queue={}',
        executor.activeCount,
        executor.poolSize,
        executor.queue?.size()
      )
    }
  }

  /** Named class — Studio sandbox blocks closure-backed schedule runnables. */
  private static final class SupervisorTickRunnable implements Runnable {
    @Override
    void run() {
      tick()
    }
  }

  static synchronized void ensureStarted() {
    if (supervisorExec == null || supervisorExec.isShutdown()) {
      supervisorExec = Executors.newSingleThreadScheduledExecutor(new SupervisorThreadFactory())
    }
    if (workerPool == null || workerPool.isShutdown()) {
      int n = Math.max(1, StudioAiSandboxConcurrency.availableProcessors())
      int maxPool = Math.min(16, Math.max(4, n * 2))
      int corePool = Math.min(maxPool, Math.max(2, n))
      int queueCap = 128
      maxPool = StudioAiPlatformSettings.propertyInt('aiassistant.autonomous.worker.max', maxPool, 1, 64)
      corePool = StudioAiPlatformSettings.propertyInt('aiassistant.autonomous.worker.core', corePool, 1, maxPool)
      queueCap = StudioAiPlatformSettings.propertyInt('aiassistant.autonomous.worker.queue', queueCap, 8, 4096)
      maxPool = Math.max(1, maxPool)
      corePool = Math.min(Math.max(1, corePool), maxPool)
      // Do not use CallerRunsPolicy: {@link #tick()} submits from the single supervisor thread; saturation would
      // run agent work inline on that thread and stall all ticks. Log and drop when saturated instead.
      workerPool = new ThreadPoolExecutor(
        corePool,
        maxPool,
        60L,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(queueCap),
        new WorkerThreadFactory(),
        new AutonomousWorkerRejectedHandler()
      )
      log.info(
        'Autonomous worker pool: core={} max={} queueCap={} (override aiassistant.autonomous.worker.core/max/queue)',
        corePool,
        maxPool,
        queueCap
      )
    }
    if (supervisorFuture == null || supervisorFuture.isCancelled()) {
      supervisorFuture = supervisorExec.scheduleAtFixedRate(
        new SupervisorTickRunnable(),
        2,
        TICK_MS,
        TimeUnit.MILLISECONDS
      )
      log.info('AutonomousAssistantSupervisor started (tick every {} ms)', TICK_MS)
    }
  }

  static synchronized void disableSupervisor() {
    supervisorEnabledFlag = false
    if (supervisorFuture != null) {
      supervisorFuture.cancel(false)
      supervisorFuture = null
    }
    log.info('AutonomousAssistantSupervisor disabled')
  }

  /**
   * Stops the supervisor schedule (legacy hook). Worker failures no longer call this so other agents keep running;
   * authors may still use **Stop system** / {@code disable_supervisor} explicitly.
   */
  static synchronized void haltSupervisorAfterAgentFailure(String reason) {
    disableSupervisor()
    supervisorHaltReasonText = (reason ?: 'Agent run failed').toString().trim()
    log.warn('AutonomousAssistantSupervisor halted: {}', supervisorHaltReasonText)
  }

  /**
   * Returns supervisor halt reason.
   * @return Text result, or empty or null when unavailable.
   */
  static String getSupervisorHaltReason() {
    supervisorHaltReasonText ?: ''
  }

  static synchronized void enableSupervisor() {
    supervisorEnabledFlag = true
    supervisorHaltReasonText = ''
    // Always reschedule: a non-cancelled future can survive disable in edge cases; authors expect Start to arm ticks.
    if (supervisorFuture != null) {
      supervisorFuture.cancel(false)
      supervisorFuture = null
    }
    ensureStarted()
  }

  static synchronized void shutdownPools() {
    if (supervisorFuture != null) {
      supervisorFuture.cancel(false)
      supervisorFuture = null
    }
    if (supervisorExec != null) {
      supervisorExec.shutdown()
      try {
        supervisorExec.awaitTermination(5, TimeUnit.SECONDS)
      } catch (Throwable ignored) {}
      supervisorExec = null
    }
    if (workerPool != null) {
      workerPool.shutdown()
      try {
        workerPool.awaitTermination(10, TimeUnit.SECONDS)
      } catch (Throwable ignored) {}
      workerPool = null
    }
    log.info('AutonomousAssistantSupervisor pools shut down')
  }

  static synchronized void destroyInMemoryStore() {
    disableSupervisor()
    shutdownPools()
    AutonomousAssistantStateStore.clearAll()
    AutonomousAssistantRegistry.clearAll()
    RUNNING.clear()
    supervisorHaltReasonText = ''
    AutonomousAssistantRuntimeHooks.clear()
    log.info('AutonomousAssistantSupervisor in-memory store cleared (re-sync agents from the UI after this)')
  }

  /**
   * Clear site.
   * @param siteId Studio or repository context for this call.
   */
  static void clearSite(String siteId) {
    if (!siteId?.trim()) {
      return
    }
    AutonomousAssistantRegistry.removeSite(siteId.trim())
    AutonomousAssistantStateStore.removeKeysForSite(siteId.trim())
  }

  /**
   * True when supervisor enabled.
   * @return True when the check succeeds.
   */
  static boolean isSupervisorEnabled() {
    supervisorEnabledFlag
  }

  /**
   * Status snapshot.
   * @return Map payload for tools or orchestration.
   */
  static Map statusSnapshot() {
    [
      supervisorEnabled   : supervisorEnabledFlag,
      tickMs              : TICK_MS,
      supervisorRunning   : supervisorFuture != null && !supervisorFuture.isCancelled(),
      workerPoolActive    : workerPool != null && !workerPool.isTerminated(),
      supervisorHaltReason: getSupervisorHaltReason()
    ]
  }

  /**
   * Tick.
   */
  private static void tick() {
    if (!supervisorEnabledFlag || workerPool == null) {
      return
    }
    try {
      Map sites = AutonomousAssistantRegistry.snapshotAllSites()
      long now = System.currentTimeMillis()
      for (Object siteKey : sites.keySet()) {
        String siteId = siteKey.toString()
        ConcurrentHashMap<String, Map> agents = AutonomousAssistantRegistry.agentsForSite(siteId)
        for (Map.Entry<String, Map> e : agents.entrySet()) {
          String agentId = e.key
          Map agentDef = e.value
          if (agentDef == null) {
            continue
          }
          Map st = AutonomousAssistantStateStore.getState(agentId)
          if (st == null) {
            continue
          }
          String stName = st.get('status')?.toString()
          if (AutonomousAssistantStatus.matches(stName, AutonomousAssistantStatus.DISABLED) ||
            AutonomousAssistantStatus.matches(stName, AutonomousAssistantStatus.STOPPED) ||
            AutonomousAssistantStatus.matches(stName, AutonomousAssistantStatus.ERROR)) {
            continue
          }
          boolean nextStep = Boolean.TRUE.equals(st.get('nextStepRequired')) ||
            'true'.equalsIgnoreCase(st.get('nextStepRequired')?.toString())
          long last = 0L
          try {
            Object lr = st.get('lastRunMillis')
            if (lr instanceof Number) {
              last = ((Number) lr).longValue()
            }
          } catch (Throwable ignored) {}
          String sched = agentDef.get('schedule')?.toString()
          // Per-agent minimum spacing between runs (from cron-ish schedule), independent of TICK_MS.
          long period = AutonomousScheduleProbe.inferPeriodMillis(sched)
          boolean due = nextStep || (now - last >= period)
          if (!due) {
            continue
          }
          if (RUNNING.putIfAbsent(agentId, Boolean.TRUE) != null) {
            continue
          }
          // execute() (not submit()) so rejection handler receives AutonomousAgentRunRunnable, not a FutureTask wrapper.
          workerPool.execute(new AutonomousAgentRunRunnable(siteId, agentId, agentDef))
        }
      }
    } catch (Throwable t) {
      log.warn('AutonomousAssistantSupervisor tick failed: {}', t.message)
    }
  }
}
