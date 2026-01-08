package mechanoid.persistence.command

import zio.*
import java.time.Instant
import scala.annotation.unused

/** Background service that processes commands from a [[CommandStore]].
  *
  * The worker polls the store for pending commands, executes them using your provided executor function, and marks them
  * as completed or failed.
  *
  * ==Features==
  *
  *   - '''Exactly-once processing''': Commands are claimed atomically, preventing duplicates
  *   - '''Automatic retries''': Failed commands are retried according to [[RetryPolicy]]
  *   - '''Dead worker recovery''': Expired claims are released automatically
  *   - '''Graceful shutdown''': Completes in-flight commands before stopping
  *
  * ==Usage==
  *
  * {{{
  * // Define your executor
  * val executor: OrderCommand => ZIO[Any, Throwable, CommandResult] = {
  *   case ChargeCard(amount, token) =>
  *     paymentService.charge(amount, token)
  *       .as(CommandResult.Success)
  *       .catchAll(e => ZIO.succeed(CommandResult.Failure(e.getMessage, retryable = true)))
  *   case SendEmail(to, template) =>
  *     emailService.send(to, template).as(CommandResult.Success)
  * }
  *
  * // Start the worker (managed by Scope)
  * val program = ZIO.scoped {
  *   for
  *     worker <- CommandWorker.make(config, commandStore, executor)
  *     _      <- worker.awaitShutdown // Or do other work
  *   yield ()
  * }
  * }}}
  *
  * @tparam Id
  *   The FSM instance identifier type
  * @tparam Cmd
  *   The command type
  */
trait CommandWorker[Id, Cmd]:
  /** Stop the worker gracefully.
    *
    * In-flight commands will complete before the worker stops.
    */
  def stop: UIO[Unit]

  /** Check if the worker is running. */
  def isRunning: UIO[Boolean]

  /** Wait for the worker to shut down. */
  def awaitShutdown: UIO[Unit]

  /** Get current worker metrics. */
  def metrics: UIO[CommandWorkerMetrics]
end CommandWorker

/** Metrics for monitoring [[CommandWorker]] health. */
final case class CommandWorkerMetrics(
    /** Total commands processed (success + failure + skipped). */
    commandsProcessed: Long,
    /** Commands that succeeded. */
    commandsSucceeded: Long,
    /** Commands that failed (after all retries). */
    commandsFailed: Long,
    /** Commands that were skipped (duplicates). */
    commandsSkipped: Long,
    /** Number of poll cycles completed. */
    pollCount: Long,
    /** Number of claims released from dead workers. */
    claimsReleased: Long,
)

object CommandWorker:

  /** Create and start a command worker.
    *
    * The worker is automatically stopped when the Scope closes.
    *
    * @param config
    *   Worker configuration
    * @param store
    *   The command store to poll
    * @param executor
    *   Function to execute commands. Return [[CommandResult.Success]], [[CommandResult.Failure]], or
    *   [[CommandResult.AlreadyExecuted]]
    * @return
    *   A running worker instance
    */
  def make[Id, Cmd](
      config: CommandWorkerConfig,
      store: CommandStore[Id, Cmd],
      executor: Cmd => ZIO[Any, Throwable, CommandResult],
  ): ZIO[Scope, Nothing, CommandWorker[Id, Cmd]] =
    for
      runningRef      <- Ref.make(true)
      metricsRef      <- Ref.make(CommandWorkerMetrics(0, 0, 0, 0, 0, 0))
      shutdownPromise <- Promise.make[Nothing, Unit]

      worker = new CommandWorkerImpl(
        config,
        store,
        executor,
        runningRef,
        metricsRef,
        shutdownPromise,
      )

      // Start the main processing fiber
      processingFiber <- worker.runLoop.fork

      // Start the claim cleanup fiber
      cleanupFiber <- worker.runClaimCleanup.fork

      // Register cleanup on scope close
      _ <- ZIO.addFinalizer {
        for
          _ <- worker.stop
          _ <- processingFiber.join.ignore
          _ <- cleanupFiber.join.ignore
        yield ()
      }
    yield worker
end CommandWorker

