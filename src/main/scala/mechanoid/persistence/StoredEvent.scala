package mechanoid.persistence

import mechanoid.core.*
import java.time.Instant

/** A persisted event wrapper for event sourcing.
  *
  * Each event sent to a persistent FSM is stored with metadata that allows for state reconstruction and auditing.
  *
  * @tparam Id
  *   The FSM instance identifier type
  * @tparam E
  *   The event type
  * @param instanceId
  *   The FSM instance this event belongs to
  * @param sequenceNr
  *   Monotonically increasing sequence number for ordering
  * @param event
  *   The actual event that was processed
  * @param timestamp
  *   When the event was persisted
  */
final case class StoredEvent[Id, E <: MEvent](
    instanceId: Id,
    sequenceNr: Long,
    event: E,
    timestamp: Instant,
)
