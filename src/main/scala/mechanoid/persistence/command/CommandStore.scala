package mechanoid.persistence.command

import zio.*
import java.time.Instant

/** Storage backend for the command queue (transactional outbox pattern).
  *
  * Implement this trait using your database of choice (PostgreSQL, DynamoDB, etc.) to enable exactly-once side effect
  * execution with your FSM.
  *
  * ==How It Works==
  *
  *   1. FSM entry actions call [[enqueue]] to persist commands
  *   2. [[CommandWorker]] calls [[claim]] to atomically claim commands for processing
  *   3. After execution, worker calls [[complete]], [[fail]], or [[skip]]
  *   4. Failed commands are retried based on [[CommandWorkerConfig]]
  *
  * ==Idempotency==
  *
  * The `idempotencyKey` parameter ensures each side effect executes at most once:
  *   - If a command with the same key exists and is completed, [[enqueue]] returns the existing command
  *   - Workers should also check external systems using this key when possible
  *
  * ==PostgreSQL Schema==
  *
  * {{{
  * CREATE TABLE commands (
  *   id              BIGSERIAL PRIMARY KEY,
  *   instance_id     TEXT NOT NULL,
  *   command         JSONB NOT NULL,
  *   idempotency_key TEXT NOT NULL UNIQUE,
  *   enqueued_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  *   status          TEXT NOT NULL DEFAULT 'pending',
  *   attempts        INT NOT NULL DEFAULT 0,
  *   last_attempt_at TIMESTAMPTZ,
  *   last_error      TEXT,
  *   next_retry_at   TIMESTAMPTZ,
  *   claimed_by      TEXT,
  *   claimed_until   TIMESTAMPTZ
  * );
  *
  * CREATE INDEX idx_commands_pending ON commands (next_retry_at)
  *   WHERE status = 'pending';
  * CREATE INDEX idx_commands_instance ON commands (instance_id);
  * }}}
  *
  * @tparam Id
  *   The FSM instance identifier type
  * @tparam Cmd
  *   The command type (your side effect enum)
  */
trait CommandStore[Id, Cmd]:

  /** Enqueue a command for later execution.
    *
    * Commands are typically enqueued in FSM entry actions, which don't run during replay, ensuring commands are created
    * exactly once per transition.
    *
    * If a command with the same `idempotencyKey` already exists:
    *   - If completed/skipped: Returns the existing command (no-op)
    *   - If pending/failed: Implementation-dependent (may update or return existing)
    *
    * @param instanceId
    *   The FSM instance enqueueing this command
    * @param command
    *   The command payload
    * @param idempotencyKey
    *   Unique key to prevent duplicates (e.g., "charge-order-123")
    * @return
    *   The enqueued (or existing) command
    */
  def enqueue(
      instanceId: Id,
      command: Cmd,
      idempotencyKey: String,
  ): ZIO[Any, Throwable, PendingCommand[Id, Cmd]]

  /** Claim commands for processing.
    *
    * Atomically marks commands as "processing" and assigns them to a worker. This prevents other workers from
    * processing the same commands.
    *
    * Claims should expire after `claimDuration` to handle worker failures.
    *
    * @param nodeId
    *   The worker node claiming the commands
    * @param limit
    *   Maximum number of commands to claim
    * @param claimDuration
    *   How long the claim is valid
    * @param now
    *   Current time (for testability)
    * @return
    *   Commands that were successfully claimed
    */
  def claim(
      nodeId: String,
      limit: Int,
      claimDuration: Duration,
      now: Instant,
  ): ZIO[Any, Throwable, List[PendingCommand[Id, Cmd]]]

  /** Mark a command as successfully completed.
    *
    * @param commandId
    *   The command's unique identifier
    * @return
    *   true if the command was marked complete, false if not found or already completed
    */
  def complete(commandId: Long): ZIO[Any, Throwable, Boolean]

  /** Mark a command as failed.
    *
    * If `retryAt` is provided, the command will be retried after that time. If `retryAt` is None, the command is marked
    * as permanently failed.
    *
    * @param commandId
    *   The command's unique identifier
    * @param error
    *   Error message describing the failure
    * @param retryAt
    *   When to retry, or None for permanent failure
    * @return
    *   true if the command was updated
    */
  def fail(
      commandId: Long,
      error: String,
      retryAt: Option[Instant],
  ): ZIO[Any, Throwable, Boolean]

  /** Mark a command as skipped (e.g., duplicate detected at execution time).
    *
    * @param commandId
    *   The command's unique identifier
    * @param reason
    *   Why the command was skipped
    * @return
    *   true if the command was marked skipped
    */
  def skip(commandId: Long, reason: String): ZIO[Any, Throwable, Boolean]

  /** Get a command by its idempotency key.
    *
    * Useful for checking if a side effect has already been enqueued/executed.
    *
    * @param idempotencyKey
    *   The unique idempotency key
    * @return
    *   The command if found
    */
  def getByIdempotencyKey(
      idempotencyKey: String
  ): ZIO[Any, Throwable, Option[PendingCommand[Id, Cmd]]]

  /** Get all commands for an FSM instance.
    *
    * Useful for debugging and audit trails.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   All commands for this instance, ordered by enqueue time
    */
  def getByInstanceId(instanceId: Id): ZIO[Any, Throwable, List[PendingCommand[Id, Cmd]]]

  /** Count commands by status.
    *
    * Useful for monitoring dashboards.
    *
    * @return
    *   Map of status to count
    */
  def countByStatus: ZIO[Any, Throwable, Map[CommandStatus, Long]]

  /** Release expired claims.
    *
    * Should be called periodically to recover commands from failed workers. Moves commands from "processing" back to
    * "pending" if their claim expired.
    *
    * @param now
    *   Current time
    * @return
    *   Number of claims released
    */
  def releaseExpiredClaims(now: Instant): ZIO[Any, Throwable, Int]
end CommandStore
