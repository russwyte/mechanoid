package mechanoid.dsl

import zio.*
import mechanoid.core.*
import mechanoid.runtime.FSMRuntime
import scala.concurrent.duration.Duration

/** Builder for defining a finite state machine.
  *
  * @tparam S
  *   The state type (must extend MState)
  * @tparam E
  *   The event type (must extend MEvent)
  * @tparam R
  *   The ZIO environment required
  * @tparam Err
  *   The error type
  */
final class FSMDefinition[S <: MState, E <: MEvent, R, Err] private (
    private[mechanoid] val transitions: Map[(S, E | Timeout.type), Transition[S, E | Timeout.type, S, R, Err]],
    private[mechanoid] val lifecycles: Map[S, StateLifecycle[S, R, Err]],
    private[mechanoid] val timeouts: Map[S, Duration],
):

  /** Start defining transitions from a specific state. */
  def when(state: S): WhenBuilder[S, E, R, Err] =
    new WhenBuilder(this, state)

  /** Define entry/exit actions for a state. */
  def onState(state: S): StateBuilder[S, E, R, Err] =
    new StateBuilder(this, state)

  /** Set a timeout for a state.
    *
    * When the FSM is in this state and no event is received within the duration, a Timeout event will be automatically
    * sent.
    */
  def withTimeout(state: S, duration: Duration): FSMDefinition[S, E, R, Err] =
    new FSMDefinition(transitions, lifecycles, timeouts + (state -> duration))

  /** Add a transition to the definition. */
  private[dsl] def addTransition(
      from: S,
      event: E | Timeout.type,
      transition: Transition[S, E | Timeout.type, S, R, Err],
  ): FSMDefinition[S, E, R, Err] =
    new FSMDefinition(transitions + ((from, event) -> transition), lifecycles, timeouts)

  /** Update lifecycle for a state. */
  private[dsl] def updateLifecycle(
      state: S,
      f: StateLifecycle[S, R, Err] => StateLifecycle[S, R, Err],
  ): FSMDefinition[S, E, R, Err] =
    val current = lifecycles.getOrElse(state, StateLifecycle.empty)
    new FSMDefinition(transitions, lifecycles + (state -> f(current)), timeouts)

  /** Build and start the FSM runtime with the given initial state. */
  def build(initial: S): ZIO[R & Scope, Nothing, FSMRuntime[S, E, R, Err]] =
    FSMRuntime.make(this, initial)
end FSMDefinition

object FSMDefinition:
  /** Create a new FSM definition with no environment requirements. */
  def apply[S <: MState, E <: MEvent]: FSMDefinition[S, E, Any, Nothing] =
    new FSMDefinition(Map.empty, Map.empty, Map.empty)

  /** Create a new FSM definition with specific environment and error types. */
  def typed[S <: MState, E <: MEvent, R, Err]: FSMDefinition[S, E, R, Err] =
    new FSMDefinition(Map.empty, Map.empty, Map.empty)
