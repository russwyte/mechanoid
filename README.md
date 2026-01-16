# Mechanoid

[![Scala CI](https://github.com/russwyte/mechanoid/actions/workflows/scala.yml/badge.svg)](https://github.com/russwyte/mechanoid/actions/workflows/scala.yml)

On Maven Central
[![Maven Central - Core](https://img.shields.io/maven-central/v/io.github.russwyte/mechanoid_3?logo=apachemaven&label=mechanoid-central)](https://central.sonatype.com/artifact/io.github.russwyte/mechanoid_3)
[![Maven Central - Postgres](https://img.shields.io/maven-central/v/io.github.russwyte/mechanoid-postgres_3?logo=apachemaven&label=mechanoid-postgres-central)](https://central.sonatype.com/artifact/io.github.russwyte/mechanoid-postgres_3)

On Maven Repo
[![Maven Repository - Core](https://img.shields.io/maven-central/v/io.github.russwyte/mechanoid_3?logo=apachemaven&label=mechanoid-repo)](https://mvnrepository.com/artifact/io.github.russwyte/mechanoid)
[![Maven Repository - Postgres](https://img.shields.io/maven-central/v/io.github.russwyte/mechanoid-postgres_3?logo=apachemaven&label=mechanoid-postgres-repo)](https://mvnrepository.com/artifact/io.github.russwyte/mechanoid-postgres)


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

## Installation

Add to your `build.sbt`:

```scala
// Core library
libraryDependencies += "io.github.russwyte" %% "mechanoid" % "<version>"

// PostgreSQL persistence (optional)
libraryDependencies += "io.github.russwyte" %% "mechanoid-postgres" % "<version>"
```

Replace `<version>` with the latest version shown in the badges above.

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
| `TimeoutStrategy[Id]` | Strategy for state timeouts (`fiber` or `durable`) |
| `LockingStrategy[Id]` | Strategy for concurrent access (`optimistic` or `distributed`) |
| `TimeoutSweeper` | Background service for durable timeouts |
| `FSMInstanceLock` | Distributed locking for exactly-once transitions |
| `LeaderElection` | Lease-based coordination for single-active mode |
| `CommandWorker` | Background processor for side effect commands |

## Example with Persistence

```scala
import mechanoid.*

val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, orderMachine, Pending)
    _   <- fsm.send(Pay)      // Persisted to EventStore
    _   <- fsm.saveSnapshot   // Optional: snapshot for faster recovery
  yield ()
}.provide(
  eventStoreLayer,
  TimeoutStrategy.fiber[OrderId],      // In-memory timeouts
  LockingStrategy.optimistic[OrderId]  // Optimistic concurrency control
)
```

## Example with Durable Timeouts

```scala
import mechanoid.*
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
    fsm <- FSMRuntime(id, paymentMachine, Pending)
    _   <- fsm.send(StartPayment)
    // Timeout persisted - another node's sweeper will fire it if this node dies
  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  TimeoutStrategy.durable[PaymentId],     // Persisted timeouts that survive node failures
  LockingStrategy.optimistic[PaymentId]
)

// Run sweeper (integrates directly with FSMRuntime for type-safe timeout handling)
val sweeper = TimeoutSweeper.make(
  TimeoutSweeperConfig()
    .withSweepInterval(Duration.fromSeconds(5))
    .withJitterFactor(0.2),
  timeoutStore,
  runtime  // FSMRuntime instance - sweeper validates state before firing
)
```

## Example with Distributed Locking

```scala
import mechanoid.*

// Exactly-once transitions across multiple nodes
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, orderMachine, Pending)
    _   <- fsm.send(Pay)  // Lock acquired automatically before processing
  yield ()
}.provide(
  eventStoreLayer,
  lockServiceLayer,                       // FSMInstanceLock implementation
  TimeoutStrategy.fiber[OrderId],
  LockingStrategy.distributed[OrderId]    // Acquires lock before each transition
)

// Production setup: durable timeouts + distributed locking
val robustProgram = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, paymentMachine, Pending)
    _   <- fsm.send(StartPayment)
  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  lockServiceLayer,
  TimeoutStrategy.durable[OrderId],       // Timeouts survive node failures
  LockingStrategy.distributed[OrderId]    // Prevents concurrent modifications
)
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

