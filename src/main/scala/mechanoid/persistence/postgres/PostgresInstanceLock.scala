package mechanoid.persistence.postgres

import saferis.*
import zio.*
import mechanoid.core.{MechanoidError, PersistenceError}
import mechanoid.persistence.lock.*
import java.time.Instant
import scala.annotation.unused

/** PostgreSQL implementation of FSMInstanceLock using Saferis.
  *
  * Uses atomic INSERT ... ON CONFLICT to implement lease-based locking with automatic expiration for crash recovery.
  */
class PostgresInstanceLock(transactor: Transactor) extends FSMInstanceLock[String]:

  @unused private val locks = Table[LockRow]

  override def tryAcquire(
      instanceId: String,
      nodeId: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, LockResult[String]] =
    val expiresAt = now.plusMillis(duration.toMillis)
    transactor
      .run {
        sql"""
        INSERT INTO fsm_instance_locks (instance_id, node_id, acquired_at, expires_at)
        VALUES ($instanceId, $nodeId, $now, $expiresAt)
        ON CONFLICT (instance_id) DO UPDATE SET
          node_id = EXCLUDED.node_id,
          acquired_at = EXCLUDED.acquired_at,
          expires_at = EXCLUDED.expires_at
        WHERE fsm_instance_locks.expires_at < $now
           OR fsm_instance_locks.node_id = EXCLUDED.node_id
        RETURNING instance_id, node_id, acquired_at, expires_at
      """.queryOne[LockRow]
      }
      .flatMap {
        case Some(row) =>
          ZIO.succeed[LockResult[String]](
            LockResult.Acquired(LockToken(instanceId, row.nodeId, row.acquiredAt, row.expiresAt))
          )
        case None =>
          // Lock held by another node - get current holder info
          transactor
            .run {
              sql"""
            SELECT instance_id, node_id, acquired_at, expires_at
            FROM fsm_instance_locks
            WHERE instance_id = $instanceId
          """.queryOne[LockRow]
            }
            .map {
              case Some(row) => LockResult.Busy[String](row.nodeId, row.expiresAt)
              case None      => LockResult.Busy[String]("unknown", now) // Shouldn't happen
            }
      }
      .mapError(PersistenceError(_))
  end tryAcquire

  override def acquire(
      instanceId: String,
      nodeId: String,
      duration: Duration,
      timeout: Duration,
  ): ZIO[Any, MechanoidError, LockResult[String]] =
    val deadline = java.time.Instant.now().plusMillis(timeout.toMillis)

    def attempt: ZIO[Any, MechanoidError, LockResult[String]] =
      for
        now         <- Clock.instant
        _           <- ZIO.when(now.isAfter(deadline))(ZIO.succeed(LockResult.TimedOut[String]()))
        result      <- tryAcquire(instanceId, nodeId, duration, now)
        finalResult <- result match
          case acquired: LockResult.Acquired[String] => ZIO.succeed(acquired)
          case LockResult.Busy(_, until)             =>
            if now.isAfter(deadline) then ZIO.succeed(LockResult.TimedOut[String]())
            else
              val waitTime = java.time.Duration.between(now, until).toMillis.min(100L).max(10L)
              ZIO.sleep(Duration.fromMillis(waitTime)) *> attempt
          case timedOut: LockResult.TimedOut[String] => ZIO.succeed(timedOut)
      yield finalResult

    attempt
  end acquire

  override def release(token: LockToken[String]): ZIO[Any, MechanoidError, Boolean] =
    transactor
      .run {
        sql"""
        DELETE FROM fsm_instance_locks
        WHERE instance_id = ${token.instanceId}
          AND node_id = ${token.nodeId}
      """.dml
      }
      .map(_ > 0)
      .mapError(PersistenceError(_))

  override def extend(
      token: LockToken[String],
      additionalDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, Option[LockToken[String]]] =
    val newExpiry = now.plusMillis(additionalDuration.toMillis)
    transactor
      .run {
        sql"""
        UPDATE fsm_instance_locks
        SET expires_at = $newExpiry
        WHERE instance_id = ${token.instanceId}
          AND node_id = ${token.nodeId}
          AND expires_at > $now
        RETURNING instance_id, node_id, acquired_at, expires_at
      """.queryOne[LockRow]
      }
      .map(_.map(row => LockToken(token.instanceId, row.nodeId, row.acquiredAt, row.expiresAt)))
      .mapError(PersistenceError(_))
  end extend

  override def get(instanceId: String, now: Instant): ZIO[Any, MechanoidError, Option[LockToken[String]]] =
    transactor
      .run {
        sql"""
        SELECT instance_id, node_id, acquired_at, expires_at
        FROM fsm_instance_locks
        WHERE instance_id = $instanceId
          AND expires_at > $now
      """.queryOne[LockRow]
      }
      .map(_.map(row => LockToken(instanceId, row.nodeId, row.acquiredAt, row.expiresAt)))
      .mapError(PersistenceError(_))
end PostgresInstanceLock

object PostgresInstanceLock:
  val layer: ZLayer[Transactor, Nothing, FSMInstanceLock[String]] =
    ZLayer.fromFunction(new PostgresInstanceLock(_))
