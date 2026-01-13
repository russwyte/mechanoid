package mechanoid.dsl

import zio.*
import mechanoid.core.*
import mechanoid.runtime.FSMRuntime
import mechanoid.visualization.TransitionMeta
import scala.concurrent.duration.Duration
import scala.annotation.publicInBinary

/** Builder for defining a finite state machine.
  *
  * Both states and events are matched by their "shape" (caseHash), not exact value. This allows rich types like
  * `Failed(reason: String)` or `PaymentSucceeded(transactionId: String)` to work correctly - any `Failed(_)` will match
  * a transition defined for `Failed("")`.
  *
  * All errors from transitions and lifecycle actions are wrapped as `MechanoidError`. User-defined errors are wrapped
  * in `ActionFailedError`, while library errors (invalid transitions, etc.) use specific error types.
  *
  * @tparam S
  *   The state type (must extend MState)
  * @tparam E
  *   The event type (must extend MEvent)
  * @tparam Cmd
  *   The command type for side effects (use Nothing for FSMs without commands)
  */
final class FSMDefinition[S <: MState, E <: MEvent, Cmd] @publicInBinary private[mechanoid] (
    private[mechanoid] val transitions: Map[(Int, Int), Transition[S, Timed[E], S]],
    private[mechanoid] val lifecycles: Map[Int, StateLifecycle[S, Cmd]],
    private[mechanoid] val timeouts: Map[Int, Duration],
    private[mechanoid] val transitionMeta: List[TransitionMeta] = List.empty,
)(using
    private[mechanoid] val stateEnum: SealedEnum[S],
    private[mechanoid] val eventEnum: SealedEnum[Timed[E]],
):

  /** Get state names for visualization (caseHash -> name). */
  def stateNames: Map[Int, String] = stateEnum.caseNames

  /** Get event names for visualization (caseHash -> name, includes Timeout). */
  def eventNames: Map[Int, String] = eventEnum.caseNames

  /** Add a transition to the definition. */
  private[mechanoid] def addTransition(
      fromCaseHash: Int,
      event: Timed[E],
      transition: Transition[S, Timed[E], S],
  ): FSMDefinition[S, E, Cmd] =
    new FSMDefinition(
      transitions + ((fromCaseHash, eventEnum.caseHash(event)) -> transition),
      lifecycles,
      timeouts,
      transitionMeta,
    )

  /** Add a transition with visualization metadata. */
  private[mechanoid] def addTransitionWithMeta(
      fromCaseHash: Int,
      event: Timed[E],
      transition: Transition[S, Timed[E], S],
      meta: TransitionMeta,
  ): FSMDefinition[S, E, Cmd] =
    new FSMDefinition(
      transitions + ((fromCaseHash, eventEnum.caseHash(event)) -> transition),
      lifecycles,
      timeouts,
      transitionMeta :+ meta,
    )

  /** Add a transition using event hash directly (for multi-event builders). */
  private[mechanoid] def addTransitionWithMetaByHash(
      fromCaseHash: Int,
      eventCaseHash: Int,
      transition: Transition[S, Timed[E], S],
      meta: TransitionMeta,
  ): FSMDefinition[S, E, Cmd] =
    new FSMDefinition(
      transitions + ((fromCaseHash, eventCaseHash) -> transition),
      lifecycles,
      timeouts,
      transitionMeta :+ meta,
    )

  /** Update lifecycle for a state. */
  private[mechanoid] def updateLifecycle(
      stateCaseHash: Int,
      f: StateLifecycle[S, Cmd] => StateLifecycle[S, Cmd],
  ): FSMDefinition[S, E, Cmd] =
    val current = lifecycles.getOrElse(stateCaseHash, StateLifecycle.empty[S, Cmd])
    new FSMDefinition(
      transitions,
      lifecycles + (stateCaseHash -> f(current)),
      timeouts,
      transitionMeta,
    )
  end updateLifecycle

  /** Set timeout for a state. */
  private[mechanoid] def setStateTimeout(
      stateCaseHash: Int,
      timeout: Duration,
  ): FSMDefinition[S, E, Cmd] =
    new FSMDefinition(
      transitions,
      lifecycles,
      timeouts + (stateCaseHash -> timeout),
      transitionMeta,
    )

  /** Build and start the FSM runtime with the given initial state.
    *
    * Creates an in-memory FSM runtime suitable for testing or single-process use. For distributed/persistent FSMs, use
    * `FSMRuntime.apply` or `FSMRuntime.withLocking` directly.
    */
  def build(initial: S): ZIO[Scope, MechanoidError, FSMRuntime[Unit, S, E, Cmd]] =
    FSMRuntime.make(this, initial)
end FSMDefinition

object FSMDefinition:
  /** Create a new FSM definition without commands.
    *
    * Uses compile-time derivation to extract state and event caseHashes, enabling rich types (case classes with data)
    * to work correctly.
    *
    * Both state and event types must be sealed traits or enums. Attempting to use non-sealed types will result in a
    * clear compile-time error.
    *
    * @tparam S
    *   The state type (must be sealed)
    * @tparam E
    *   The event type (must be sealed)
    */
  def apply[S <: MState: SealedEnum, E <: MEvent: SealedEnum]: FSMDefinition[S, E, Nothing] =
    new FSMDefinition(Map.empty, Map.empty, Map.empty)

  /** Create a new FSM definition with commands.
    *
    * Commands enable the transactional outbox pattern for reliable side effect execution. Use
    * `.onState(s).enqueue(...)` to declaratively specify which commands to enqueue when entering a state.
    *
    * @tparam S
    *   The state type (must be sealed)
    * @tparam E
    *   The event type (must be sealed)
    * @tparam Cmd
    *   The command type for side effects
    */
  def withCommands[S <: MState: SealedEnum, E <: MEvent: SealedEnum, Cmd]: FSMDefinition[S, E, Cmd] =
    new FSMDefinition(Map.empty, Map.empty, Map.empty)
end FSMDefinition
