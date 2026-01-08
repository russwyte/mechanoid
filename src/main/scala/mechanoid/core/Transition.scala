package mechanoid.core

import zio.*
import scala.concurrent.duration.Duration

/** A transition definition from one state to another via an event.
  *
  * @tparam S
  *   The base state type
  * @tparam E
  *   The event type that triggers this transition
  * @tparam R
  *   The ZIO environment required
  * @tparam Err
  *   The error type
  * @param guard
  *   Optional predicate that must be true for the transition to occur
  * @param action
  *   The effect to execute, returning the transition result
  * @param description
  *   Optional human-readable description of this transition
  */
final case class Transition[-S <: MState, -E <: MEvent, +S2 <: MState, -R, +Err](
    guard: Option[ZIO[R, Err, Boolean]],
    action: ZIO[R, Err, TransitionResult[S2]],
    description: Option[String] = None,
)

object Transition:
  /** Create a simple transition that goes to a target state. */
  def goto[S <: MState, E <: MEvent, S2 <: MState](target: S2): Transition[S, E, S2, Any, Nothing] =
    Transition(None, ZIO.succeed(TransitionResult.Goto(target)), None)

  /** Create a transition that stays in the current state. */
  def stay[S <: MState, E <: MEvent]: Transition[S, E, S, Any, Nothing] =
    Transition(None, ZIO.succeed(TransitionResult.Stay), None)

/** Entry and exit actions for a state.
  *
  * @tparam S
  *   The state type
  * @tparam R
  *   The ZIO environment required
  * @tparam Err
  *   The error type
  */
final case class StateLifecycle[-S <: MState, -R, +Err](
    onEntry: Option[ZIO[R, Err, Unit]] = None,
    onExit: Option[ZIO[R, Err, Unit]] = None,
)

object StateLifecycle:
  def empty[S <: MState]: StateLifecycle[S, Any, Nothing] =
    StateLifecycle(None, None)

/** Timeout configuration for a state.
  *
  * When the FSM enters a state with a timeout, if no event is received within the duration, the timeout action is
  * automatically executed.
  *
  * @tparam S
  *   The state type this timeout applies to
  * @tparam S2
  *   The potential target state type
  * @tparam R
  *   The ZIO environment required
  * @tparam Err
  *   The error type
  */
final case class StateTimeout[-S <: MState, +S2 <: MState, -R, +Err](
    duration: Duration,
    action: ZIO[R, Err, TransitionResult[S2]],
)
