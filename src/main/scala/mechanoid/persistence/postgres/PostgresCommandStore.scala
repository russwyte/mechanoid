package mechanoid.persistence.postgres

import saferis.*
import zio.*
import zio.json.*
import mechanoid.persistence.command.*
import java.time.Instant
import scala.annotation.unused

/** Codec for serializing/deserializing commands to JSON.
  *
  * The simplest way to create one is using the companion object's `fromJson` method with a zio-json codec:
  *
  * {{{
  * enum MyCommand derives JsonCodec:
  *   case SendEmail(to: String, subject: String)
  *   case ChargePayment(amount: BigDecimal)
  *
  * val codec = CommandCodec.fromJson[MyCommand]
  * }}}
  */
trait CommandCodec[Cmd]:
  def encode(cmd: Cmd): String
  def decode(json: String): Either[Throwable, Cmd]

object CommandCodec:
  /** Creates a CommandCodec from a zio-json codec. */
  def fromJson[Cmd](using codec: JsonCodec[Cmd]): CommandCodec[Cmd] = new CommandCodec[Cmd]:
    def encode(cmd: Cmd): String                     = cmd.toJson
    def decode(json: String): Either[Throwable, Cmd] =
      json.fromJson[Cmd].left.map(err => new RuntimeException(err))

/** Helper case class for status count query. */
private[postgres] case class StatusCount(status: String, count: Long) derives Table

/** PostgreSQL implementation of CommandStore using Saferis.
  *
  * Uses FOR UPDATE SKIP LOCKED for efficient concurrent claim processing.
  *
  * @param transactor
  *   The Saferis transactor for database operations
  * @param codec
  *   Codec for serializing/deserializing commands
  */
