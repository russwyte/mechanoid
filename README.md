# Mechanoid

A type-safe, effect-oriented finite state machine library for Scala 3 built on ZIO.

## Features

- **Declarative DSL** - Fluent builder API for defining state machines
- **Type-safe** - States and events are Scala 3 enums with compile-time validation
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

// Build FSM definition (UFSM = pure FSM, no environment, no errors)
val orderFSM = UFSM[OrderState, OrderEvent]
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
- [Defining FSMs](docs/DOCUMENTATION.md#defining-fsms) - Guards, actions, timeouts
- [Running FSMs](docs/DOCUMENTATION.md#running-fsms) - Runtime, sending events
- [Persistence](docs/DOCUMENTATION.md#persistence) - Event sourcing, snapshots, recovery
- [Durable Timeouts](docs/DOCUMENTATION.md#durable-timeouts) - TimeoutStore, sweepers, leader election
- [Distributed Locking](docs/DOCUMENTATION.md#distributed-locking) - Exactly-once transitions, FSMInstanceLock
- [Command Queue](docs/DOCUMENTATION.md#command-queue) - Transactional outbox for side effects
- [Type-Level Safety](docs/DOCUMENTATION.md#type-level-safety) - Compile-time transition validation

## Key Components

| Component | Description |
|-----------|-------------|
| `FSMDefinition` | Builder for defining state machines (use type aliases below) |
| `UFSM` | Pure FSM - no environment, no errors (`FSMDefinition[S, E, Any, Nothing]`) |
| `TaskFSM` | FSM with Throwable errors (`FSMDefinition[S, E, Any, Throwable]`) |
| `IOFSM` | FSM with custom errors (`FSMDefinition[S, E, Any, Err]`) |
| `URFSM` | FSM with environment, no errors (`FSMDefinition[S, E, R, Nothing]`) |
| `RFSM` | FSM with environment and Throwable (`FSMDefinition[S, E, R, Throwable]`) |
| `FSMRuntime` | In-memory FSM execution |
| `PersistentFSMRuntime` | Event-sourced FSM with persistence |
| `TimeoutSweeper` | Background service for durable timeouts |
| `FSMInstanceLock` | Distributed locking for exactly-once transitions |
| `LeaderElection` | Lease-based coordination for single-active mode |
| `CommandWorker` | Background processor for side effect commands |

## Example with Persistence

```scala
import mechanoid.persistence.*

val program = ZIO.scoped {
  for
    fsm <- PersistentFSMRuntime(orderId, orderDefinition, Pending)
    _   <- fsm.send(Pay)      // Persisted to EventStore
    _   <- fsm.saveSnapshot   // Optional: snapshot for faster recovery
  yield ()
}.provide(eventStoreLayer)
```

## Example with Durable Timeouts

```scala
import mechanoid.persistence.timeout.*

// FSM with timeout that survives node failures
val definition = UFSM[State, Event]
  .when(AwaitingPayment).onTimeout.goto(Cancelled)
  .withTimeout(AwaitingPayment, 30.minutes)

val program = ZIO.scoped {
  for
    fsm <- PersistentFSMRuntime.withDurableTimeouts(id, definition, Pending)
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
import mechanoid.persistence.lock.*

// Exactly-once transitions across multiple nodes
val program = ZIO.scoped {
  for
    fsm <- PersistentFSMRuntime.withLocking(orderId, definition, Pending)
    _   <- fsm.send(Pay)  // Lock acquired automatically
  yield ()
}.provide(eventStoreLayer, lockLayer)

// Combine with durable timeouts for maximum robustness
val robustProgram = ZIO.scoped {
  for
    fsm <- PersistentFSMRuntime.withLockingAndTimeouts(
      orderId, definition, Pending, LockConfig.default
    )
    _   <- fsm.send(StartPayment)
  yield ()
}.provide(eventStoreLayer, timeoutStoreLayer, lockLayer)
```

## Requirements

- Scala 3.x
- ZIO 2.x

## License

[Apache 2.0](LICENSE)

