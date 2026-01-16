package mechanoid.persistence.timeout

import zio.*
import mechanoid.core.{MechanoidError, PersistenceError}

/** Metrics emitted by the sweeper for monitoring.
  *
  * @param sweepCount
  *   Total number of sweep iterations completed
  * @param timeoutsFired
  *   Number of timeouts successfully fired
  * @param timeoutsSkipped
  *   Timeouts skipped (state changed, cancelled, etc.)
  * @param claimConflicts
  *   Times another node had already claimed a timeout
  * @param errors
  *   Number of errors encountered during sweeping
  */
final case class SweeperMetrics(
    sweepCount: Long,
    timeoutsFired: Long,
    timeoutsSkipped: Long,
    claimConflicts: Long,
    errors: Long,
)

object SweeperMetrics:
  val empty: SweeperMetrics = SweeperMetrics(0, 0, 0, 0, 0)

/** A background service that sweeps for expired timeouts and fires them.
  *
  * ==Operation Modes==
  *
  * '''Multi-sweeper mode''' (default): Multiple nodes run sweepers concurrently. Coordination via atomic database
  * claims prevents duplicate firing. This is simpler to deploy and naturally load-balanced.
  *
  * '''Single-active mode''' (with leader election): Only the leader sweeps. Reduces database load but requires leader
  * election infrastructure and has slightly longer failover time.
  *
  * ==Jitter Algorithm==
  *
  * To prevent thundering herd when multiple sweepers start simultaneously:
  * {{{
  * actualWait = sweepInterval + random(0, jitterFactor * sweepInterval)
  *            + (noTimeoutsFound ? backoffOnEmpty : 0)
  * }}}
  *
  * ==Claim Flow==
  *
  * {{{
  * [Query Expired] → [For Each Timeout]
  *                         │
  *                    [Claim Atomically]
  *                         │
  *        ┌────────────────┼────────────────┐
  *        ▼                ▼                ▼
  *   [Claimed]      [AlreadyClaimed]   [NotFound]
  *        │                │                │
  *        ▼                │                │
  *   [Fire Timeout]        │                │
  *        │                │                │
  *   ┌────┴────┐           │                │
  *   ▼         ▼           ▼                ▼
  * [Success] [Error]    [Skip]          [Skip]
  *   │         │
  *   ▼         ▼
  * [Complete] [Release]
  * }}}
  *
  * ==Usage==
  *
  * {{{
  * ZIO.scoped {
  *   for
  *     sweeper <- TimeoutSweeper.make(
  *       config = TimeoutSweeperConfig()
  *         .withSweepInterval(5.seconds)
  *         .withJitterFactor(0.2),
  *       timeoutStore = myTimeoutStore,
  *       onTimeout = (id, state) => myFsm.fireTimeout(id, state)
  *     )
  *     _ <- ZIO.never // Keep running until scope closes
  *   yield ()
  * }
  * }}}
  */
trait TimeoutSweeper:
  /** Check if the sweeper is currently running. */
  def isRunning: UIO[Boolean]

  /** Get current metrics for monitoring. */
  def metrics: UIO[SweeperMetrics]

  /** Stop the sweeper gracefully. */
  def stop: UIO[Unit]

