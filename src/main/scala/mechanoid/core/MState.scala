package mechanoid.core

/** Base trait for all FSM states.
  *
  * User-defined states should extend this trait, typically using Scala 3 enums:
  * {{{
  * enum MyState extends MState:
  *   case Idle, Running, Stopped
  * }}}
  *
  * States can be simple enum cases or carry data (rich states):
  * {{{
  * enum OrderState extends MState:
  *   case Pending
  *   case Paid(transactionId: String)
  *   case Failed(reason: String)
  * }}}
  *
  * When defining transitions, the state's "shape" (which case it is) is used for matching, not the exact value. This
  * means `.when(Failed(""))` will match ANY `Failed(_)` state.
  */
trait MState

object MState:
  /** Extension method providing ordinal access via typeclass, avoiding name collision with Scala 3 enum's ordinal. */
  extension [S <: MState](s: S)(using se: SealedEnum[S])
    private[mechanoid] inline def fsmOrdinal: Int = se.ordinal(s)

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

/** Extracts the "shape" (ordinal) from a state for pattern matching.
  *
  * For sealed hierarchies (enums), the ordinal identifies which case a state is, ignoring any data it carries. This
  * enables matching on `Failed(_)` without caring about the specific reason.
  */
object StateShape:
  /** Extract ordinal from a state using SealedEnum.
    *
    * This is captured at FSMDefinition creation time when the concrete state type is known.
    */
  def ordinalOf[S <: MState](state: S)(using se: SealedEnum[S]): Int =
    se.ordinal(state)
