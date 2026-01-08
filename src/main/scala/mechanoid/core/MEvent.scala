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
case object Timeout extends MEvent
