package mechanoid.persistence.lock

import java.time.Instant

/** Result of attempting to acquire a lock on an FSM instance. */
sealed trait LockResult[Id]

object LockResult:
  /** Lock successfully acquired. */
  final case class Acquired[Id](token: LockToken[Id]) extends LockResult[Id]

  /** Lock is held by another node. */
  final case class Busy[Id](heldBy: String, until: Instant) extends LockResult[Id]

  /** Lock acquisition timed out while waiting. */
  final case class TimedOut[Id]() extends LockResult[Id]

  /** Check if the result indicates success. */
  extension [Id](result: LockResult[Id])
    def isAcquired: Boolean = result match
      case _: Acquired[?] => true
      case _              => false

    def isBusy: Boolean = result match
      case _: Busy[?] => true
      case _          => false

    def tokenOption: Option[LockToken[Id]] = result match
      case Acquired(token) => Some(token)
      case _               => None
  end extension
end LockResult
