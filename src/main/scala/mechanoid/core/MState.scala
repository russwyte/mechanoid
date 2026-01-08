package mechanoid.core

/** Base trait for all FSM states.
  *
  * User-defined states should extend this trait, typically using Scala 3 enums:
  * {{{
  * enum MyState extends MState:
  *   case Idle, Running, Stopped
  * }}}
  */
trait MState

/** Marker trait for terminal states that cannot transition further. */
trait TerminalState extends MState

/** State that carries associated data.
  *
  * Useful for extended state machines where states need context:
  * {{{
  * case class Processing(itemsRemaining: Int) extends MState with StateData[Int]:
  *   def data: Int = itemsRemaining
  * }}}
  */
trait StateData[D]:
  def data: D
