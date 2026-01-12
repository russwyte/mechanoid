package mechanoid.dsl

import zio.*
import mechanoid.core.*
import mechanoid.visualization.{TransitionMeta, TransitionKind}

/** Builder for configuring a transition that explicitly overrides a previous definition.
  *
  * Use `.override` on a TransitionBuilder when you intentionally want to replace a transition that was defined earlier
  * (e.g., when using `whenAny[T]` to set defaults and then overriding for specific states).
  *
  * The `build` macro detects `.override.goto()` patterns and allows these transitions to replace existing ones without
  * error.
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  * @tparam Cmd
  *   The command type
  */
final class OverrideTransitionBuilder[S <: MState, E <: MEvent, Cmd](
    private val definition: FSMDefinition[S, E, Cmd],
    private val fromStateCaseHash: Int,
    private val event: Timed[E],
):
  private val eventCaseHash: Int = definition.eventEnum.caseHash(event)

  /** Override transition to the specified target state. */
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

  /** Override to stay in the current state (no transition). */
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

  /** Override to stop the FSM. */
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

  /** Override to stop the FSM with a reason. */
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
end OverrideTransitionBuilder

/** Builder for configuring a multi-state transition that explicitly overrides previous definitions.
  *
  * Similar to OverrideTransitionBuilder but for transitions defined with `whenAny` or `whenStates`.
  */
final class OverrideMultiTransitionBuilder[S <: MState, E <: MEvent, Cmd](
    private val definition: FSMDefinition[S, E, Cmd],
    private val stateHashes: Set[Int],
    private val event: Timed[E],
):
  private val eventCaseHash: Int = definition.eventEnum.caseHash(event)

  /** Override transition to the specified target state from all matching source states. */
  def goto(targetState: S): FSMDefinition[S, E, Cmd] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Goto(targetState))
    val transition = Transition[S, Timed[E], S](action, None)
    val targetHash = definition.stateEnum.caseHash(targetState)

    stateHashes.foldLeft(definition) { (defn, fromStateHash) =>
      val meta = TransitionMeta(
        fromStateHash,
        eventCaseHash,
        Some(targetHash),
        TransitionKind.Goto,
      )
      defn.addTransitionWithMeta(fromStateHash, event, transition, meta)
    }
  end goto

  /** Override to stay in the current state (no transition) for all matching source states. */
  def stay: FSMDefinition[S, E, Cmd] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stay)
    val transition = Transition[S, Timed[E], S](action, None)

    stateHashes.foldLeft(definition) { (defn, fromStateHash) =>
      val meta = TransitionMeta(
        fromStateHash,
        eventCaseHash,
        None,
        TransitionKind.Stay,
      )
      defn.addTransitionWithMeta(fromStateHash, event, transition, meta)
    }
  end stay

  /** Override to stop the FSM from all matching source states. */
  def stop: FSMDefinition[S, E, Cmd] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stop(None))
    val transition = Transition[S, Timed[E], S](action, None)

    stateHashes.foldLeft(definition) { (defn, fromStateHash) =>
      val meta = TransitionMeta(
        fromStateHash,
        eventCaseHash,
        None,
        TransitionKind.Stop(None),
      )
      defn.addTransitionWithMeta(fromStateHash, event, transition, meta)
    }
  end stop

  /** Override to stop the FSM with a reason from all matching source states. */
  def stop(reason: String): FSMDefinition[S, E, Cmd] =
    val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stop(Some(reason)))
    val transition = Transition[S, Timed[E], S](action, None)

    stateHashes.foldLeft(definition) { (defn, fromStateHash) =>
      val meta = TransitionMeta(
        fromStateHash,
        eventCaseHash,
        None,
        TransitionKind.Stop(Some(reason)),
      )
      defn.addTransitionWithMeta(fromStateHash, event, transition, meta)
    }
  end stop
end OverrideMultiTransitionBuilder