private final class CommandWorkerImpl[Id, Cmd](
    config: CommandWorkerConfig,
    store: CommandStore[Id, Cmd],
    executor: Cmd => ZIO[Any, Throwable, CommandResult],
    runningRef: Ref[Boolean],
    metricsRef: Ref[CommandWorkerMetrics],
    shutdownPromise: Promise[Nothing, Unit],
) extends CommandWorker[Id, Cmd]:

  override def stop: UIO[Unit] =
    runningRef.set(false) *> shutdownPromise.succeed(()).unit

  override def isRunning: UIO[Boolean] =
    runningRef.get

  override def awaitShutdown: UIO[Unit] =
    shutdownPromise.await

  override def metrics: UIO[CommandWorkerMetrics] =
    metricsRef.get

  /** Main processing loop. */
  private[command] def runLoop: ZIO[Any, Nothing, Unit] =
    def loop: ZIO[Any, Nothing, Unit] =
      for
        running <- runningRef.get
        _       <-
          if !running then ZIO.unit
          else
            for
              processed <- processBatch.catchAll { error =>
                ZIO.logError(s"Error processing batch: ${error.getMessage}").as(0)
              }
              _ <- metricsRef.update(m => m.copy(pollCount = m.pollCount + 1))
              _ <- waitWithJitter(processed == 0)
              _ <- loop
            yield ()
      yield ()

    loop
  end runLoop

  /** Process a batch of commands. */
  private def processBatch: ZIO[Any, Throwable, Int] =
    for
      now      <- Clock.instant
      commands <- store.claim(config.nodeId, config.batchSize, config.claimDuration, now)
      _        <- ZIO.foreachParDiscard(commands)(processCommand)
    yield commands.size

  /** Process a single command. */
  private def processCommand(cmd: PendingCommand[Id, Cmd]): ZIO[Any, Nothing, Unit] =
    val process = for
      result <- executor(cmd.command).catchAll { error =>
        ZIO.succeed(CommandResult.Failure(error.getMessage, retryable = true))
      }
      now <- Clock.instant
      _   <- result match
        case CommandResult.Success =>
          store.complete(cmd.id) *>
            metricsRef.update(m =>
              m.copy(
                commandsProcessed = m.commandsProcessed + 1,
                commandsSucceeded = m.commandsSucceeded + 1,
              )
            )
        case CommandResult.Failure(error, retryable) =>
          handleFailure(cmd, error, retryable, now)
        case CommandResult.AlreadyExecuted =>
          store.skip(cmd.id, "Already executed") *>
            metricsRef.update(m =>
              m.copy(
                commandsProcessed = m.commandsProcessed + 1,
                commandsSkipped = m.commandsSkipped + 1,
              )
            )
    yield ()

    process.catchAll { error =>
      ZIO.logError(s"Error processing command ${cmd.id}: ${error.getMessage}")
    }
  end processCommand

  /** Handle a failed command execution. */
  private def handleFailure(
      cmd: PendingCommand[Id, Cmd],
      error: String,
      retryable: Boolean,
      now: Instant,
  ): ZIO[Any, Throwable, Unit] =
    val nextAttempt = cmd.attempts + 1
    val retryAt     =
      if retryable then config.retryPolicy.nextRetry(nextAttempt, now)
      else None

    for
      _ <- store.fail(cmd.id, error, retryAt)
      _ <-
        if retryAt.isEmpty then
          // Permanently failed
          metricsRef.update(m =>
            m.copy(
              commandsProcessed = m.commandsProcessed + 1,
              commandsFailed = m.commandsFailed + 1,
            )
          )
        else ZIO.unit // Will be retried
    yield ()
    end for
  end handleFailure

  /** Wait with jitter before next poll. */
  private def waitWithJitter(@unused wasEmpty: Boolean): ZIO[Any, Nothing, Unit] =
    val baseWait = config.pollInterval
    val jitter   = if config.jitterFactor > 0 then
      val jitterMs = (baseWait.toMillis * config.jitterFactor * scala.util.Random.nextDouble()).toLong
      Duration.fromMillis(jitterMs)
    else Duration.Zero

    ZIO.sleep(baseWait + jitter)

  /** Periodically release expired claims from dead workers. */
  private[command] def runClaimCleanup: ZIO[Any, Nothing, Unit] =
    def loop: ZIO[Any, Nothing, Unit] =
      for
        running <- runningRef.get
        _       <-
          if !running then ZIO.unit
          else
            for
              now      <- Clock.instant
              released <- store.releaseExpiredClaims(now).catchAll { error =>
                ZIO.logError(s"Error releasing claims: ${error.getMessage}").as(0)
              }
              _ <-
                if released > 0 then metricsRef.update(m => m.copy(claimsReleased = m.claimsReleased + released))
                else ZIO.unit
              _ <- ZIO.sleep(config.claimCleanupInterval)
              _ <- loop
            yield ()
      yield ()

    loop
  end runClaimCleanup
end CommandWorkerImpl
