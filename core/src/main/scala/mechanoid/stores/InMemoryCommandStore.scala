package mechanoid.stores

import zio.*
import mechanoid.core.*
import mechanoid.persistence.command.*
import java.time.Instant

/** ZIO Ref-based in-memory CommandStore implementation.
  *
  * Thread-safe via ZIO Refs. Suitable for testing and simple single-process deployments. For distributed deployments,
  * use a database-backed implementation like PostgresCommandStore.
  *
  * ==Usage==
  * {{{
  * // Create directly
  * for
  *   store <- InMemoryCommandStore.make[String, MyCommand]
  *   cmd   <- store.enqueue("instance-1", MyCommand.DoSomething, "idempotency-key")
  * yield cmd
  *
  * // Or as a ZLayer
  * val storeLayer = InMemoryCommandStore.layer[String, MyCommand]
  * myProgram.provide(storeLayer)
  * }}}
  */
final class InMemoryCommandStore[Id, Cmd] private (
    commandsRef: Ref[Map[Long, PendingCommand[Id, Cmd]]],
    idempotencyIndexRef: Ref[Map[String, Long]],
    claimsRef: Ref[Map[Long, (String, Instant)]], // commandId -> (nodeId, expiresAt)
    nextIdRef: Ref[Long],
) extends CommandStore[Id, Cmd]:

  override def enqueue(
      instanceId: Id,
      command: Cmd,
      idempotencyKey: String,
  ): UIO[PendingCommand[Id, Cmd]] =
    for
      now      <- Clock.instant
      existing <- idempotencyIndexRef.get.map(_.get(idempotencyKey))
      result   <- existing match
        case Some(existingId) =>
          commandsRef.get.map(cmds => cmds(existingId))
        case None =>
          for
            id <- nextIdRef.updateAndGet(_ + 1)
            pending = PendingCommand(
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
            _ <- commandsRef.update(_ + (id -> pending))
            _ <- idempotencyIndexRef.update(_ + (idempotencyKey -> id))
          yield pending
    yield result

  override def claim(
      nodeId: String,
      limit: Int,
      claimDuration: Duration,
      now: Instant,
  ): UIO[List[PendingCommand[Id, Cmd]]] =
    val expiresAt = now.plusMillis(claimDuration.toMillis)

    for
      commands <- commandsRef.get
      claims   <- claimsRef.get
      // Find pending commands that are ready and not claimed
      toClaim = commands.values
        .filter { cmd =>
          cmd.status == CommandStatus.Pending &&
          cmd.isReady(now) &&
          !claims.get(cmd.id).exists { case (_, until) => now.isBefore(until) }
        }
        .take(limit)
        .toList
      // Update claims and command states
      _ <- ZIO.foreachDiscard(toClaim) { cmd =>
        claimsRef.update(_ + (cmd.id -> (nodeId, expiresAt))) *>
          commandsRef.update(cmds =>
            cmds + (cmd.id -> cmd.copy(
              status = CommandStatus.Processing,
              attempts = cmd.attempts + 1,
              lastAttemptAt = Some(now),
            ))
          )
      }
      // Return updated commands
      updatedCommands <- commandsRef.get
    yield toClaim.map(cmd => updatedCommands(cmd.id))
    end for
  end claim

  override def complete(commandId: Long): UIO[Boolean] =
    for
      maybeCmd <- commandsRef.get.map(_.get(commandId))
      result   <- maybeCmd match
        case Some(cmd) if cmd.status == CommandStatus.Processing =>
          for
            _ <- commandsRef.update(cmds => cmds + (commandId -> cmd.copy(status = CommandStatus.Completed)))
            _ <- claimsRef.update(_ - commandId)
          yield true
        case _ => ZIO.succeed(false)
    yield result

  override def fail(
      commandId: Long,
      error: String,
      retryAt: Option[Instant],
  ): UIO[Boolean] =
    for
      maybeCmd <- commandsRef.get.map(_.get(commandId))
      result   <- maybeCmd match
        case Some(cmd) if cmd.status == CommandStatus.Processing =>
          val newStatus = if retryAt.isDefined then CommandStatus.Pending else CommandStatus.Failed
          for
            _ <- commandsRef.update(cmds =>
              cmds + (commandId -> cmd.copy(
                status = newStatus,
                lastError = Some(error),
                nextRetryAt = retryAt,
              ))
            )
            _ <- claimsRef.update(_ - commandId)
          yield true
          end for
        case _ => ZIO.succeed(false)
    yield result

  override def skip(commandId: Long, reason: String): UIO[Boolean] =
    for
      maybeCmd <- commandsRef.get.map(_.get(commandId))
      result   <- maybeCmd match
        case Some(cmd) if cmd.status == CommandStatus.Processing =>
          for
            _ <- commandsRef.update(cmds =>
              cmds + (commandId -> cmd.copy(
                status = CommandStatus.Skipped,
                lastError = Some(reason),
              ))
            )
            _ <- claimsRef.update(_ - commandId)
          yield true
        case _ => ZIO.succeed(false)
    yield result

  override def getByIdempotencyKey(
      idempotencyKey: String
  ): UIO[Option[PendingCommand[Id, Cmd]]] =
    for
      maybeId  <- idempotencyIndexRef.get.map(_.get(idempotencyKey))
      commands <- commandsRef.get
    yield maybeId.flatMap(commands.get)

  override def getByInstanceId(instanceId: Id): UIO[List[PendingCommand[Id, Cmd]]] =
    commandsRef.get.map(_.values.filter(_.instanceId == instanceId).toList.sortBy(_.enqueuedAt))

  override def countByStatus: UIO[Map[CommandStatus, Long]] =
    commandsRef.get.map(_.values.groupBy(_.status).map { case (status, cmds) => status -> cmds.size.toLong }.toMap)

  override def releaseExpiredClaims(now: Instant): UIO[Int] =
    for
      claims <- claimsRef.get
      expired = claims.filter { case (_, (_, until)) => !now.isBefore(until) }
      _ <- ZIO.foreachDiscard(expired.keys) { cmdId =>
        commandsRef.update { cmds =>
          cmds.get(cmdId) match
            case Some(cmd) if cmd.status == CommandStatus.Processing =>
              cmds + (cmdId -> cmd.copy(status = CommandStatus.Pending))
            case _ => cmds
        } *> claimsRef.update(_ - cmdId)
      }
    yield expired.size

  /** Get all commands (for testing). */
  def all: UIO[List[PendingCommand[Id, Cmd]]] =
    commandsRef.get.map(_.values.toList.sortBy(_.id))

  /** Clear all data (for testing). */
  def clear: UIO[Unit] =
    commandsRef.set(Map.empty) *>
      idempotencyIndexRef.set(Map.empty) *>
      claimsRef.set(Map.empty) *>
      nextIdRef.set(0L)

  /** Get command by ID (for testing). */
  def findById(id: Long): UIO[Option[PendingCommand[Id, Cmd]]] =
    commandsRef.get.map(_.get(id))
end InMemoryCommandStore

object InMemoryCommandStore:

  /** Create a new in-memory command store. */
  def make[Id, Cmd]: UIO[InMemoryCommandStore[Id, Cmd]] =
    for
      commandsRef         <- Ref.make(Map.empty[Long, PendingCommand[Id, Cmd]])
      idempotencyIndexRef <- Ref.make(Map.empty[String, Long])
      claimsRef           <- Ref.make(Map.empty[Long, (String, Instant)])
      nextIdRef           <- Ref.make(0L)
    yield new InMemoryCommandStore(commandsRef, idempotencyIndexRef, claimsRef, nextIdRef)

end InMemoryCommandStore
