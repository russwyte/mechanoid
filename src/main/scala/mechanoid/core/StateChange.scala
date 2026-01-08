package mechanoid.core

import java.time.Instant

/** Notification of a state change in the FSM.
  *
  * Published to observers whenever the FSM transitions from one state to another.
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  * @param from
  *   The state before the transition
  * @param to
  *   The state after the transition
  * @param triggeredBy
  *   The event that caused this transition (Timeout for timeout-triggered)
  * @param timestamp
  *   When the transition occurred
  */
final case class StateChange[S <: MState, E <: MEvent](
    from: S,
    to: S,
    triggeredBy: E,
    timestamp: Instant,
):
  /** Check if this was a timeout-triggered transition. */
  def isTimeoutTriggered: Boolean = triggeredBy == Timeout

  /** Check if this transition was to a different state (not a self-transition). */
  def isRealTransition: Boolean = from != to
end StateChange
