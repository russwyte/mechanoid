package mechanoid.core

import java.time.Instant

/** The runtime state container for an FSM.
  *
  * This holds the current state along with metadata about the FSM's execution.
  *
  * @tparam S
  *   The state type
  * @param current
  *   The current state
  * @param history
  *   List of previous states (most recent first)
  * @param stateData
  *   Arbitrary key-value data associated with the FSM
  * @param startedAt
  *   When the FSM was started
  * @param lastTransitionAt
  *   When the last state transition occurred
  */
final case class FSMState[S](
    current: S,
    history: List[S] = Nil,
    stateData: Map[String, Any] = Map.empty,
    startedAt: Instant,
    lastTransitionAt: Instant,
):
  /** Get the previous state, if any. */
  def previousState: Option[S] = history.headOption

  /** Get the number of transitions that have occurred. */
  def transitionCount: Int = history.length

  /** Update to a new current state, pushing the old state to history. */
  def transitionTo(newState: S, at: Instant): FSMState[S] =
    copy(
      current = newState,
      history = current :: history,
      lastTransitionAt = at,
    )

  /** Store a value in the state data. */
  def withData(key: String, value: Any): FSMState[S] =
    copy(stateData = stateData + (key -> value))

  /** Retrieve a value from state data. */
  def getData[A](key: String): Option[A] =
    stateData.get(key).map(_.asInstanceOf[A])
end FSMState

object FSMState:
  /** Create an initial FSM state. */
  def initial[S](state: S): FSMState[S] =
    val now = Instant.now()
    FSMState(
      current = state,
      history = Nil,
      stateData = Map.empty,
      startedAt = now,
      lastTransitionAt = now,
    )
end FSMState
