package mechanoid.persistence.lock

import zio.*
import zio.stream.*
import mechanoid.core.*
import mechanoid.persistence.*

/** A wrapper that adds distributed locking to a PersistentFSMRuntime.
  *
  * This ensures exactly-once transition semantics by acquiring a lock before processing each event. If another node is
  * processing the same FSM instance, the operation waits (up to timeout) or fails.
  *
  * ==Why Use Locking?==
  *
  * Without locking, concurrent event processing relies on optimistic locking which detects conflicts ''after'' the
  * fact. With distributed locking:
  *
  *   - Only one node processes events for an FSM at a time
  *   - No wasted work from rejected writes
  *   - Guaranteed exactly-once delivery per event
  *
  * ==Node Failure Resilience==
  *
  * Locks are lease-based and automatically expire. If a node crashes:
  *   1. Other nodes wait for the lock to expire
  *   2. Lock expires after `lockDuration`
  *   3. Another node acquires the lock and continues processing
  *
  * ==Zombie Node Protection==
  *
  * Even if a paused node wakes up after its lock expired:
  *   - The lock is validated before each operation (if configured)
  *   - EventStore's optimistic locking provides a final safety net
  *
  * ==Usage==
  *
  * {{{
  * val program = ZIO.scoped {
  *   for
  *     fsm <- PersistentFSMRuntime.withLocking(orderId, definition, Pending)
  *     _   <- fsm.send(Pay)  // Lock acquired automatically around this call
  *   yield ()
  * }.provide(eventStoreLayer, lockLayer)
  * }}}
  */
final class LockedFSMRuntime[Id, S <: MState, E <: MEvent, R, Err] private[lock] (
    underlying: PersistentFSMRuntime[Id, S, E, R, Err],
    lock: FSMInstanceLock[Id],
    config: LockConfig,
) extends PersistentFSMRuntime[Id, S, E, R, Err]:

  override def instanceId: Id = underlying.instanceId

  override def send(event: E): ZIO[R, Err | MechanoidError, TransitionResult[S]] =
    lock
      .withLock(instanceId, config.nodeId, config.lockDuration, Some(config.acquireTimeout)) {
        // Optionally validate lock is still held before proceeding
        validateLockIfConfigured *>
          underlying.send(event)
      }
      .mapError {
        case e: LockError      => LockingError(e)
        case e: MechanoidError => e
        case e: Err @unchecked => e
      }

  /** Validate that we still hold the lock (if configured). */
  private def validateLockIfConfigured: ZIO[Any, LockError, Unit] =
    if config.validateBeforeOperation then
      Clock.instant.flatMap { now =>
        lock
          .get(instanceId, now)
          .flatMap {
            case Some(token) if token.nodeId == config.nodeId =>
              ZIO.unit
            case Some(token) =>
              ZIO.fail(LockError.LockBusy(instanceId.toString, token.nodeId, token.expiresAt))
            case None =>
              // Lock was released/expired - this shouldn't happen inside withLock
              // but check anyway for safety
              ZIO.fail(
                LockError.LockTimeout(instanceId.toString, config.lockDuration)
              )
          }
          .mapError {
            case e: LockError      => e
            case e: MechanoidError =>
              LockError.LockAcquisitionFailed(instanceId.toString, new RuntimeException(e.toString))
          }
      }
    else ZIO.unit

  override def currentState: UIO[S] = underlying.currentState

  override def state: UIO[FSMState[S]] = underlying.state

  override def history: UIO[List[S]] = underlying.history

  override def lastSequenceNr: UIO[Long] = underlying.lastSequenceNr

  override def subscribe: ZStream[Any, Nothing, StateChange[S, E | Timeout.type]] =
    underlying.subscribe

  override def processStream(
      events: ZStream[R, Err, E]
  ): ZStream[R, Err | MechanoidError, StateChange[S, E | Timeout.type]] =
    // Note: We can't simply delegate to underlying.processStream because
    // it would bypass our locked send(). Instead, we process each event
    // through our send() and construct state changes.
    val results: ZStream[R, Err | MechanoidError, (FSMState[S], TransitionResult[S], FSMState[S], E)] =
      events.mapZIO { (event: E) =>
        for
          stateBefore <- underlying.state
          result      <- send(event)
          stateAfter  <- underlying.state
        yield (stateBefore, result, stateAfter, event)
      }

    results.collect { case (stateBefore, TransitionResult.Goto(newState), stateAfter, event) =>
      StateChange(
        stateBefore.current,
        newState,
        event: E | Timeout.type,
        stateAfter.lastTransitionAt,
      )
    }
  end processStream

  override def stop: UIO[Unit] = underlying.stop

  override def stop(reason: String): UIO[Unit] = underlying.stop(reason)

  override def isRunning: UIO[Boolean] = underlying.isRunning

  override def saveSnapshot: ZIO[Any, MechanoidError, Unit] =
    // Snapshots don't need locking - they're read-only from the FSM's perspective
    underlying.saveSnapshot
end LockedFSMRuntime

object LockedFSMRuntime:

  /** Wrap a PersistentFSMRuntime with distributed locking.
    *
    * @param underlying
    *   The runtime to wrap
    * @param lock
    *   The distributed lock service
    * @param config
    *   Lock configuration
    * @return
    *   A new runtime that acquires locks around event processing
    */
  def apply[Id, S <: MState, E <: MEvent, R, Err](
      underlying: PersistentFSMRuntime[Id, S, E, R, Err],
      lock: FSMInstanceLock[Id],
      config: LockConfig = LockConfig.default,
  ): LockedFSMRuntime[Id, S, E, R, Err] =
    new LockedFSMRuntime(underlying, lock, config)
end LockedFSMRuntime
