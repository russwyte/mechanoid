package mechanoid.core

/** The outcome of processing an event in the FSM.
  *
  * @tparam S
  *   The state type
  */
enum TransitionResult[+S]:
  /** Stay in the current state without transitioning. */
  case Stay

  /** Transition to a new state.
    *
    * @param state
    *   The target state to transition to
    */
  case Goto(state: S)

  /** Stop the FSM.
    *
    * @param reason
    *   Optional reason for stopping
    */
  case Stop(reason: Option[String] = None)
end TransitionResult

object TransitionResult:
  /** Convenience method to create a Stop result with a reason. */
  def stop[S](reason: String): TransitionResult[S] =
    Stop(Some(reason))

  /** Convenience method to create a Stop result without a reason. */
  def stop[S]: TransitionResult[S] =
    Stop(None)