object TimeoutSweeper:

  /** Create and start a timeout sweeper.
    *
    * The sweeper starts immediately and runs until the scope closes or `stop` is called.
    *
    * @param config
    *   Sweeper configuration (intervals, batch size, jitter, etc.)
    * @param timeoutStore
    *   Storage for scheduled timeouts
    * @param onTimeout
    *   Callback when a timeout fires - receives instanceId and expected state. Should send a Timeout event to the FSM
    *   and handle errors.
    * @param leaseStore
    *   Optional lease store for leader election (required if config.leaderElection is set)
    * @return
    *   A scoped TimeoutSweeper service
    */
  def make[Id](
      config: TimeoutSweeperConfig,
      timeoutStore: TimeoutStore[Id],
      onTimeout: (Id, String) => ZIO[Any, MechanoidError, Unit],
      leaseStore: Option[LeaseStore] = None,
  ): ZIO[Scope, MechanoidError, TimeoutSweeper] =
    for
      runningRef <- Ref.make(true)
      metricsRef <- Ref.make(SweeperMetrics.empty)

      // Set up leader election if configured
      leaderElection <- config.leaderElection match
        case Some(leConfig) =>
          leaseStore match
            case Some(ls) =>
              LeaderElection.make(leConfig, config.nodeId, ls).map(Some(_))
            case None =>
              ZIO.fail(
                PersistenceError(
                  new IllegalArgumentException(
                    "LeaseStore required when leader election is configured"
                  )
                )
              )
        case None =>
          ZIO.succeed(None)

      // Start the sweep loop
      _ <- runSweepLoop(
        config,
        timeoutStore,
        onTimeout,
        runningRef,
        metricsRef,
        leaderElection,
      ).forkScoped
    yield new TimeoutSweeper:
      def isRunning: UIO[Boolean]      = runningRef.get
      def metrics: UIO[SweeperMetrics] = metricsRef.get
      def stop: UIO[Unit]              =
        runningRef.set(false) *>
          leaderElection.fold(ZIO.unit)(_.resign)

  private def runSweepLoop[Id](
      config: TimeoutSweeperConfig,
      store: TimeoutStore[Id],
      onTimeout: (Id, String) => ZIO[Any, MechanoidError, Unit],
      runningRef: Ref[Boolean],
      metricsRef: Ref[SweeperMetrics],
      leaderElection: Option[LeaderElection],
  ): ZIO[Any, Nothing, Unit] =

    // Sweep effect returns shouldContinue status
    val sweep: ZIO[Any, Nothing, Boolean] =
      for
        // Check if we should sweep (always in multi-mode, only if leader in single-mode)
        shouldSweep <- leaderElection.fold(ZIO.succeed(true))(_.isLeader)
        count       <-
          if shouldSweep then
            performSweep(config, store, onTimeout, metricsRef)
              .catchAll { error =>
                metricsRef.update(m => m.copy(errors = m.errors + 1)) *>
                  ZIO.logError(s"Sweep error: $error").as(0)
              }
          else ZIO.succeed(0)
        _ <- metricsRef.update(m => m.copy(sweepCount = m.sweepCount + 1))
        // Add extra backoff delay when no timeouts found (embedded in effect)
        _ <- config.backoffOnEmpty match
          case Some(backoff) if count == 0 => ZIO.sleep(backoff)
          case _                           => ZIO.unit
        shouldContinue <- runningRef.get
      yield shouldContinue

    // Schedule with timing, jitter, and stop condition
    val baseSchedule     = Schedule.spaced(config.sweepInterval)
    val jitteredSchedule =
      if config.jitterFactor > 0 then baseSchedule.jittered(0.0, config.jitterFactor)
      else baseSchedule
    val schedule = jitteredSchedule && Schedule.recurWhile[Boolean](identity)

    sweep.repeat(schedule).unit
  end runSweepLoop

  private def performSweep[Id](
      config: TimeoutSweeperConfig,
      store: TimeoutStore[Id],
      onTimeout: (Id, String) => ZIO[Any, MechanoidError, Unit],
      metricsRef: Ref[SweeperMetrics],
  ): ZIO[Any, MechanoidError, Int] =
    for
      now     <- Clock.instant
      expired <- store.queryExpired(config.batchSize, now)
      results <- ZIO.foreach(expired) { timeout =>
        processTimeout(config, store, onTimeout, timeout, metricsRef)
      }
    yield results.count(identity)

  private def processTimeout[Id](
      config: TimeoutSweeperConfig,
      store: TimeoutStore[Id],
      onTimeout: (Id, String) => ZIO[Any, MechanoidError, Unit],
      timeout: ScheduledTimeout[Id],
      metricsRef: Ref[SweeperMetrics],
  ): ZIO[Any, MechanoidError, Boolean] =
    for
      now         <- Clock.instant
      claimResult <- store.claim(
        timeout.instanceId,
        config.nodeId,
        config.claimDuration,
        now,
      )
      fired <- claimResult match
        case ClaimResult.Claimed(_) =>
          // Successfully claimed - fire the timeout
          onTimeout(timeout.instanceId, timeout.state)
            .flatMap { _ =>
              store.complete(timeout.instanceId) *>
                metricsRef.update(m => m.copy(timeoutsFired = m.timeoutsFired + 1))
            }
            .catchAll { error =>
              // Release claim on error so another node can retry
              store.release(timeout.instanceId) *>
                ZIO.logWarning(
                  s"Failed to fire timeout for ${timeout.instanceId}: $error"
                )
            }
            .as(true)

        case ClaimResult.AlreadyClaimed(_, _) =>
          metricsRef
            .update(m => m.copy(claimConflicts = m.claimConflicts + 1))
            .as(false)

        case ClaimResult.NotFound | ClaimResult.StateChanged(_) =>
          metricsRef
            .update(m => m.copy(timeoutsSkipped = m.timeoutsSkipped + 1))
            .as(false)
    yield fired
end TimeoutSweeper
