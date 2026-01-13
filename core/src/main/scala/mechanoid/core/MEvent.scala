package mechanoid.core

/** Base trait for all FSM events.
  *
  * User-defined events should extend this trait, typically using Scala 3 enums:
  * {{{
  * enum MyEvent extends MEvent:
  *   case Start, Stop, Pause
  * }}}
  */
trait MEvent

object MEvent:

  /** Automatically derive SealedEnum for any sealed MEvent subtype.
    *
    * This enables users to simply write:
    * {{{
    * enum MyEvent extends MEvent:
    *   case Start, Stop
    * }}}
    *
    * Without needing to explicitly derive anything.
    *
    * Also supports hierarchical event definitions with nested sealed traits. The macro recursively discovers all leaf
    * cases (non-sealed case classes/objects).
    */
  inline given sealedEnum[E <: MEvent]: SealedEnum[E] =
    SealedEnum.derived

  /** Extension method providing caseHash access via typeclass. */
  extension [E <: MEvent](e: E)(using se: SealedEnum[E]) private[mechanoid] inline def fsmCaseHash: Int = se.caseHash(e)

  /** Wrap an event as a Timed event for use with EventStore. Internal use only. */
  extension [E <: MEvent](e: E) private[mechanoid] def timed: Timed[E] = Timed.UserEvent(e)

  /** Derive a SealedEnum for an event type with a specific hasher.
    *
    * Use this when the default hasher produces collisions (the compiler will tell you):
    *
    * {{{
    * enum MyEvent extends MEvent:
    *   case Start, Stop
    *
    * object MyEvent:
    *   // The type is inferred - you don't need to reference SealedEnum
    *   given murmur3Hasher = MEvent.deriveWithHasher[MyEvent](CaseHasher.Murmur3)
    * }}}
    */
  inline def deriveWithHasher[E <: MEvent](inline hasher: CaseHasher): SealedEnum[E] =
    SealedEnum.deriveWithHasher[E](hasher)
end MEvent

/** Sealed ADT for events that include the built-in Timeout event.
  *
  * This sealed trait allows FSM transitions to handle both user-defined events and timeout events uniformly, with full
  * type safety and no unchecked casts.
  *
  * This is an internal type - user events are wrapped explicitly at API boundaries.
  */
sealed trait Timed[+E <: MEvent] extends MEvent

object Timed:
  /** Wrapper for user-defined events. Internal only - use extension methods to access. */
  private[mechanoid] final case class UserEvent[E <: MEvent](event: E) extends Timed[E]

  /** Singleton for timeout events. Internal only - use isTimeout to check. */
  private[mechanoid] case object TimeoutEvent extends Timed[Nothing]

  /** Derive SealedEnum for Timed event types using the base event's SealedEnum. */
  given sealedEnum[E <: MEvent](using base: SealedEnum[E]): SealedEnum[Timed[E]] with
    import mechanoid.macros.SealedEnumMacros.{CaseInfo, HierarchyInfo}

    def caseHash(value: Timed[E]): Int = value match
      case TimeoutEvent => Timeout.CaseHash
      case UserEvent(e) => base.caseHash(e)

    val caseInfos: Array[CaseInfo] =
      base.caseInfos :+ CaseInfo("Timeout", "mechanoid.core.Timeout", Timeout.CaseHash)

    val caseNames: Map[Int, String] =
      base.caseNames + (Timeout.CaseHash -> "Timeout")

    // Timed is a flat wrapper, so hierarchy comes from the base event
    val hierarchyInfo: HierarchyInfo = base.hierarchyInfo

    override def nameFor(hash: Int): String =
      if hash == Timeout.CaseHash then "Timeout"
      else base.nameFor(hash)
  end sealedEnum

  /** Extension method providing caseHash access for timed events. */
  extension [E <: MEvent](e: Timed[E])(using base: SealedEnum[E])
    private[mechanoid] inline def fsmCaseHash: Int = e match
      case TimeoutEvent  => Timeout.CaseHash
      case UserEvent(ev) => base.caseHash(ev)

  /** Extension methods that don't require SealedEnum. */
  extension [E <: MEvent](e: Timed[E])
    /** Extract the underlying event if it's a user event. */
    def userEvent: Option[E] = e match
      case UserEvent(ev) => Some(ev)
      case TimeoutEvent  => None

    /** Check if this is a timeout event. */
    def isTimeout: Boolean = e match
      case TimeoutEvent => true
      case _            => false
  end extension
end Timed

/** Built-in timeout event.
  *
  * This event is automatically sent when a state's timeout duration elapses. Use this in transitions to define what
  * happens when a timeout fires:
  *
  * {{{
  * // Set timeout duration on a state
  * Processing @@ timeout(30.seconds)
  *
  * // Define what happens when timeout fires
  * Processing via Timeout to TimedOut
  * }}}
  */
case object Timeout extends MEvent:
  /** Stable hash for the Timeout event, computed from its fully qualified name. */
  val CaseHash: Int = "mechanoid.core.Timeout".hashCode
