package mechanoid.dsl

import zio.*
import mechanoid.core.*
import mechanoid.macros.SealedEnumMacros
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

  /** Start defining transitions from a specific state.
    *
    * The state's shape (which case it is) is used for matching, not its exact value. For example, `.when(Failed(""))`
    * will match any `Failed(_)` state.
    */
  def when(state: S): WhenBuilder[S, E, Cmd] =
    new WhenBuilder(this, state.fsmCaseHash)

  /** Start defining transitions that apply to all leaf states under a parent type.
    *
    * This enables defining a single transition that applies to multiple related states. For example:
    * {{{
    * sealed trait InReview extends DocumentState
    * case object PendingReview extends InReview
    * case object UnderReview extends InReview
    *
    * // This applies to both PendingReview and UnderReview:
    * .whenAny[InReview].on(Cancel).goto(Cancelled)
    * }}}
    *
    * Transitions defined with `whenAny` can be overridden by more specific leaf-level transitions defined later:
    * {{{
    * .whenAny[InReview].on(Cancel).goto(Cancelled)        // Default for all InReview states
    * .when(UnderReview).on(Cancel).goto(ReviewCancelled)  // Override for UnderReview specifically
    * }}}
    *
    * @tparam T
    *   The parent state type (must be a sealed trait that is a subtype of S)
    */
  inline def whenAny[T <: S]: WhenAnyBuilder[S, E, Cmd] =
    val parentHash = SealedEnumMacros.typeHash[T](CaseHasher.Default)
    val leafHashes = stateEnum.leafHashesFor(parentHash)
    if leafHashes.isEmpty then
      throw new IllegalArgumentException(
        s"whenAny called with type that has no leaf states in the hierarchy. " +
          s"Ensure the type parameter is a sealed parent trait, not a leaf state."
      )
    new WhenAnyBuilder(this, leafHashes)

  /** Start defining transitions that apply to specific states under a parent type.
    *
    * Like `whenAny[T]` but only applies to the explicitly listed states. The type parameter ensures at compile-time
    * that all provided states are subtypes of the parent type.
    *
    * {{{
    * // Only PendingReview and UnderReview can cancel, not ChangesRequested
    * .whenAny[InReview](PendingReview, UnderReview).on(Cancel).goto(Draft)
    * }}}
    *
    * @tparam T
    *   The parent state type (must be a sealed trait that is a subtype of S)
    * @param first
    *   The first state (must be a subtype of T)
    * @param rest
    *   Additional states (must be subtypes of T)
    */
  def whenAny[T <: S](first: T, rest: T*): WhenAnyBuilder[S, E, Cmd] =
    val stateHashes = (first +: rest).map(s => stateEnum.caseHash(s)).toSet
    new WhenAnyBuilder(this, stateHashes)

  /** Start defining transitions that apply to an explicit list of states.
    *
    * Unlike `whenAny[T]` which uses the type hierarchy, this method lets you specify exactly which states should share
    * a transition. Useful when you want to apply the same behavior to states that don't share a common parent.
    *
    * {{{
    * // Apply same transition to specific states (not based on hierarchy)
    * .whenStates(PendingReview, Rejected).on(Cancel).goto(Draft)
    *
    * // Can still override for specific states defined later
    * .whenStates(State1, State2, State3).on(Reset).goto(Initial)
    * .when(State2).on(Reset).goto(Special)  // Override for State2
    * }}}
    *
    * @param first
    *   The first state (required)
    * @param rest
    *   Additional states
    */
  def whenStates(first: S, rest: S*): WhenAnyBuilder[S, E, Cmd] =
    val stateHashes = (first +: rest).map(s => stateEnum.caseHash(s)).toSet
    new WhenAnyBuilder(this, stateHashes)

  /** Define entry/exit actions for a state.
    *
    * The state's shape is used for matching, so actions defined for `Failed("")` will run for any `Failed(_)`.
    */
  def onState(state: S): StateBuilder[S, E, Cmd] =
    new StateBuilder(this, state.fsmCaseHash)

  /** Set a timeout for a state.
    *
    * When the FSM is in this state and no event is received within the duration, a Timeout event will be automatically
    * sent. The state's shape is used for matching.
    */
  def withTimeout(state: S, duration: Duration): FSMDefinition[S, E, Cmd] =
    new FSMDefinition(
      transitions,
      lifecycles,
      timeouts + (state.fsmCaseHash -> duration),
      transitionMeta,
    )

  /** Add a transition to the definition. */
  private[dsl] def addTransition(
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
  private[dsl] def addTransitionWithMeta(
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

  /** Update lifecycle for a state. */
  private[dsl] def updateLifecycle(
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
