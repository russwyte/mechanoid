package mechanoid.persistence.postgres

import saferis.*
import zio.*
import mechanoid.persistence.timeout.*
import java.time.Instant
import scala.annotation.unused

/** PostgreSQL implementation of LeaseStore using Saferis.
  *
  * Uses atomic INSERT ... ON CONFLICT for lease acquisition to ensure exactly-one-leader semantics in distributed
  * environments.
  */
class PostgresLeaseStore(transactor: Transactor) extends LeaseStore:

  @unused private val leases = Table[LeaseRow]

  override def tryAcquire(
      key: String,
      holder: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, Throwable, Option[Lease]] =
    val expiresAt = now.plusMillis(duration.toMillis)
    transactor
      .run {
        sql"""
        INSERT INTO leases (key, holder, expires_at, acquired_at)
        VALUES ($key, $holder, $expiresAt, $now)
        ON CONFLICT (key) DO UPDATE SET
          holder = EXCLUDED.holder,
          expires_at = EXCLUDED.expires_at,
          acquired_at = EXCLUDED.acquired_at
        WHERE leases.expires_at < $now
           OR leases.holder = EXCLUDED.holder
        RETURNING key, holder, expires_at, acquired_at
      """.queryOne[LeaseRow]
      }
      .map(_.map(rowToLease))
  end tryAcquire

  override def renew(
      key: String,
      holder: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, Throwable, Boolean] =
    val newExpiry = now.plusMillis(duration.toMillis)
    transactor
      .run {
        sql"""
        UPDATE leases
        SET expires_at = $newExpiry
        WHERE key = $key
          AND holder = $holder
          AND expires_at > $now
      """.dml
      }
      .map(_ > 0)
  end renew

  override def release(key: String, holder: String): ZIO[Any, Throwable, Boolean] =
    transactor
      .run {
        sql"""
        DELETE FROM leases
        WHERE key = $key
          AND holder = $holder
      """.dml
      }
      .map(_ > 0)

  override def get(key: String): ZIO[Any, Throwable, Option[Lease]] =
    transactor
      .run {
        sql"""
        SELECT key, holder, expires_at, acquired_at
        FROM leases
        WHERE key = $key
      """.queryOne[LeaseRow]
      }
      .map(_.map(rowToLease))

  private def rowToLease(row: LeaseRow): Lease =
    Lease(
      key = row.key,
      holder = row.holder,
      expiresAt = row.expiresAt,
      acquiredAt = row.acquiredAt,
    )
end PostgresLeaseStore

object PostgresLeaseStore:
  val layer: ZLayer[Transactor, Nothing, LeaseStore] =
    ZLayer.fromFunction(new PostgresLeaseStore(_))
