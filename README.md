# Mechanoid

A type-safe, effect-oriented finite state machine library for Scala 3 built on ZIO.

## Features

- **Declarative DSL** - Clean infix syntax: `State via Event to Target`
- **Type-safe** - States and events are Scala 3 enums or sealed traits with compile-time validation
- **Hierarchical states** - Organize complex state spaces with nested sealed traits
- **Composable assemblies** - Build reusable FSM fragments and compose them with full compile-time validation
- **Effectful** - All transitions are ZIO effects with full environment and error support
- **Event sourcing** - Optional persistence with snapshots and optimistic locking
- **Durable timeouts** - Timeouts that survive node failures via database persistence
- **Distributed coordination** - Claim-based locking and optional leader election

## Quick Start

```scala
import mechanoid.*
import zio.*

// Define states and events as plain enums
enum OrderState derives Finite:
  case Pending, Paid, Shipped

enum OrderEvent derives Finite:
  case Pay, Ship

import OrderState.*, OrderEvent.*

// Create FSM with clean infix syntax and compile-time validation
val orderMachine = Machine(assembly[OrderState, OrderEvent](
  Pending via Pay to Paid,
  Paid via Ship to Shipped,
))

// Run
val program = ZIO.scoped {
  for
    fsm   <- orderMachine.start(Pending)
    _     <- fsm.send(Pay)
    _     <- fsm.send(Ship)
    state <- fsm.currentState
  yield state // Shipped
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

## Key Components

| Component | Description |
|-----------|-------------|
| `assembly[S, E](...)` | Create reusable transition fragments with compile-time validation |
| `assemblyAll[S, E]: ...` | Block syntax for assemblies (no commas between specs) |
| `Machine(assembly)` | Create a runnable Machine from a validated Assembly |
| `include(assembly)` | Include an assembly's specs in another assembly |
| `Machine[S, E, Cmd]` | The FSM definition that can be started and run |
| `Assembly[S, E, Cmd]` | Composable transition fragments (cannot run directly) |
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
    fsm <- FSMRuntime(orderId, orderMachine, Pending)
    _   <- fsm.send(Pay)      // Persisted to EventStore
    _   <- fsm.saveSnapshot   // Optional: snapshot for faster recovery
  yield ()
}.provide(eventStoreLayer)
```

## Example with Durable Timeouts

```scala
import mechanoid.*
import mechanoid.runtime.FSMRuntime
import mechanoid.persistence.timeout.*
import zio.Duration

enum PaymentState derives Finite:
  case Pending, AwaitingPayment, Paid, Cancelled

enum PaymentEvent derives Finite:
  case StartPayment, ConfirmPayment, PaymentTimeout

import PaymentState.*, PaymentEvent.*

// FSM with timeout that survives node failures
val timedAwaiting = AwaitingPayment @@ Aspect.timeout(Duration.fromMinutes(30), PaymentTimeout)

val paymentMachine = Machine(assembly[PaymentState, PaymentEvent](
  Pending via StartPayment to timedAwaiting,
  AwaitingPayment via ConfirmPayment to Paid,
  AwaitingPayment via PaymentTimeout to Cancelled,
))

val program = ZIO.scoped {
  for
    fsm <- FSMRuntime.withDurableTimeouts(id, paymentMachine, Pending)
    _   <- fsm.send(StartPayment)
    // Timeout persisted - another node's sweeper will fire it if this node dies
  yield ()
}.provide(eventStoreLayer, timeoutStoreLayer)

// Run sweeper (typically separate process)
val sweeper = TimeoutSweeper.make(
  TimeoutSweeperConfig()
    .withSweepInterval(Duration.fromSeconds(5))
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
    fsm <- FSMRuntime.withLocking(orderId, orderMachine, Pending)
    _   <- fsm.send(Pay)  // Lock acquired automatically
  yield ()
}.provide(eventStoreLayer, lockLayer)

// Combine with durable timeouts for maximum robustness
val robustProgram = ZIO.scoped {
  for
    fsm <- FSMRuntime.withLockingAndTimeouts(
      orderId, paymentMachine, Pending, LockConfig.default
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

