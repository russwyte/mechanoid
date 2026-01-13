package mechanoid.machine

import mechanoid.core.*

// ============================================
// Terminal objects for non-goto transitions
// ============================================

/** Terminal object for stay transitions.
  *
  * Usage: `A via E1 to stay`
  */
object stay

/** Terminal object for stop transitions.
  *
  * Usage: `A via E1 to stop` or `A via E1 to stop("reason")`
  */
object stop:
  def apply(reason: String): StopReason = StopReason(reason)

/** Wrapper for stop with a reason. */
final case class StopReason(reason: String)

// ============================================
// Matcher types for hierarchical matching
// ============================================

/** Matcher for all children of a sealed parent type.
  *
  * Created by `all[Parent]` macro. Contains pre-computed hashes of all leaf children.
  *
  * Usage: `all[ParentState] via Event to Target`
  */
final class AllMatcher[T](
    val hashes: Set[Int],
    val names: List[String],
)

/** Matcher for specific state values.
  *
  * Created by `anyOf(StateA, StateB, ...)`. Contains the actual values and their computed hashes.
  *
  * Usage: `anyOf(ChildA, ChildB) via Event to Target`
  */
final class AnyOfMatcher[S <: MState](
    val values: Seq[S],
    val hashes: Set[Int],
    val names: List[String],
)

/** Matcher for specific event values.
  *
  * Created by `anyOf(EventA, EventB, ...)`. Contains the actual values and their computed hashes.
  *
  * Usage: `State via anyOf(Click, Tap) to Target`
  */
final class AnyOfEventMatcher[E <: MEvent](
    val values: Seq[E],
    val hashes: Set[Int],
    val names: List[String],
)

// ============================================
// ViaBuilder - the intermediate builder
// ============================================

/** Builder after `State via Event`, ready for the `to` method.
  *
  * Contains pre-computed state and event hashes for efficient duplicate detection.
  */
final class ViaBuilder[S <: MState, E <: MEvent](
    val stateHashes: Set[Int],
    val eventHashes: Set[Int],
    val stateNames: List[String],
    val eventNames: List[String],
):
  /** Transition to a target state. */
  inline infix def to[S2 <: MState](target: S2): TransitionSpec[MState, MEvent, Nothing] =
    TransitionSpec.goto(stateHashes, eventHashes, stateNames, eventNames, target)

  /** Transition to a timed target state (starts timeout timer on entry).
    *
    * Usage:
    * {{{
    * val timedWaiting = Waiting @@ timeout(30.seconds)
    * Idle via Start to timedWaiting
    * }}}
    */
  inline infix def to[S2 <: MState](target: TimedTarget[S2]): TransitionSpec[MState, MEvent, Nothing] =
    TransitionSpec.gotoTimed(stateHashes, eventHashes, stateNames, eventNames, target)

  /** Alias for `to` using >> operator. */
  inline def >>[S2 <: MState](target: S2): TransitionSpec[MState, MEvent, Nothing] = to(target)

  /** Stay in the current state. */
  infix def to(terminal: stay.type): TransitionSpec[MState, MEvent, Nothing] =
    TransitionSpec.stay(stateHashes, eventHashes, stateNames, eventNames)

  /** Stop the FSM. */
  infix def to(terminal: stop.type): TransitionSpec[MState, MEvent, Nothing] =
    TransitionSpec.stop(stateHashes, eventHashes, stateNames, eventNames)

  /** Stop the FSM with a reason. */
  infix def to(terminal: StopReason): TransitionSpec[MState, MEvent, Nothing] =
    TransitionSpec.stop(stateHashes, eventHashes, stateNames, eventNames, Some(terminal.reason))
end ViaBuilder
