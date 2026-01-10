package mechanoid.dsl

import zio.*
import mechanoid.core.*
import mechanoid.runtime.FSMRuntime
import scala.concurrent.duration.Duration
import scala.annotation.publicInBinary

/** Builder for defining a finite state machine.
  *
  * Both states and events are matched by their "shape" (ordinal), not exact value. This allows rich types like
  * `Failed(reason: String)` or `PaymentSucceeded(transactionId: String)` to work correctly - any `Failed(_)` will match
  * a transition defined for `Failed("")`.
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
final class FSMDefinition[S <: MState, E <: MEvent, R, Err] @publicInBinary private[mechanoid] (
    private[mechanoid] val transitions: Map[(Int, Int), Transition[S, Timed[E], S, R, Err]],
    private[mechanoid] val lifecycles: Map[Int, StateLifecycle[S, R, Err]],
    private[mechanoid] val timeouts: Map[Int, Duration],
)(using
    private[mechanoid] val stateEnum: SealedEnum[S],
    private[mechanoid] val eventEnum: SealedEnum[Timed[E]],
):

  /** Start defining transitions from a specific state.
    *
    * The state's shape (which case it is) is used for matching, not its exact value. For example, `.when(Failed(""))`
    * will match any `Failed(_)` state.
    */
  def when(state: S): WhenBuilder[S, E, R, Err] =
    new WhenBuilder(this, state.fsmOrdinal)

  /** Define entry/exit actions for a state.
    *
    * The state's shape is used for matching, so actions defined for `Failed("")` will run for any `Failed(_)`.
    */
  def onState(state: S): StateBuilder[S, E, R, Err] =
    new StateBuilder(this, state.fsmOrdinal)

  /** Set a timeout for a state.
    *
    * When the FSM is in this state and no event is received within the duration, a Timeout event will be automatically
    * sent. The state's shape is used for matching.
    */
  def withTimeout(state: S, duration: Duration): FSMDefinition[S, E, R, Err] =
    new FSMDefinition(transitions, lifecycles, timeouts + (state.fsmOrdinal -> duration))

  /** Add a transition to the definition. */
  private[dsl] def addTransition(
      fromOrdinal: Int,
      event: Timed[E],
      transition: Transition[S, Timed[E], S, R, Err],
  ): FSMDefinition[S, E, R, Err] =
    new FSMDefinition(
      transitions + ((fromOrdinal, eventEnum.ordinal(event)) -> transition),
      lifecycles,
      timeouts,
    )

  /** Update lifecycle for a state. */
  private[dsl] def updateLifecycle(
      stateOrd: Int,
      f: StateLifecycle[S, R, Err] => StateLifecycle[S, R, Err],
  ): FSMDefinition[S, E, R, Err] =
    val current = lifecycles.getOrElse(stateOrd, StateLifecycle.empty)
    new FSMDefinition(transitions, lifecycles + (stateOrd -> f(current)), timeouts)

  /** Build and start the FSM runtime with the given initial state. */
  def build(initial: S): ZIO[R & Scope, Nothing, FSMRuntime[S, E, R, Err]] =
    FSMRuntime.make(this, initial)
end FSMDefinition

object FSMDefinition:
  /** Create a new FSM definition.
    *
    * Uses compile-time derivation to extract state and event ordinals, enabling rich types (case classes with data) to
    * work correctly.
    *
    * Both state and event types must be sealed traits or enums. Attempting to use non-sealed types will result in a
    * clear compile-time error.
    *
    * @tparam S
    *   The state type (must be sealed)
    * @tparam E
    *   The event type (must be sealed)
    * @tparam R
    *   The ZIO environment required by transitions (default: Any)
    * @tparam Err
    *   The error type for transitions (default: Nothing)
    */
  def apply[S <: MState: SealedEnum, E <: MEvent: SealedEnum, R, Err]: FSMDefinition[S, E, R, Err] =
    new FSMDefinition(Map.empty, Map.empty, Map.empty)
end FSMDefinition
