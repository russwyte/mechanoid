package mechanoid.persistence.postgres

import saferis.*
import zio.*
import mechanoid.core.{MechanoidError, PersistenceError}
import mechanoid.persistence.timeout.*
import java.time.Instant
import scala.annotation.unused

/** PostgreSQL implementation of TimeoutStore using Saferis.
  *
  * This implementation uses atomic UPDATE ... RETURNING for claim operations to ensure exactly-once timeout processing
  * in distributed environments.
  */
class PostgresTimeoutStore(transactor: Transactor) extends TimeoutStore[String]:

  @unused private val timeouts = Table[TimeoutRow]

  override def schedule(
      instanceId: String,
      stateHash: Int,
      sequenceNr: Long,
      deadline: Instant,
  ): ZIO[Any, MechanoidError, ScheduledTimeout[String]] =
    (for
      now <- Clock.instant
      _   <- transactor.run {
        sql"""
          INSERT INTO scheduled_timeouts (instance_id, state_hash, sequence_nr, deadline, created_at)
          VALUES ($instanceId, $stateHash, $sequenceNr, $deadline, $now)
          ON CONFLICT (instance_id) DO UPDATE SET
            state_hash = EXCLUDED.state_hash,
            sequence_nr = EXCLUDED.sequence_nr,
            deadline = EXCLUDED.deadline,
            claimed_by = NULL,
            claimed_until = NULL
        """.dml
      }
    yield ScheduledTimeout(instanceId, stateHash, sequenceNr, deadline, now, None, None)).mapError(PersistenceError(_))

  override def cancel(instanceId: String): ZIO[Any, MechanoidError, Boolean] =
    transactor
      .run {
        sql"""
        DELETE FROM scheduled_timeouts
        WHERE instance_id = $instanceId
      """.dml
      }
      .map(_ > 0)
      .mapError(PersistenceError(_))

  override def queryExpired(limit: Int, now: Instant): ZIO[Any, MechanoidError, List[ScheduledTimeout[String]]] =
    transactor
      .run {
        sql"""
        SELECT instance_id, state_hash, sequence_nr, deadline, created_at, claimed_by, claimed_until
        FROM scheduled_timeouts
        WHERE deadline <= $now
          AND (claimed_by IS NULL OR claimed_until < $now)
        ORDER BY deadline ASC
        LIMIT $limit
      """.query[TimeoutRow]
      }
      .map(_.map(rowToTimeout).toList)
      .mapError(PersistenceError(_))

  override def claim(
      instanceId: String,
      nodeId: String,
      claimDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, ClaimResult] =
    val claimedUntil = now.plusMillis(claimDuration.toMillis)
    transactor
      .run {
        sql"""
        UPDATE scheduled_timeouts
        SET claimed_by = $nodeId, claimed_until = $claimedUntil
        WHERE instance_id = $instanceId
          AND (claimed_by IS NULL OR claimed_until < $now)
        RETURNING instance_id, state_hash, sequence_nr, deadline, created_at, claimed_by, claimed_until
      """.queryOne[TimeoutRow]
      }
      .flatMap {
        case Some(row) =>
          ZIO.succeed(ClaimResult.Claimed(rowToTimeout(row)))
        case None =>
          // Check if it exists but is claimed, or doesn't exist
          get(instanceId).map {
            case Some(timeout) if timeout.isClaimed(now) =>
              ClaimResult.AlreadyClaimed(timeout.claimedBy.getOrElse("unknown"), timeout.claimedUntil.getOrElse(now))
            case Some(_) =>
              // Exists but not claimed - race condition, treat as already claimed
              ClaimResult.AlreadyClaimed("unknown", now)
            case None =>
              ClaimResult.NotFound
          }
      }
      .mapError {
        case e: MechanoidError => e
        case e: Throwable      => PersistenceError(e)
      }
  end claim

  override def complete(instanceId: String, sequenceNr: Long): ZIO[Any, MechanoidError, Boolean] =
    transactor
      .run {
        sql"""
        DELETE FROM scheduled_timeouts
        WHERE instance_id = $instanceId
          AND sequence_nr = $sequenceNr
      """.dml
      }
      .map(_ > 0)
      .mapError(PersistenceError(_))

  override def release(instanceId: String): ZIO[Any, MechanoidError, Boolean] =
    transactor
      .run {
        sql"""
        UPDATE scheduled_timeouts
        SET claimed_by = NULL, claimed_until = NULL
        WHERE instance_id = $instanceId
      """.dml
      }
      .map(_ > 0)
      .mapError(PersistenceError(_))

  override def get(instanceId: String): ZIO[Any, MechanoidError, Option[ScheduledTimeout[String]]] =
    transactor
      .run {
        sql"""
        SELECT instance_id, state_hash, sequence_nr, deadline, created_at, claimed_by, claimed_until
        FROM scheduled_timeouts
        WHERE instance_id = $instanceId
      """.queryOne[TimeoutRow]
      }
      .map(_.map(rowToTimeout))
      .mapError(PersistenceError(_))

  private def rowToTimeout(row: TimeoutRow): ScheduledTimeout[String] =
    ScheduledTimeout(
      instanceId = row.instanceId,
      stateHash = row.stateHash,
      sequenceNr = row.sequenceNr,
      deadline = row.deadline,
      createdAt = row.createdAt,
      claimedBy = row.claimedBy,
      claimedUntil = row.claimedUntil,
    )
end PostgresTimeoutStore

object PostgresTimeoutStore:
  val layer: ZLayer[Transactor, Nothing, TimeoutStore[String]] =
    ZLayer.fromFunction(new PostgresTimeoutStore(_))
