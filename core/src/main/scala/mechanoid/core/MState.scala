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

  /** Automatically derive SealedEnum for any sealed MState subtype.
    *
    * This enables users to simply write:
    * {{{
    * enum MyState extends MState:
    *   case Idle, Running
    * }}}
    *
    * Without needing to explicitly derive anything.
    *
    * Also supports hierarchical state definitions with nested sealed traits:
    * {{{
    * sealed trait OrderState extends MState
    * case object Pending extends OrderState
    * sealed trait Processing extends OrderState
    *   case object Validating extends Processing
    *   case object Charging extends Processing
    * case object Completed extends OrderState
    * }}}
    *
    * The macro recursively discovers all leaf cases (non-sealed case classes/objects).
    */
  inline given sealedEnum[S <: MState]: SealedEnum[S] =
    SealedEnum.derived

  /** Extension method providing caseHash access via typeclass. */
  extension [S <: MState](s: S)(using se: SealedEnum[S]) private[mechanoid] inline def fsmCaseHash: Int = se.caseHash(s)

  /** Derive a SealedEnum for a state type with a specific hasher.
    *
    * Use this when the default hasher produces collisions (the compiler will tell you):
    *
    * {{{
    * enum MyState extends MState:
    *   case Idle, Running
    *
    * object MyState:
    *   // The type is inferred - you don't need to reference SealedEnum
    *   given murmur3Hasher = MState.deriveWithHasher[MyState](CaseHasher.Murmur3)
    * }}}
    */
  inline def deriveWithHasher[S <: MState](inline hasher: CaseHasher): SealedEnum[S] =
    SealedEnum.deriveWithHasher[S](hasher)
end MState
