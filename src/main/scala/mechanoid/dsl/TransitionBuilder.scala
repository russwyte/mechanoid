package mechanoid.dsl

import zio.*
import mechanoid.core.*
import mechanoid.visualization.TransitionMeta

/** Builder for configuring a specific transition.
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  */
final class TransitionBuilder[S <: MState, E <: MEvent](
    private val definition: FSMDefinition[S, E],
    private val fromStateOrdinal: Int,
    private val event: Timed[E],
):
  private val eventOrdinal: Int = definition.eventEnum.ordinal(event)

  /** Transition to the specified target state. */
  def goto(targetState: S): FSMDefinition[S, E] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Goto(targetState))
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      Some(definition.stateEnum.ordinal(targetState)),
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end goto

  /** Stay in the current state (no transition). */
  def stay: FSMDefinition[S, E] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stay)
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None, // Stay = no target state change
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end stay

  /** Stop the FSM. */
  def stop: FSMDefinition[S, E] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stop(None))
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None, // Stop = terminal
      annotation = Some("stop"),
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end stop

  /** Stop the FSM with a reason. */
  def stop(reason: String): FSMDefinition[S, E] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stop(Some(reason)))
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None, // Stop = terminal
      annotation = Some(s"stop: $reason"),
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end stop

  /** Execute an effectful action that determines the transition result.
    *
    * The action can perform side effects and return any TransitionResult. Note: Target state is not known at compile
    * time for dynamic transitions.
    *
    * User errors are wrapped in `ActionFailedError`.
    */
  def execute[Err1](zio: ZIO[Any, Err1, TransitionResult[S]]): FSMDefinition[S, E] =
    val action     = (_: S, _: Timed[E]) => zio.mapError(wrapError)
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None, // Dynamic - target not known at definition time
      annotation = Some("dynamic"),
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end execute

  /** Execute with access to current state and triggering event.
    *
    * Use this when your transition logic needs to inspect the current state or event data:
    *
    * {{{
    * import mechanoid.*
    *
    * .when(Processing).on(Pay).executeWith { (state, event) =>
    *   event match
    *     case Pay(amount) if amount > 0 => goto(Paid)
    *     case Pay(_) => goto(PaymentFailed)
    * }
    * }}}
    *
    * The function receives the current state and the unwrapped user event (not Timed). For conditional logic that was
    * previously done with guards, use pattern matching or if-expressions inside the function body.
    *
    * User errors are wrapped in `ActionFailedError`.
    */
  def executeWith[Err1](
      fn: (S, E) => ZIO[Any, Err1, TransitionResult[S]]
  ): FSMDefinition[S, E] =
    val action = (state: S, timedEvent: Timed[E]) =>
      timedEvent match
        case Timed.UserEvent(e) => fn(state, e).mapError(wrapError)
        case Timed.TimeoutEvent =>
          // Shouldn't happen for user event transitions, but handle gracefully
          ZIO.succeed(TransitionResult.Stay)
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None, // Dynamic - target not known at definition time
      annotation = Some("dynamic"),
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end executeWith

  /** Execute an effectful action and then transition to a target state.
    *
    * User errors are wrapped in `ActionFailedError`.
    */
  def executing[Err1](effect: ZIO[Any, Err1, Unit]): ExecutingBuilder[S, E, Err1] =
    new ExecutingBuilder(
      definition,
      fromStateOrdinal,
      event,
      effect,
    )

  /** Execute an effectful action with state/event access, then chain to a goto/stay.
    *
    * User errors are wrapped in `ActionFailedError`.
    */
  def executingWith[Err1](
      fn: (S, E) => ZIO[Any, Err1, Unit]
  ): ExecutingWithBuilder[S, E, Err1] =
    new ExecutingWithBuilder(
      definition,
      fromStateOrdinal,
      event,
      fn,
    )

  /** Wrap an error as MechanoidError - pass through if already one, otherwise wrap in ActionFailedError. */
  private def wrapError[E](e: E): MechanoidError = e match
    case me: MechanoidError => me
    case other              => ActionFailedError(other)
end TransitionBuilder

/** Builder for transitions that execute an action before transitioning.
  *
  * User errors are wrapped in `ActionFailedError`.
  */
final class ExecutingBuilder[S <: MState, E <: MEvent, Err1](
    private val definition: FSMDefinition[S, E],
    private val fromStateOrdinal: Int,
    private val event: Timed[E],
    private val effect: ZIO[Any, Err1, Unit],
):
  private val eventOrdinal: Int = definition.eventEnum.ordinal(event)

  /** After executing the action, transition to the target state. */
  def goto(targetState: S): FSMDefinition[S, E] =
    val action     = (_: S, _: Timed[E]) => effect.mapError(wrapError).as(TransitionResult.Goto(targetState))
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      Some(definition.stateEnum.ordinal(targetState)),
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end goto

  /** After executing the action, stay in the current state. */
  def stay: FSMDefinition[S, E] =
    val action     = (_: S, _: Timed[E]) => effect.mapError(wrapError).as(TransitionResult.Stay)
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None,
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end stay

  /** Wrap an error as MechanoidError - pass through if already one, otherwise wrap in ActionFailedError. */
  private def wrapError[E](e: E): MechanoidError = e match
    case me: MechanoidError => me
    case other              => ActionFailedError(other)
end ExecutingBuilder

/** Builder for transitions that execute an action with state/event access before transitioning.
  *
  * User errors are wrapped in `ActionFailedError`.
  */
final class ExecutingWithBuilder[S <: MState, E <: MEvent, Err1](
    private val definition: FSMDefinition[S, E],
    private val fromStateOrdinal: Int,
    private val event: Timed[E],
    private val fn: (S, E) => ZIO[Any, Err1, Unit],
):
  private val eventOrdinal: Int = definition.eventEnum.ordinal(event)

  /** After executing the action, transition to the target state. */
  def goto(targetState: S): FSMDefinition[S, E] =
    val action = (state: S, timedEvent: Timed[E]) =>
      timedEvent match
        case Timed.UserEvent(e) =>
          fn(state, e).mapError(wrapError).as(TransitionResult.Goto(targetState))
        case Timed.TimeoutEvent => ZIO.succeed(TransitionResult.Stay)
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      Some(definition.stateEnum.ordinal(targetState)),
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end goto

  /** After executing the action, stay in the current state. */
  def stay: FSMDefinition[S, E] =
    val action = (state: S, timedEvent: Timed[E]) =>
      timedEvent match
        case Timed.UserEvent(e) =>
          fn(state, e).mapError(wrapError).as(TransitionResult.Stay)
        case Timed.TimeoutEvent => ZIO.succeed(TransitionResult.Stay)
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None,
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end stay

  /** Wrap an error as MechanoidError - pass through if already one, otherwise wrap in ActionFailedError. */
  private def wrapError[E](e: E): MechanoidError = e match
    case me: MechanoidError => me
    case other              => ActionFailedError(other)
end ExecutingWithBuilder
