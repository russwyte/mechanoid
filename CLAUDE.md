# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

Prefer to use metals mcp vs bash. If things seem off - run import-build then compile-full

```bash
# Full build with formatting check and tests (CI pipeline)
sbt clean scalafmtCheckAll test

# Format all code
sbt scalafmtAll

# Run tests for specific module
sbt core/test
sbt postgres/test
sbt examples/test

# Compile without tests
sbt compile

# Build examples fat jar
sbt examples/assembly

# Publish locally
sbt publishLocal
```

## Pre-commit Hook

Configure the pre-commit hook to enforce formatting:
```bash
git config core.hooksPath hooks
```

This runs `sbt scalafmtCheckAll` before each commit. Fix formatting issues with `sbt scalafmtAll`.

## Architecture Overview

Mechanoid is a **type-safe, effect-oriented finite state machine library** for Scala 3 built on ZIO 2.x. It provides compile-time validated FSM definitions with event sourcing and distributed coordination capabilities.

## Code Quality

When coding in this library prefer idiomatic ZIO patterns when considering options. Compose capabilities as layers. Use error types instead of throwing.

We strive always for strong type safety guarantees - avoid casts and `Any` at all costs unless they are used in ways consistent with ZIO etc - (like ZIO(Any,Nothing,A)). Use type classes for extending capabilities and widening types.

Keep the library simple to use and import - example - to use postgres persistence I expect users to only need two or three import lines:
```scala
import mechanoid.*
import mechanoid.postgres.*
import mechanoid.persistence.* // only used if you need specific overrides from defaults for strategies
```
That means all the public APIs need to be exported appropriately.

### Project Structure

- **core/** - Core FSM library (states, events, transitions, runtime, persistence interfaces, visualization)
- **postgres/** - PostgreSQL implementations of persistence traits (EventStore, TimeoutStore, FSMInstanceLock, LeaseStore)
- **examples/** - Reference implementations (petstore order FSM, hierarchical states, heartbeat monitoring)
- **compile-experiments/** - Macro development and testing

### Key Design Patterns

**Sealed Typeclass Derivation**: The `Finite[T]` typeclass (derived via `derives Finite`) proves at compile time that a type is a sealed set of cases. Macros discover all cases, compute stable hashes, and generate pattern-matching code. It is also used to widen types for hierarchical FSM states and events etc.

**Transition DSL**: Clean infix syntax with compile-time validation:
```scala
Pending via Pay to Paid                           // Simple transition
all[Processing] via Cancel to Cancelled           // Wildcard for sealed hierarchy
anyOf(A, B) via E to C                            // Multiple source states
(A via E to B) @@ Aspect.timeout(30.seconds, T)   // With timeout aspect
(A via E to B) @@ Aspect.overriding               // Override marker for duplicates
```

**Good Coding Hygiene**
After code changes to files run metals format-file,

**Assembly Composition**: `assembly[S, E](...)` creates reusable transition fragments validated at compile time. `Machine(assembly)` wraps into a runnable FSM. Use `include(otherAssembly)` to compose.

**Strategy Pattern for Runtime**: `FSMRuntime[Id, S, E]` uses pluggable strategies:
- `TimeoutStrategy`: `fiber` (in-memory) or `durable` (persisted to TimeoutStore)
- `LockingStrategy`: `optimistic` (EventStore sequence conflicts) or `distributed` (FSMInstanceLock)
This is the preferred way to extend capabilities to the runtime.

**Persistence Traits**: Abstract interfaces in `core/` with PostgreSQL implementations in `postgres/`:
- `EventStore[Id, S, E]` - Event sourcing with optimistic locking
- `TimeoutStore[Id]` - Durable timeout persistence
- `FSMInstanceLock[Id]` - Distributed mutual exclusion
- `LeaseStore[Id]` - Leader election for TimeoutSweeper

### Important Files

- `core/src/main/scala/mechanoid/Mechanoid.scala` - Public API re-exports
- `core/src/main/scala/mechanoid/core/Finite.scala` - Core typeclass for sealed types
- `core/src/main/scala/mechanoid/machine/Machine.scala` - Runnable FSM definition
- `core/src/main/scala/mechanoid/machine/Assembly.scala` - Composable transition fragments
- `core/src/main/scala/mechanoid/runtime/FSMRuntime.scala` - Primary runtime interface
- `core/src/main/scala/mechanoid/macros/FiniteMacros.scala` - Derives Finite typeclass

### Testing

Tests use ZIO Test framework. PostgreSQL tests use TestContainers for integration testing.

```bash
# Run all tests
sbt test

# Run specific test class
sbt "core/testOnly mechanoid.machine.MachineSpec"
```
