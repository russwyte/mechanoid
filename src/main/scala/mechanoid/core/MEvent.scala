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
  /** Extension method providing ordinal access via typeclass, avoiding name collision with Scala 3 enum's ordinal. */
  extension [E <: MEvent](e: E)(using se: SealedEnum[E]) private[mechanoid] inline def fsmOrdinal: Int = se.ordinal(e)

/** Sealed ADT for events that include the built-in Timeout event.
  *
  * This sealed trait allows FSM transitions to handle both user-defined events and timeout events uniformly, with full
  * type safety and no unchecked casts.
  *
  * User events are automatically wrapped via implicit conversion, so users can write:
  * {{{
  * fsm.send(MyEvent.Start)  // automatically becomes Timed.UserEvent(MyEvent.Start)
  * }}}
  */
sealed trait Timed[+E <: MEvent] extends MEvent

object Timed:
  /** Wrapper for user-defined events. Internal only - use extension methods to access. */
  private[mechanoid] final case class UserEvent[E <: MEvent](event: E) extends Timed[E]

  /** Singleton for timeout events. Internal only - use isTimeout to check. */
  private[mechanoid] case object TimeoutEvent extends Timed[Nothing]

  /** Automatic conversion from user events to Timed - enables transparent API. */
  given userEventConversion[E <: MEvent]: Conversion[E, Timed[E]] = UserEvent(_)

  /** Conversion from Timeout singleton to Timed. */
  given timeoutConversion: Conversion[Timeout.type, Timed[Nothing]] = _ => TimeoutEvent

  /** Derive SealedEnum for Timed event types using the base event's SealedEnum. */
  given sealedEnum[E <: MEvent](using base: SealedEnum[E]): SealedEnum[Timed[E]] with
    def ordinal(value: Timed[E]): Int = value match
      case TimeoutEvent => Timeout.Ordinal
      case UserEvent(e) => base.ordinal(e)

    val caseNames: Array[String] = base.caseNames :+ "Timeout"

    override def nameFor(ordinal: Int): String =
      if ordinal == Timeout.Ordinal then "Timeout"
      else base.nameFor(ordinal)

  /** Extension method providing ordinal access for timed events. */
  extension [E <: MEvent](e: Timed[E])(using base: SealedEnum[E])
    private[mechanoid] inline def fsmOrdinal: Int = e match
      case TimeoutEvent  => Timeout.Ordinal
      case UserEvent(ev) => base.ordinal(ev)

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
end Timed

/** Event that carries a payload.
  *
  * Useful for events that need to pass data:
  * {{{
  * case class DataReceived(payload: Array[Byte]) extends MEvent with EventData[Array[Byte]]:
  *   def data: Array[Byte] = payload
  * }}}
  */
trait EventData[D]:
  def data: D

/** Built-in timeout event.
  *
  * This event is automatically sent when a state's timeout duration elapses. Users can handle it like any other event
  * in their transition definitions.
  */
case object Timeout extends MEvent:
  val Ordinal: Int = -1
