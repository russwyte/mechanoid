package mechanoid.dsl

import zio.*
import mechanoid.core.*
import mechanoid.visualization.{TransitionMeta, TransitionKind}

/** Builder for transitions from any state matching a parent type.
  *
  * This enables defining transitions that apply to all leaf states under a sealed parent trait. For example:
  * {{{
  * .whenAny[InReview].on(Cancel).goto(Cancelled)
  * }}}
  *
  * This would expand to transitions from all leaf states under `InReview` (e.g., PendingReview, UnderReview,
  * ChangesRequested) to `Cancelled` when the `Cancel` event is received.
  *
  * Transitions defined with `whenAny` can be overridden by more specific leaf-level transitions defined later:
  * {{{
  * .whenAny[InReview].on(Cancel).goto(Cancelled)        // Default for all InReview states
  * .when(UnderReview).on(Cancel).goto(ReviewCancelled)  // Override for UnderReview specifically
  * }}}
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  * @tparam Cmd
  *   The command type
  */
final class WhenAnyBuilder[S <: MState, E <: MEvent, Cmd](
    private val definition: FSMDefinition[S, E, Cmd],
    private val stateHashes: Set[Int],
):

  /** Define a transition triggered by a specific event for all matching states. */
  def on(event: E): MultiTransitionBuilder[S, E, Cmd] =
    new MultiTransitionBuilder(definition, stateHashes, event.timed)

  /** Define a transition triggered by the timeout event for all matching states. */
  def onTimeout: MultiTransitionBuilder[S, E, Cmd] =
    new MultiTransitionBuilder(definition, stateHashes, Timed.TimeoutEvent)
end WhenAnyBuilder

/** Builder for configuring a transition that applies to multiple source states.
  *
  * Similar to TransitionBuilder but adds transitions for all states in the provided set.
  */
final class MultiTransitionBuilder[S <: MState, E <: MEvent, Cmd](
    private val definition: FSMDefinition[S, E, Cmd],
    private val stateHashes: Set[Int],
    private val event: Timed[E],
):
  private val eventCaseHash: Int = definition.eventEnum.caseHash(event)

  /** Transition to the specified target state from all matching source states. */
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

  /** Stay in the current state (no transition) for all matching source states. */
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

  /** Stop the FSM from all matching source states. */
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

  /** Stop the FSM with a reason from all matching source states. */
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
end MultiTransitionBuilder
