package mechanoid.persistence.timeout

import zio.*
import mechanoid.core.*
import mechanoid.persistence.*

/** Utilities for firing timeouts through the EventStore.
  *
  * When using durable timeouts, the [[TimeoutSweeper]] discovers expired timeouts and needs to fire them. This module
  * provides the callback that integrates with the event-sourcing model.
  *
  * ==Flow==
  *
  * {{{
  * [Sweeper finds expired timeout]
  *       │
  *       ▼
  * [makeCallback called with (instanceId, expectedState)]
  *       │
  *       ▼
  * [Verify FSM is still in expected state (optional)]
  *       │
  *       ▼
  * [Append Timeout event to EventStore]
  *       │
  *   ┌───┴───┐
  *   ▼       ▼
  * [Success] [SequenceConflictError]
  *   │             │
  *   ▼             ▼
  * [Done]    [Skip - state changed concurrently]
  * }}}
  */
object TimeoutFiring:

  /** Create a callback for the sweeper to fire timeouts.
    *
    * This callback appends a `Timeout` event to the EventStore with optimistic locking. If the FSM's state has changed
    * concurrently (SequenceConflictError), the timeout is silently skipped - this is correct behavior because either:
    *   - Another event already transitioned the FSM (timeout no longer needed)
    *   - Another sweeper already fired this timeout
    *
    * ==State Verification==
    *
    * By default, this callback does NOT verify that the FSM is still in the expected state before firing. The FSM will
    * simply reject the Timeout event if no transition is defined. Use [[makeVerifyingCallback]] if you want to verify
    * first.
    *
    * @param eventStore
    *   The event store for this FSM type
    * @return
    *   A callback suitable for [[TimeoutSweeper.make]]
    */
  def makeCallback[Id, S <: MState, E <: MEvent](
      eventStore: EventStore[Id, S, E]
  ): (Id, String) => ZIO[Any, Throwable, Unit] =
    (instanceId, _) =>
      for
        seqNr <- eventStore.highestSequenceNr(instanceId)
        _     <- eventStore
          .append(instanceId, Timeout, seqNr)
          .catchSome { case _: SequenceConflictError =>
            // State changed concurrently - timeout may no longer be relevant
            // This is fine; the FSM has moved on
            ZIO.logDebug(
              s"Timeout for $instanceId skipped due to concurrent modification"
            ) *>
              ZIO.unit
          }
      yield ()

  /** Create a callback that verifies state before firing.
    *
    * This variant checks that the FSM is still in the expected state before appending the Timeout event. This reduces
    * unnecessary writes to the EventStore when the state has already changed.
    *
    * '''Note''': This requires loading events to determine current state, which adds latency. The simple
    * [[makeCallback]] is usually sufficient.
    *
    * @param eventStore
    *   The event store for this FSM type
    * @param getCurrentState
    *   A function to determine the FSM's current state from events. Typically: load snapshot + replay events.
    * @return
    *   A callback suitable for [[TimeoutSweeper.make]]
    */
  def makeVerifyingCallback[Id, S <: MState, E <: MEvent](
      eventStore: EventStore[Id, S, E],
      getCurrentState: Id => ZIO[Any, Throwable, Option[S]],
  ): (Id, String) => ZIO[Any, Throwable, Unit] =
    (instanceId, expectedState) =>
      for
        currentStateOpt <- getCurrentState(instanceId)
        _               <- currentStateOpt match
          case Some(currentState) if currentState.toString == expectedState =>
            // State matches - fire the timeout
            for
              seqNr <- eventStore.highestSequenceNr(instanceId)
              _     <- eventStore
                .append(instanceId, Timeout, seqNr)
                .catchSome { case _: SequenceConflictError =>
                  ZIO.logDebug(
                    s"Timeout for $instanceId skipped due to concurrent modification"
                  )
                }
            yield ()

          case Some(currentState) =>
            // State has changed - skip the timeout
            ZIO.logDebug(
              s"Timeout for $instanceId skipped: expected $expectedState but found $currentState"
            )

          case None =>
            // FSM doesn't exist - skip
            ZIO.logDebug(
              s"Timeout for $instanceId skipped: FSM not found"
            )
      yield ()

  /** Create a callback that uses a specific EventStore instance.
    *
    * This is a convenience method when you have the EventStore available directly rather than through ZIO environment.
    */
  def makeCallbackDirect[Id, S <: MState, E <: MEvent](
      eventStore: EventStore[Id, S, E]
  ): (Id, String) => ZIO[Any, Throwable, Unit] =
    makeCallback(eventStore)
end TimeoutFiring
