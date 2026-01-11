package mechanoid.dsl

import zio.*
import mechanoid.core.*
import mechanoid.visualization.{TransitionMeta, TransitionKind}

/** Builder for configuring a specific transition.
  *
  * All transitions are statically resolvable - the target state is known at compile time. For conditional logic or side
  * effects, use entry/exit actions on states and the command pattern to produce events that drive further transitions.
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  * @tparam Cmd
  *   The command type
  */
final class TransitionBuilder[S <: MState, E <: MEvent, Cmd](
    private val definition: FSMDefinition[S, E, Cmd],
    private val fromStateOrdinal: Int,
    private val event: Timed[E],
):
  private val eventOrdinal: Int = definition.eventEnum.ordinal(event)

  /** Transition to the specified target state. */
  def goto(targetState: S): FSMDefinition[S, E, Cmd] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Goto(targetState))
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      Some(definition.stateEnum.ordinal(targetState)),
      TransitionKind.Goto,
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end goto

  /** Stay in the current state (no transition). */
  def stay: FSMDefinition[S, E, Cmd] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stay)
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None,
      TransitionKind.Stay,
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end stay

  /** Stop the FSM. */
  def stop: FSMDefinition[S, E, Cmd] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stop(None))
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None,
      TransitionKind.Stop(None),
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end stop

  /** Stop the FSM with a reason. */
  def stop(reason: String): FSMDefinition[S, E, Cmd] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stop(Some(reason)))
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateOrdinal,
      eventOrdinal,
      None,
      TransitionKind.Stop(Some(reason)),
    )
    definition.addTransitionWithMeta(fromStateOrdinal, event, transition, meta)
  end stop
end TransitionBuilder
