package mechanoid.persistence.lock

import java.time.Instant
import zio.Duration

/** Errors related to FSM instance locking. */
enum LockError extends Exception:
  /** Lock is currently held by another node. */
  case LockBusy(instanceId: String, heldBy: String, until: Instant)

  /** Timed out waiting to acquire the lock. */
  case LockTimeout(instanceId: String, waitedFor: Duration)

  /** Failed to acquire lock due to an underlying error. */
  case LockAcquisitionFailed(instanceId: String, cause: Throwable)

  /** Failed to release lock due to an underlying error. */
  case LockReleaseFailed(instanceId: String, cause: Throwable)

  override def getMessage: String = this match
    case LockBusy(id, heldBy, until) =>
      s"Lock for FSM instance '$id' is held by node '$heldBy' until $until"
    case LockTimeout(id, waitedFor) =>
      s"Timed out after $waitedFor waiting for lock on FSM instance '$id'"
    case LockAcquisitionFailed(id, cause) =>
      s"Failed to acquire lock for FSM instance '$id': ${cause.getMessage}"
    case LockReleaseFailed(id, cause) =>
      s"Failed to release lock for FSM instance '$id': ${cause.getMessage}"

  override def getCause: Throwable = this match
    case LockAcquisitionFailed(_, cause) => cause
    case LockReleaseFailed(_, cause)     => cause
    case _                               => null
end LockError
