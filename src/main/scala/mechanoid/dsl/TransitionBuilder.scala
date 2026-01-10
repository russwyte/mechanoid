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
  * @tparam R
  *   The ZIO environment required
  * @tparam Err
  *   The error type
  */
final class TransitionBuilder[S <: MState, E <: MEvent, R, Err](
    private val definition: FSMDefinition[S, E, R, Err],
    private val fromStateOrdinal: Int,
    private val event: Timed[E],
    private val guard: Option[ZIO[R, Err, Boolean]],
):
  private val eventOrdinal: Int = definition.eventEnum.ordinal(event)

  /** Add a guard condition that must be true for the transition to occur.
    *
    * If the guard returns false, the transition is rejected and the FSM stays in the current state.
    */
  def when[R1 <: R, Err1 >: Err](predicate: ZIO[R1, Err1, Boolean]): TransitionBuilder[S, E, R1, Err1] =
    new TransitionBuilder(
      definition.asInstanceOf[FSMDefinition[S, E, R1, Err1]],
      fromStateOrdinal,
      event,
      Some(predicate),
    )

  /** Add a simple boolean guard condition.
    *
    * This is a convenience method for guards that don't require effects. The condition is evaluated once when the
    * transition is checked.
    *
    * @param condition
    *   The boolean condition that must be true
    */
  def when(condition: Boolean): TransitionBuilder[S, E, R, Err] =
    new TransitionBuilder(definition, fromStateOrdinal, event, Some(ZIO.succeed(condition)))

  /** Add a by-name boolean guard condition.
    *
    * The condition is evaluated lazily each time the transition is checked. Use this when the guard depends on mutable
    * state or should be re-evaluated.
    *
    * @param condition
    *   The boolean condition (evaluated lazily)
    */
  def whenEval(condition: => Boolean): TransitionBuilder[S, E, R, Err] =
    new TransitionBuilder(definition, fromStateOrdinal, event, Some(ZIO.succeed(condition)))

  /** Transition to the specified target state. */
  def goto(targetState: S): FSMDefinition[S, E, R, Err] =
    val transition = Transition[S, Timed[E], S, R, Err](
      guard,
      ZIO.succeed(TransitionResult.Goto(targetState)),
      None,
    )
    val meta = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      Some(definition.stateEnum.ordinal(targetState)),
      guard.isDefined,
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end goto

  /** Stay in the current state (no transition). */
  def stay: FSMDefinition[S, E, R, Err] =
    val transition = Transition[S, Timed[E], S, R, Err](
      guard,
      ZIO.succeed(TransitionResult.Stay),
      None,
    )
    val meta = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None, // Stay = no target state change
      guard.isDefined,
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end stay

  /** Stop the FSM. */
  def stop: FSMDefinition[S, E, R, Err] =
    val transition = Transition[S, Timed[E], S, R, Err](
      guard,
      ZIO.succeed(TransitionResult.Stop(None)),
      None,
    )
    val meta = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None, // Stop = terminal
      guard.isDefined,
      Some("stop"),
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end stop

  /** Stop the FSM with a reason. */
  def stop(reason: String): FSMDefinition[S, E, R, Err] =
    val transition = Transition[S, Timed[E], S, R, Err](
      guard,
      ZIO.succeed(TransitionResult.Stop(Some(reason))),
      None,
    )
    val meta = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None, // Stop = terminal
      guard.isDefined,
      Some(s"stop: $reason"),
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end stop

  /** Execute an effectful action that determines the transition result.
    *
    * The action can perform side effects and return any TransitionResult. Note: Target state is not known at compile
    * time for dynamic transitions.
    */
  def execute[R1 <: R, Err1 >: Err](action: ZIO[R1, Err1, TransitionResult[S]]): FSMDefinition[S, E, R1, Err1] =
    val transition = Transition[S, Timed[E], S, R1, Err1](
      guard.asInstanceOf[Option[ZIO[R1, Err1, Boolean]]],
      action,
      None,
    )
    val meta = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None, // Dynamic - target not known at definition time
      guard.isDefined,
      Some("dynamic"),
    )
    definition
      .asInstanceOf[FSMDefinition[S, E, R1, Err1]]
      .addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end execute

  /** Execute an effectful action and then transition to a target state. */
  def executing[R1 <: R, Err1 >: Err](action: ZIO[R1, Err1, Unit]): ExecutingBuilder[S, E, R1, Err1] =
    new ExecutingBuilder(
      definition.asInstanceOf[FSMDefinition[S, E, R1, Err1]],
      fromStateOrdinal,
      event,
      guard.asInstanceOf[Option[ZIO[R1, Err1, Boolean]]],
      action,
    )
end TransitionBuilder

/** Builder for transitions that execute an action before transitioning.
  */
final class ExecutingBuilder[S <: MState, E <: MEvent, R, Err](
    private val definition: FSMDefinition[S, E, R, Err],
    private val fromStateOrdinal: Int,
    private val event: Timed[E],
    private val guard: Option[ZIO[R, Err, Boolean]],
    private val action: ZIO[R, Err, Unit],
):
  private val eventOrdinal: Int = definition.eventEnum.ordinal(event)

  /** After executing the action, transition to the target state. */
  def goto(targetState: S): FSMDefinition[S, E, R, Err] =
    val transition = Transition[S, Timed[E], S, R, Err](
      guard,
      action.as(TransitionResult.Goto(targetState)),
      None,
    )
    val meta = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      Some(definition.stateEnum.ordinal(targetState)),
      guard.isDefined,
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end goto

  /** After executing the action, stay in the current state. */
  def stay: FSMDefinition[S, E, R, Err] =
    val transition = Transition[S, Timed[E], S, R, Err](
      guard,
      action.as(TransitionResult.Stay),
      None,
    )
    val meta = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None,
      guard.isDefined,
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end stay
end ExecutingBuilder
