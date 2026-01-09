package mechanoid.dsl

import zio.*
import mechanoid.core.*
import mechanoid.runtime.FSMRuntime
import scala.concurrent.duration.Duration
import scala.deriving.Mirror
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
    private[mechanoid] val transitions: Map[(Int, Int), Transition[S, E | Timeout.type, S, R, Err]],
    private[mechanoid] val lifecycles: Map[Int, StateLifecycle[S, R, Err]],
    private[mechanoid] val timeouts: Map[Int, Duration],
    private[mechanoid] val stateOrdinal: S => Int,
    private[mechanoid] val eventOrdinal: (E | Timeout.type) => Int,
):

  /** Start defining transitions from a specific state.
    *
    * The state's shape (which case it is) is used for matching, not its exact value. For example, `.when(Failed(""))`
    * will match any `Failed(_)` state.
    */
  def when(state: S): WhenBuilder[S, E, R, Err] =
    new WhenBuilder(this, stateOrdinal(state))

  /** Define entry/exit actions for a state.
    *
    * The state's shape is used for matching, so actions defined for `Failed("")` will run for any `Failed(_)`.
    */
  def onState(state: S): StateBuilder[S, E, R, Err] =
    new StateBuilder(this, stateOrdinal(state))

  /** Set a timeout for a state.
    *
    * When the FSM is in this state and no event is received within the duration, a Timeout event will be automatically
    * sent. The state's shape is used for matching.
    */
  def withTimeout(state: S, duration: Duration): FSMDefinition[S, E, R, Err] =
    new FSMDefinition(transitions, lifecycles, timeouts + (stateOrdinal(state) -> duration), stateOrdinal, eventOrdinal)

  /** Add a transition to the definition. */
  private[dsl] def addTransition(
      fromOrdinal: Int,
      event: E | Timeout.type,
      transition: Transition[S, E | Timeout.type, S, R, Err],
  ): FSMDefinition[S, E, R, Err] =
    new FSMDefinition(
      transitions + ((fromOrdinal, eventOrdinal(event)) -> transition),
      lifecycles,
      timeouts,
      stateOrdinal,
      eventOrdinal,
    )

  /** Update lifecycle for a state. */
  private[dsl] def updateLifecycle(
      stateOrd: Int,
      f: StateLifecycle[S, R, Err] => StateLifecycle[S, R, Err],
  ): FSMDefinition[S, E, R, Err] =
    val current = lifecycles.getOrElse(stateOrd, StateLifecycle.empty)
    new FSMDefinition(transitions, lifecycles + (stateOrd -> f(current)), timeouts, stateOrdinal, eventOrdinal)

  /** Build and start the FSM runtime with the given initial state. */
  def build(initial: S): ZIO[R & Scope, Nothing, FSMRuntime[S, E, R, Err]] =
    FSMRuntime.make(this, initial)
end FSMDefinition

object FSMDefinition:
  // Special ordinal for Timeout (not part of user's event enum)
  private val TimeoutOrdinal: Int = -1

  /** Create a new FSM definition with no environment requirements.
    *
    * Uses Scala 3's Mirror to extract state and event ordinals at compile time, enabling rich types (case classes with
    * data) to work correctly.
    */
  inline def apply[S <: MState, E <: MEvent](using
      sm: Mirror.SumOf[S],
      em: Mirror.SumOf[E],
  ): FSMDefinition[S, E, Any, Nothing] =
    new FSMDefinition(
      Map.empty,
      Map.empty,
      Map.empty,
      (s: S) => sm.ordinal(s),
      (e: E | Timeout.type) =>
        e match
          case Timeout => TimeoutOrdinal
          case ev: E   => em.ordinal(ev),
    )

  /** Create a new FSM definition with specific environment and error types. */
  inline def typed[S <: MState, E <: MEvent, R, Err](using
      sm: Mirror.SumOf[S],
      em: Mirror.SumOf[E],
  ): FSMDefinition[S, E, R, Err] =
    new FSMDefinition(
      Map.empty,
      Map.empty,
      Map.empty,
      (s: S) => sm.ordinal(s),
      (e: E | Timeout.type) =>
        e match
          case Timeout => TimeoutOrdinal
          case ev: E   => em.ordinal(ev),
    )
end FSMDefinition
