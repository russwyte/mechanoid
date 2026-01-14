package mechanoid.persistence

import java.time.Instant

/** A point-in-time snapshot of FSM state for optimization.
  *
  * Snapshots allow faster state reconstruction by avoiding full event replay. The FSM can load the latest snapshot and
  * only replay events that occurred after it.
  *
  * @tparam Id
  *   The FSM instance identifier type
  * @tparam S
  *   The state type
  * @param instanceId
  *   The FSM instance this snapshot belongs to
  * @param state
  *   The state at snapshot time
  * @param sequenceNr
  *   The sequence number of the last event included in this snapshot
  * @param timestamp
  *   When the snapshot was taken
  */
final case class FSMSnapshot[Id, S](
    instanceId: Id,
    state: S,
    sequenceNr: Long,
    timestamp: Instant,
)
