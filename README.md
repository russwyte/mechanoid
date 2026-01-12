# Mechanoid

A type-safe, effect-oriented finite state machine library for Scala 3 built on ZIO.

## Features

- **Declarative DSL** - Fluent builder API for defining state machines
- **Type-safe** - States and events are Scala 3 enums or sealed traits with compile-time validation
- **Hierarchical states** - Organize complex state spaces with nested sealed traits
- **Effectful** - All transitions are ZIO effects with full environment and error support
- **Event sourcing** - Optional persistence with snapshots and optimistic locking
- **Durable timeouts** - Timeouts that survive node failures via database persistence
- **Distributed coordination** - Claim-based locking and optional leader election

## Quick Start

```scala
import mechanoid.*
import zio.*

// Define states and events
enum OrderState extends MState:
  case Pending, Paid, Shipped

enum OrderEvent extends MEvent:
  case Pay, Ship

// Build FSM definition
val orderFSM = fsm[OrderState, OrderEvent]
  .when(Pending).on(Pay).goto(Paid)
  .when(Paid).on(Ship).goto(Shipped)

// Run
val program = ZIO.scoped {
  for
    fsm <- orderFSM.build(Pending)
    _   <- fsm.send(Pay)
    _   <- fsm.send(Ship)
    s   <- fsm.currentState
  yield s // Shipped
}
```

## Documentation

See the [full documentation](docs/DOCUMENTATION.md) for:

- [Core Concepts](docs/DOCUMENTATION.md#core-concepts) - States, events, transitions
- [Defining FSMs](docs/DOCUMENTATION.md#defining-fsms) - Entry/exit actions, timeouts
- [Running FSMs](docs/DOCUMENTATION.md#running-fsms) - Runtime, sending events
- [Persistence](docs/DOCUMENTATION.md#persistence) - Event sourcing, snapshots, recovery
- [Durable Timeouts](docs/DOCUMENTATION.md#durable-timeouts) - TimeoutStore, sweepers, leader election
- [Distributed Locking](docs/DOCUMENTATION.md#distributed-locking) - Exactly-once transitions, FSMInstanceLock
- [Command Queue](docs/DOCUMENTATION.md#command-queue) - Transactional outbox for side effects
- [Type-Level Safety](docs/DOCUMENTATION.md#type-level-safety) - Compile-time transition validation

## Key Components

| Component | Description |
|-----------|-------------|
| `fsm[S, E]` | Entry point for defining state machines |
| `fsm.withCommands[S, E, Cmd]` | Entry point for FSMs with commands |
| `FSMDefinition[S, E, Cmd]` | Builder type for FSM definitions |
| `FSMRuntime[Id, S, E, Cmd]` | Unified FSM execution (in-memory or persistent) |
| `TimeoutSweeper` | Background service for durable timeouts |
| `FSMInstanceLock` | Distributed locking for exactly-once transitions |
| `LeaderElection` | Lease-based coordination for single-active mode |
| `CommandWorker` | Background processor for side effect commands |

## Example with Persistence

```scala
import mechanoid.runtime.FSMRuntime

val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, orderDefinition, Pending)
    _   <- fsm.send(Pay)      // Persisted to EventStore
    _   <- fsm.saveSnapshot   // Optional: snapshot for faster recovery
  yield ()
}.provide(eventStoreLayer)
```

## Example with Durable Timeouts

```scala
import mechanoid.runtime.FSMRuntime
import mechanoid.persistence.timeout.*

// FSM with timeout that survives node failures
val definition = fsm[State, Event]
  .when(AwaitingPayment).onTimeout.goto(Cancelled)
  .withTimeout(AwaitingPayment, 30.minutes)

val program = ZIO.scoped {
  for
    fsm <- FSMRuntime.withDurableTimeouts(id, definition, Pending)
    _   <- fsm.send(StartPayment)
    // Timeout persisted - another node's sweeper will fire it if this node dies
  yield ()
}.provide(eventStoreLayer, timeoutStoreLayer)

// Run sweeper (typically separate process)
val sweeper = TimeoutSweeper.make(
  TimeoutSweeperConfig()
    .withSweepInterval(5.seconds)
    .withJitterFactor(0.2),
  timeoutStore,
  TimeoutFiring.makeCallback(eventStore)
)
```

## Example with Distributed Locking

```scala
import mechanoid.runtime.FSMRuntime
import mechanoid.persistence.lock.*

// Exactly-once transitions across multiple nodes
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime.withLocking(orderId, definition, Pending)
    _   <- fsm.send(Pay)  // Lock acquired automatically
  yield ()
}.provide(eventStoreLayer, lockLayer)

// Combine with durable timeouts for maximum robustness
val robustProgram = ZIO.scoped {
  for
    fsm <- FSMRuntime.withLockingAndTimeouts(
      orderId, definition, Pending, LockConfig.default
    )
    _   <- fsm.send(StartPayment)
  yield ()
}.provide(eventStoreLayer, timeoutStoreLayer, lockLayer)
```

## Requirements

- Scala 3.x
- ZIO 2.x

## Development

After cloning, set up the pre-commit hook to check formatting:

```bash
git config core.hooksPath hooks
```

This enables a pre-commit hook that runs `sbt scalafmtCheckAll`. If formatting fails, run `sbt scalafmtAll` to fix it.

## License

[Apache 2.0](LICENSE)