class PostgresCommandStore[Cmd](
    transactor: Transactor,
    codec: CommandCodec[Cmd],
) extends CommandStore[String, Cmd]:

  @unused private val commands = Table[CommandRow]

  override def enqueue(
      instanceId: String,
      command: Cmd,
      idempotencyKey: String,
  ): ZIO[Any, Throwable, PendingCommand[String, Cmd]] =
    for
      now <- Clock.instant
      commandJson = codec.encode(command)

      // Try insert, on conflict return existing
      result <- transactor.run {
        sql"""
          INSERT INTO commands (instance_id, command_data, idempotency_key, enqueued_at, status, attempts)
          VALUES ($instanceId, $commandJson::jsonb, $idempotencyKey, $now, 'pending', 0)
          ON CONFLICT (idempotency_key) DO NOTHING
          RETURNING id, instance_id, command_data, idempotency_key, enqueued_at, status, attempts,
                    last_attempt_at, last_error, next_retry_at, claimed_by, claimed_until
        """.queryOne[CommandRow]
      }

      cmd <- result match
        case Some(row) => rowToPendingCommand(row)
        case None      =>
          // Conflict - fetch existing
          transactor
            .run {
              sql"""
              SELECT id, instance_id, command_data, idempotency_key, enqueued_at, status, attempts,
                     last_attempt_at, last_error, next_retry_at, claimed_by, claimed_until
              FROM commands
              WHERE idempotency_key = $idempotencyKey
            """.queryOne[CommandRow]
            }
            .flatMap {
              case Some(row) => rowToPendingCommand(row)
              case None      => ZIO.fail(new RuntimeException("Command not found after conflict"))
            }
    yield cmd

  override def claim(
      nodeId: String,
      limit: Int,
      claimDuration: Duration,
      now: Instant,
  ): ZIO[Any, Throwable, List[PendingCommand[String, Cmd]]] =
    val claimedUntil = now.plusMillis(claimDuration.toMillis)
    transactor
      .run {
        sql"""
        WITH to_claim AS (
          SELECT id FROM commands
          WHERE status = 'pending'
            AND (next_retry_at IS NULL OR next_retry_at <= $now)
            AND (claimed_by IS NULL OR claimed_until < $now)
          ORDER BY next_retry_at NULLS FIRST, enqueued_at ASC
          LIMIT $limit
          FOR UPDATE SKIP LOCKED
        )
        UPDATE commands
        SET claimed_by = $nodeId,
            claimed_until = $claimedUntil,
            status = 'processing',
            attempts = attempts + 1,
            last_attempt_at = $now
        FROM to_claim
        WHERE commands.id = to_claim.id
        RETURNING commands.id, commands.instance_id, commands.command_data,
                  commands.idempotency_key, commands.enqueued_at, commands.status, commands.attempts,
                  commands.last_attempt_at, commands.last_error, commands.next_retry_at,
                  commands.claimed_by, commands.claimed_until
      """.query[CommandRow]
      }
      .flatMap(rows => ZIO.foreach(rows.toList)(rowToPendingCommand))
  end claim

  override def complete(commandId: Long): ZIO[Any, Throwable, Boolean] =
    transactor
      .run {
        sql"""
        UPDATE commands
        SET status = 'completed', claimed_by = NULL, claimed_until = NULL
        WHERE id = $commandId AND status = 'processing'
      """.dml
      }
      .map(_ > 0)

  override def fail(
      commandId: Long,
      error: String,
      retryAt: Option[Instant],
  ): ZIO[Any, Throwable, Boolean] =
    val newStatus = if retryAt.isDefined then "pending" else "failed"
    transactor
      .run {
        sql"""
        UPDATE commands
        SET status = $newStatus,
            last_error = $error,
            next_retry_at = $retryAt,
            claimed_by = NULL,
            claimed_until = NULL
        WHERE id = $commandId AND status = 'processing'
      """.dml
      }
      .map(_ > 0)
  end fail

  override def skip(commandId: Long, reason: String): ZIO[Any, Throwable, Boolean] =
    transactor
      .run {
        sql"""
        UPDATE commands
        SET status = 'skipped', last_error = $reason, claimed_by = NULL, claimed_until = NULL
        WHERE id = $commandId AND status = 'processing'
      """.dml
      }
      .map(_ > 0)

  override def getByIdempotencyKey(idempotencyKey: String): ZIO[Any, Throwable, Option[PendingCommand[String, Cmd]]] =
    transactor
      .run {
        sql"""
        SELECT id, instance_id, command_data, idempotency_key, enqueued_at, status, attempts,
               last_attempt_at, last_error, next_retry_at, claimed_by, claimed_until
        FROM commands
        WHERE idempotency_key = $idempotencyKey
      """.queryOne[CommandRow]
      }
      .flatMap(ZIO.foreach(_)(rowToPendingCommand))

  override def getByInstanceId(instanceId: String): ZIO[Any, Throwable, List[PendingCommand[String, Cmd]]] =
    transactor
      .run {
        sql"""
        SELECT id, instance_id, command_data, idempotency_key, enqueued_at, status, attempts,
               last_attempt_at, last_error, next_retry_at, claimed_by, claimed_until
        FROM commands
        WHERE instance_id = $instanceId
        ORDER BY enqueued_at ASC
      """.query[CommandRow]
      }
      .flatMap(rows => ZIO.foreach(rows.toList)(rowToPendingCommand))

  override def countByStatus: ZIO[Any, Throwable, Map[CommandStatus, Long]] =
    transactor
      .run {
        sql"""
        SELECT status, COUNT(*) as count
        FROM commands
        GROUP BY status
      """.query[StatusCount]
      }
      .map { rows =>
        rows.flatMap { row =>
          parseStatus(row.status).map(_ -> row.count)
        }.toMap
      }

  override def releaseExpiredClaims(now: Instant): ZIO[Any, Throwable, Int] =
    transactor.run {
      sql"""
        UPDATE commands
        SET status = 'pending', claimed_by = NULL, claimed_until = NULL
        WHERE status = 'processing' AND claimed_until < $now
      """.dml
    }

  private def rowToPendingCommand(row: CommandRow): ZIO[Any, Throwable, PendingCommand[String, Cmd]] =
    ZIO
      .fromEither(codec.decode(row.commandData))
      .mapError(e => new RuntimeException(s"Failed to decode command: ${e.getMessage}", e))
      .flatMap { cmd =>
        ZIO
          .fromOption(parseStatus(row.status))
          .orElseFail(new RuntimeException(s"Unknown command status: ${row.status}"))
          .map { status =>
            PendingCommand(
              id = row.id,
              instanceId = row.instanceId,
              command = cmd,
              idempotencyKey = row.idempotencyKey,
              enqueuedAt = row.enqueuedAt,
              status = status,
              attempts = row.attempts,
              lastAttemptAt = row.lastAttemptAt,
              lastError = row.lastError,
              nextRetryAt = row.nextRetryAt,
            )
          }
      }

  private def parseStatus(s: String): Option[CommandStatus] =
    s.toLowerCase match
      case "pending"    => Some(CommandStatus.Pending)
      case "processing" => Some(CommandStatus.Processing)
      case "completed"  => Some(CommandStatus.Completed)
      case "failed"     => Some(CommandStatus.Failed)
      case "skipped"    => Some(CommandStatus.Skipped)
      case _            => None
end PostgresCommandStore

object PostgresCommandStore:
  def layer[Cmd: Tag](codec: CommandCodec[Cmd]): ZLayer[Transactor, Nothing, CommandStore[String, Cmd]] =
    ZLayer.fromFunction((xa: Transactor) => new PostgresCommandStore[Cmd](xa, codec))
