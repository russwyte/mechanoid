package mechanoid.core

import zio.*
import scala.concurrent.duration.Duration

/** A transition definition from one state to another via an event.
  *
  * All transition actions return `MechanoidError` as the error type. User errors are wrapped in `ActionFailedError`.
  *
  * @tparam S
  *   The base state type
  * @tparam E
  *   The event type that triggers this transition
  * @tparam S2
  *   The target state type
  * @param action
  *   A function that receives the current state and event, returning the transition result
  * @param description
  *   Optional human-readable description of this transition
  */
final case class Transition[-S <: MState, -E <: MEvent, +S2 <: MState](
    action: (S, E) => ZIO[Any, MechanoidError, TransitionResult[S2]],
    description: Option[String] = None,
)

object Transition:
  /** Create a simple transition that goes to a target state. */
  def goto[S <: MState, E <: MEvent, S2 <: MState](target: S2): Transition[S, E, S2] =
    Transition((_, _) => ZIO.succeed(TransitionResult.Goto(target)), None)

  /** Create a transition that stays in the current state. */
  def stay[S <: MState, E <: MEvent]: Transition[S, E, S] =
    Transition((_, _) => ZIO.succeed(TransitionResult.Stay), None)

/** Entry and exit actions for a state.
  *
  * All lifecycle actions return `MechanoidError` as the error type. User errors are wrapped in `ActionFailedError`.
  *
  * @tparam S
  *   The state type
  * @param onEntry
  *   Action to execute when entering this state
  * @param onExit
  *   Action to execute when exiting this state
  * @param onEntryDescription
  *   Human-readable description of entry action (for visualization)
  * @param onExitDescription
  *   Human-readable description of exit action (for visualization)
  */
final case class StateLifecycle[-S <: MState](
    onEntry: Option[ZIO[Any, MechanoidError, Unit]] = None,
    onExit: Option[ZIO[Any, MechanoidError, Unit]] = None,
    onEntryDescription: Option[String] = None,
    onExitDescription: Option[String] = None,
)

object StateLifecycle:
  def empty[S <: MState]: StateLifecycle[S] =
    StateLifecycle(None, None, None, None)

/** Timeout configuration for a state.
  *
  * When the FSM enters a state with a timeout, if no event is received within the duration, the timeout action is
  * automatically executed.
  *
  * @tparam S
  *   The state type this timeout applies to
  * @tparam S2
  *   The potential target state type
  */
final case class StateTimeout[-S <: MState, +S2 <: MState](
    duration: Duration,
    action: ZIO[Any, MechanoidError, TransitionResult[S2]],
)
