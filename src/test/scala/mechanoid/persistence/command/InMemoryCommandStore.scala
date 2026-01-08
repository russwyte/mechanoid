package mechanoid.persistence.command

import zio.*
import java.time.Instant
import scala.collection.mutable

/** In-memory implementation of [[CommandStore]] for testing.
  *
  * Thread-safe via synchronized blocks. Not suitable for production.
  */
class InMemoryCommandStore[Id, Cmd] extends CommandStore[Id, Cmd]:

  private val commands         = mutable.Map.empty[Long, PendingCommand[Id, Cmd]]
  private val byIdempotencyKey = mutable.Map.empty[String, Long]
  private val claims           = mutable.Map.empty[Long, (String, Instant)] // commandId -> (nodeId, expiresAt)
  private var nextId           = 1L

  override def enqueue(
      instanceId: Id,
      command: Cmd,
      idempotencyKey: String,
  ): ZIO[Any, Throwable, PendingCommand[Id, Cmd]] =
    Clock.instant.map { now =>
      synchronized {
        byIdempotencyKey.get(idempotencyKey) match
          case Some(existingId) =>
            commands(existingId)
          case None =>
            val id = nextId
            nextId += 1
            val pending = PendingCommand(
              id = id,
              instanceId = instanceId,
              command = command,
              idempotencyKey = idempotencyKey,
              enqueuedAt = now,
              status = CommandStatus.Pending,
              attempts = 0,
              lastAttemptAt = None,
              lastError = None,
              nextRetryAt = None,
            )
            commands(id) = pending
            byIdempotencyKey(idempotencyKey) = id
            pending
      }
    }

  override def claim(
      nodeId: String,
      limit: Int,
      claimDuration: Duration,
      now: Instant,
  ): ZIO[Any, Throwable, List[PendingCommand[Id, Cmd]]] =
    ZIO.succeed {
      synchronized {
        val expiresAt = now.plusMillis(claimDuration.toMillis)

        // Find pending commands that are ready and not claimed
        val toClaim = commands.values
          .filter { cmd =>
            cmd.status == CommandStatus.Pending &&
            cmd.isReady(now) &&
            !claims.get(cmd.id).exists { case (_, until) => now.isBefore(until) }
          }
          .take(limit)
          .toList

        // Claim them
        toClaim.foreach { cmd =>
          claims(cmd.id) = (nodeId, expiresAt)
          commands(cmd.id) = cmd.copy(
            status = CommandStatus.Processing,
            attempts = cmd.attempts + 1,
            lastAttemptAt = Some(now),
          )
        }

        toClaim.map(cmd => commands(cmd.id))
      }
    }

  override def complete(commandId: Long): ZIO[Any, Throwable, Boolean] =
    ZIO.succeed {
      synchronized {
        commands.get(commandId) match
          case Some(cmd) if cmd.status == CommandStatus.Processing =>
            commands(commandId) = cmd.copy(status = CommandStatus.Completed)
            claims.remove(commandId)
            true
          case _ => false
      }
    }

  override def fail(
      commandId: Long,
      error: String,
      retryAt: Option[Instant],
  ): ZIO[Any, Throwable, Boolean] =
    ZIO.succeed {
      synchronized {
        commands.get(commandId) match
          case Some(cmd) if cmd.status == CommandStatus.Processing =>
            val newStatus =
              if retryAt.isDefined then CommandStatus.Pending
              else CommandStatus.Failed
            commands(commandId) = cmd.copy(
              status = newStatus,
              lastError = Some(error),
              nextRetryAt = retryAt,
            )
            claims.remove(commandId)
            true
          case _ => false
      }
    }

  override def skip(commandId: Long, reason: String): ZIO[Any, Throwable, Boolean] =
    ZIO.succeed {
      synchronized {
        commands.get(commandId) match
          case Some(cmd) if cmd.status == CommandStatus.Processing =>
            commands(commandId) = cmd.copy(
              status = CommandStatus.Skipped,
              lastError = Some(reason),
            )
            claims.remove(commandId)
            true
          case _ => false
      }
    }

  override def getByIdempotencyKey(
      idempotencyKey: String
  ): ZIO[Any, Throwable, Option[PendingCommand[Id, Cmd]]] =
    ZIO.succeed {
      synchronized {
        byIdempotencyKey.get(idempotencyKey).flatMap(commands.get)
      }
    }

  override def getByInstanceId(instanceId: Id): ZIO[Any, Throwable, List[PendingCommand[Id, Cmd]]] =
    ZIO.succeed {
      synchronized {
        commands.values
          .filter(_.instanceId == instanceId)
          .toList
          .sortBy(_.enqueuedAt)
      }
    }

  override def countByStatus: ZIO[Any, Throwable, Map[CommandStatus, Long]] =
    ZIO.succeed {
      synchronized {
        commands.values
          .groupBy(_.status)
          .map { case (status, cmds) => status -> cmds.size.toLong }
          .toMap
      }
    }

  override def releaseExpiredClaims(now: Instant): ZIO[Any, Throwable, Int] =
    ZIO.succeed {
      synchronized {
        val expired = claims.filter { case (_, (_, until)) => !now.isBefore(until) }
        expired.foreach { case (cmdId, _) =>
          commands.get(cmdId).foreach { cmd =>
            if cmd.status == CommandStatus.Processing then commands(cmdId) = cmd.copy(status = CommandStatus.Pending)
          }
          claims.remove(cmdId)
        }
        expired.size
      }
    }

  // Test helpers

  /** Get all commands (for testing). */
  def all: List[PendingCommand[Id, Cmd]] = synchronized {
    commands.values.toList.sortBy(_.id)
  }

  /** Clear all commands (for testing). */
  def clear(): Unit = synchronized {
    commands.clear()
    byIdempotencyKey.clear()
    claims.clear()
    nextId = 1L
  }

  /** Get command by ID (for testing). Returns ZIO for use in for-comprehensions. */
  def findById(id: Long): ZIO[Any, Nothing, Option[PendingCommand[Id, Cmd]]] =
    ZIO.succeed {
      synchronized {
        commands.get(id)
      }
    }
end InMemoryCommandStore
