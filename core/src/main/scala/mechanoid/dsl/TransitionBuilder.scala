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
    private val fromStateCaseHash: Int,
    private val event: Timed[E],
):
  private val eventCaseHash: Int = definition.eventEnum.caseHash(event)

  /** Transition to the specified target state. */
  def goto(targetState: S): FSMDefinition[S, E, Cmd] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Goto(targetState))
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateCaseHash,
      eventCaseHash,
      Some(definition.stateEnum.caseHash(targetState)),
      TransitionKind.Goto,
    )
    definition.addTransitionWithMeta(fromStateCaseHash, event, transition, meta)
  end goto

  /** Stay in the current state (no transition). */
  def stay: FSMDefinition[S, E, Cmd] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stay)
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateCaseHash,
      eventCaseHash,
      None,
      TransitionKind.Stay,
    )
    definition.addTransitionWithMeta(fromStateCaseHash, event, transition, meta)
  end stay

  /** Stop the FSM. */
  def stop: FSMDefinition[S, E, Cmd] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stop(None))
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateCaseHash,
      eventCaseHash,
      None,
      TransitionKind.Stop(None),
    )
    definition.addTransitionWithMeta(fromStateCaseHash, event, transition, meta)
  end stop

  /** Stop the FSM with a reason. */
  def stop(reason: String): FSMDefinition[S, E, Cmd] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stop(Some(reason)))
    val transition = Transition[S, Timed[E], S](action, None)
    val meta       = TransitionMeta(
      fromStateCaseHash,
      eventCaseHash,
      None,
      TransitionKind.Stop(Some(reason)),
    )
    definition.addTransitionWithMeta(fromStateCaseHash, event, transition, meta)
  end stop

  /** Mark this transition as an intentional override.
    *
    * Use this when you want to replace a transition that was defined earlier (e.g., when using `whenAny[T]` to set
    * defaults and then overriding for specific states).
    *
    * Example:
    * {{{
    * build {
    *   fsm[MyState, MyEvent]
    *     .whenAny[Parent].on(Cancel).goto(Draft)           // Default for all
    *     .when(SpecificState).on(Cancel).override.goto(Special)  // Intentional override
    * }
    * }}}
    */
  def `override`: OverrideTransitionBuilder[S, E, Cmd] =
    new OverrideTransitionBuilder(definition, fromStateCaseHash, event)
end TransitionBuilder
