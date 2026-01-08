package mechanoid.persistence.command

import java.time.Instant

/** A command that has been enqueued for execution.
  *
  * Commands represent side effects that should be executed exactly once. They are persisted atomically with FSM events,
  * then picked up by a [[CommandWorker]] for asynchronous execution.
  *
  * @tparam Id
  *   The FSM instance identifier type
  * @tparam Cmd
  *   The command type (typically an enum of possible side effects)
  *
  * @param id
  *   Unique identifier for this command instance
  * @param instanceId
  *   The FSM instance that enqueued this command
  * @param command
  *   The actual command payload
  * @param idempotencyKey
  *   A key to prevent duplicate execution (e.g., "charge-order-123")
  * @param enqueuedAt
  *   When the command was enqueued
  * @param status
  *   Current status of the command
  * @param attempts
  *   Number of execution attempts so far
  * @param lastAttemptAt
  *   When the last execution attempt occurred
  * @param lastError
  *   Error message from the last failed attempt, if any
  * @param nextRetryAt
  *   When to retry after a failure, if scheduled
  */
final case class PendingCommand[Id, Cmd](
    id: Long,
    instanceId: Id,
    command: Cmd,
    idempotencyKey: String,
    enqueuedAt: Instant,
    status: CommandStatus,
    attempts: Int,
    lastAttemptAt: Option[Instant],
    lastError: Option[String],
    nextRetryAt: Option[Instant],
):
  /** Check if this command is ready for execution. */
  def isReady(now: Instant): Boolean =
    status == CommandStatus.Pending &&
      nextRetryAt.forall(retry => !now.isBefore(retry))

  /** Check if this command has exceeded max attempts. */
  def hasExceededAttempts(maxAttempts: Int): Boolean =
    attempts >= maxAttempts
end PendingCommand

/** Status of a command in the queue. */
enum CommandStatus:
  /** Waiting to be executed. */
  case Pending

  /** Currently being processed by a worker. */
  case Processing

  /** Successfully executed. */
  case Completed

  /** Failed after exhausting retries. */
  case Failed

  /** Skipped (e.g., duplicate detected). */
  case Skipped
end CommandStatus

/** Result of executing a command. */
sealed trait CommandResult

object CommandResult:
  /** Command executed successfully. */
  case object Success extends CommandResult

  /** Command failed, may be retried. */
  final case class Failure(error: String, retryable: Boolean) extends CommandResult

  /** Command was already executed (idempotency check). */
  case object AlreadyExecuted extends CommandResult
