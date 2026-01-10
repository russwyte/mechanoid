package mechanoid.runtime

import zio.*
import mechanoid.core.*
import mechanoid.dsl.FSMDefinition

/** The runtime interface for an active FSM.
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  * @tparam R
  *   The ZIO environment required
  * @tparam Err
  *   The error type
  */
trait FSMRuntime[S <: MState, E <: MEvent, R, Err]:

  /** Send an event to the FSM and get the transition result.
    *
    * Returns the outcome of processing the event:
    *   - Stay: FSM remained in current state
    *   - Goto: FSM transitioned to a new state
    *   - Stop: FSM has stopped
    *
    * If no transition is defined for the current state and event, returns an InvalidTransitionError.
    */
  def send(event: E): ZIO[R, Err | MechanoidError, TransitionResult[S]]

  /** Get the current state of the FSM. */
  def currentState: UIO[S]

  /** Get the full FSM state including metadata. */
  def state: UIO[FSMState[S]]

  /** Get the history of previous states (most recent first). */
  def history: UIO[List[S]]

  /** Stop the FSM gracefully. */
  def stop: UIO[Unit]

  /** Stop the FSM with a reason. */
  def stop(reason: String): UIO[Unit]

  /** Check if the FSM is currently running. */
  def isRunning: UIO[Boolean]
end FSMRuntime

object FSMRuntime:
  /** Create a new FSM runtime from a definition and initial state. */
  def make[S <: MState, E <: MEvent, R, Err](
      definition: FSMDefinition[S, E, R, Err],
      initial: S,
  ): ZIO[R & Scope, Nothing, FSMRuntime[S, E, R, Err]] =
    FSMRuntimeImpl.make(definition, initial)
