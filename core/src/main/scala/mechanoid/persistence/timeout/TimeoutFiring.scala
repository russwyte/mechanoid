package mechanoid.persistence.timeout

import zio.*
import mechanoid.core.*
import mechanoid.persistence.*

/** Utilities for firing timeouts through the EventStore.
  *
  * When using durable timeouts, the [[TimeoutSweeper]] discovers expired timeouts and needs to fire them. This module
  * provides the callback that integrates with the event-sourcing model.
  *
  * ==User-Defined Timeout Events==
  *
  * Timeouts now fire user-defined events rather than a built-in Timeout singleton. The sweeper must be configured with
  * a way to determine which event to fire for each timeout.
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
  * [Determine which event to fire from Machine configuration]
  *       │
  *       ▼
  * [Append user's timeout event to EventStore]
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

  /** Create a callback for the sweeper to fire timeouts with a provided event resolver.
    *
    * The `resolveTimeoutEvent` function determines which event to fire based on the state name. This should typically
    * look up the timeout event from the Machine's `timeoutEvents` map.
    *
    * @param eventStore
    *   The event store for this FSM type
    * @param resolveTimeoutEvent
    *   Given a state name, return the event to fire (or None to skip)
    * @return
    *   A callback suitable for [[TimeoutSweeper.make]]
    */
  def makeCallback[Id, S, E](
      eventStore: EventStore[Id, S, E],
      resolveTimeoutEvent: String => Option[E],
  ): (Id, String) => ZIO[Any, MechanoidError, Unit] =
    (instanceId, stateName) =>
      resolveTimeoutEvent(stateName) match
        case Some(timeoutEvent) =>
          for
            seqNr <- eventStore.highestSequenceNr(instanceId)
            _     <- eventStore
              .append(instanceId, timeoutEvent, seqNr)
              .catchSome { case _: SequenceConflictError =>
                // State changed concurrently - timeout may no longer be relevant
                ZIO.logDebug(
                  s"Timeout for $instanceId skipped due to concurrent modification"
                ) *>
                  ZIO.unit
              }
          yield ()
        case None =>
          // No timeout event configured for this state - skip
          ZIO.logDebug(
            s"Timeout for $instanceId skipped: no timeout event configured for state $stateName"
          )

  /** Create a callback that verifies state before firing.
    *
    * This variant checks that the FSM is still in the expected state before appending the timeout event.
    *
    * @param eventStore
    *   The event store for this FSM type
    * @param resolveTimeoutEvent
    *   Given a state name, return the event to fire (or None to skip)
    * @param getCurrentState
    *   A function to determine the FSM's current state from events
    * @return
    *   A callback suitable for [[TimeoutSweeper.make]]
    */
  def makeVerifyingCallback[Id, S, E](
      eventStore: EventStore[Id, S, E],
      resolveTimeoutEvent: String => Option[E],
      getCurrentState: Id => ZIO[Any, MechanoidError, Option[S]],
  ): (Id, String) => ZIO[Any, MechanoidError, Unit] =
    (instanceId, expectedState) =>
      resolveTimeoutEvent(expectedState) match
        case Some(timeoutEvent) =>
          for
            currentStateOpt <- getCurrentState(instanceId)
            _               <- currentStateOpt match
              case Some(currentState) if currentState.toString == expectedState =>
                // State matches - fire the timeout
                for
                  seqNr <- eventStore.highestSequenceNr(instanceId)
                  _     <- eventStore
                    .append(instanceId, timeoutEvent, seqNr)
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

        case None =>
          ZIO.logDebug(
            s"Timeout for $instanceId skipped: no timeout event configured for state $expectedState"
          )
end TimeoutFiring
