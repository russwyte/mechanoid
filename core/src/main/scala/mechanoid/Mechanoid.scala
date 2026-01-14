package mechanoid

import mechanoid.core.*

object Mechanoid

/** Utility for generating idempotency keys for commands.
  *
  * Use this to create deterministic, collision-resistant keys for command deduplication in the transactional outbox
  * pattern.
  *
  * ==Usage==
  * {{{
  * for
  *   outcome <- fsmRuntime.send(event)
  *   seqNr <- fsmRuntime.lastSequenceNr
  *   _ <- ZIO.foreachDiscard(outcome.allCommands.zipWithIndex) { (cmd, idx) =>
  *     val key = CommandKey.fromEvent(event, seqNr, idx, "all")
  *     commandStore.enqueue(instanceId, cmd, key)
  *   }
  * yield outcome
  * }}}
  */
object CommandKey:
  /** Create a deterministic idempotency key for a command.
    *
    * @param event
    *   The triggering event
    * @param seqNr
    *   The sequence number when the event was persisted
    * @param index
    *   The command's index within the list (for multiple commands from one transition)
    * @param phase
    *   Either "pre" or "post" to distinguish command generation phase
    * @return
    *   A deterministic key string suitable for idempotency checks
    */
  def fromEvent[E](event: E, seqNr: Long, index: Int, phase: String): String =
    val eventTypeName = event.getClass.getName
    val dataHash      = event.hashCode
    s"cmd-$phase-${eventTypeName.hashCode}-$dataHash-$seqNr-$index"
end CommandKey

// Re-export core types
export core.Finite
export core.TransitionResult
export core.TransitionOutcome
export core.Transition
export core.StateLifecycle
export core.StateTimeout
export core.FSMState
export core.MechanoidError
export core.InvalidTransitionError
export core.FSMStoppedError
export core.ActionFailedError
export core.ProcessingTimeoutError
export core.CaseHasher

// Re-export suite-style DSL
export machine.Machine
export machine.build
export machine.buildAll
export machine.all
export machine.anyOf
export machine.Aspect
export machine.stay
export machine.stop
export machine.TransitionSpec as MachineTransitionSpec
export machine.TimeoutSpec
export machine.ViaBuilder
export machine.AllMatcher
export machine.AnyOfMatcher
export machine.AnyOfEventMatcher
export machine.include

// Re-export runtime
export runtime.FSMRuntime

// Re-export type-level
export typelevel.ValidTransition
export typelevel.TransitionSpec
export typelevel.Allow
export typelevel.TNil
export typelevel.::
export typelevel.TransitionSpecBuilder

// Re-export persistence
export persistence.StoredEvent
export persistence.FSMSnapshot
export persistence.EventStore
export core.PersistenceError
export core.SequenceConflictError
export core.EventReplayError

// Re-export visualization
export visualization.TransitionKind
export visualization.TransitionMeta
export visualization.StateMeta
export visualization.FSMMeta
export visualization.TraceStep
export visualization.ExecutionTrace
export visualization.MermaidVisualizer
export visualization.GraphVizVisualizer

// Re-export redaction
export core.sensitive
export core.Redactor
export core.Redactor.redacted
export core.Redactor.redactedPretty

// Re-export command types
export core.PendingCommand
export core.CommandStatus
export core.CommandResult

// Re-export in-memory stores
export stores.InMemoryEventStore
export stores.InMemoryCommandStore
export stores.InMemoryTimeoutStore
export stores.InMemoryFSMInstanceLock
