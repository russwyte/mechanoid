# Suite-Style DSL Design

## Overview

This document describes the design and implementation of Mechanoid's suite-style DSL for defining FSMs. The DSL evolved from initial proposals to the final clean infix syntax.

## Motivation

The original chained builder pattern (`_.when(A).on(E).goto(B).when(B)...`) made compile-time validation complex because we had to traverse a deeply nested AST. The suite-style approach treats each transition as a discrete expression, enabling:

1. **Simpler validation** - Just iterate over a list of specs
2. **Better readability** - Each transition stands alone
3. **Precise errors** - Point directly to the problematic line
4. **Composition** - Combine definitions like zio-test suites
5. **Type inference** - No explicit type parameters needed in most cases

## Final Syntax

### Basic Usage

```scala
import mechanoid.*

// Clean infix syntax - types inferred from transitions
val machine = build(
  Pending via Pay to Paid,
  Paid via Ship to Shipped,
  Shipped via Deliver to Delivered,
)

// Block style with buildAll
val machine = buildAll[OrderState, OrderEvent]:
  Pending via Pay to Paid
  Paid via Ship to Shipped
  Shipped via Deliver to Delivered

// Non-transition outcomes
Processing via Cancel to stay          // stay in current state
Processing via Abort to stop           // stop FSM
Processing via Error to stop("failed") // stop with reason
```

### Override with Aspects

```scala
import mechanoid.*

// Single transition override
val machine = build(
  A via E1 to B,
  (A via E1 to C) @@ Aspect.overriding,  // This one wins
)

// Override entire machine (all specs become overridable)
val baseMachine = build(
  A via E1 to B,
  B via E1 to C,
) @@ Aspect.overriding

// Compose with overrides
val extended = build(
  baseMachine,           // All these can be overridden
  A via E1 to D,         // This wins over base
)
```

### Hierarchical States

```scala
sealed trait ReviewState
case object PendingReview extends ReviewState
case object UnderReview extends ReviewState
case object ChangesRequested extends ReviewState

// all[Parent] expands to all children at compile time
val machine = build(
  all[ReviewState] via Cancel to Draft,                    // Applies to all ReviewState children
  (UnderReview via Cancel to NeedsRework) @@ Aspect.overriding,  // Override for specific child
)
```

### Multi-Event Matching

```scala
// Match by event type (useful for events with data)
Pending via event[PaymentReceived] to Processing

// Match multiple specific events
Idle via anyOfEvents(Click, Tap, Swipe) to Active

// Match multiple specific states
anyOf(Draft, Archived) via Reopen to Active
```

### User-Defined Timeout Events

```scala
enum OrderEvent:
  case Pay, PaymentTimeout

// Create timed target - schedules timeout when entering state
val timedPayment = PaymentPending @@ timeout(30.minutes, PaymentTimeout)

val machine = build(
  Created via Pay to timedPayment,
  PaymentPending via PaymentTimeout to Cancelled,
  PaymentPending via Confirm to Paid,
)
```

### Command Generation with emitting

```scala
val machine = buildAll[OrderState, OrderEvent]:
  // Define helper at top of block
  val buildPaymentCommand: (OrderEvent, OrderState) => List[Command] = { (event, _) =>
    event match
      case e: InitiatePayment => List(ProcessPayment(e.orderId, e.amount))
      case _ => Nil
  }

  // Use infix emitting
  Created via event[InitiatePayment] to Processing emitting buildPaymentCommand

  // Or inline
  Paid via Ship to Shipped emitting { (_, state) =>
    List(SendNotification(s"Order shipped: ${state.orderId}"))
  }
```

### Composition

```scala
// Base transitions - reusable module
val paymentMachine = build(
  Pending via Pay to Paid,
  Paid via Refund to Refunded,
)

// Shipping transitions - another module
val shippingMachine = build(
  Paid via Ship to Shipped,
  Shipped via Deliver to Delivered,
)

// Compose them directly
val fullWorkflow = build(
  paymentMachine,
  shippingMachine,
)

// Block style with include()
val fullWorkflow = buildAll[OrderState, OrderEvent]:
  include(paymentMachine)
  include(shippingMachine)
  Delivered via Return to Returned  // Add more
```

**Note:** Val references to `Machine` or `TransitionSpec` in `buildAll` blocks require `include()` wrapper to avoid "pure expression does nothing" warnings.

## Core Types

```scala
/** A single transition specification. */
final case class TransitionSpec[+S, +E, +Cmd](
  stateHashes: Set[Int],      // For duplicate detection (expanded from sealed hierarchies)
  eventHashes: Set[Int],      // For duplicate detection
  stateNames: List[String],   // For error messages
  eventNames: List[String],   // For error messages
  targetDesc: String,         // "-> Paid", "stay", "stop" - for error messages
  isOverride: Boolean,        // If true, won't trigger duplicate error
  handler: Handler[Any],
  targetTimeout: Option[Duration],
  targetTimeoutConfig: Option[TimeoutEventConfig[?]],
  preCommandFactory: Option[(Any, Any) => List[Any]],
  postCommandFactory: Option[(Any, Any) => List[Any]],
):
  infix def emittingBefore[C](f: (E, S) => List[C]): TransitionSpec[S, E, C]
  infix def emitting[C](f: (E, S) => List[C]): TransitionSpec[S, E, C]

/** Aspects that modify transition specs or machines. */
enum Aspect:
  case overriding
  case timeout[E](duration: Duration, event: E)

/** Extension for @@ syntax. */
extension [S, E, Cmd](spec: TransitionSpec[S, E, Cmd])
  infix def @@(aspect: Aspect): TransitionSpec[S, E, Cmd]

extension [S, E, Cmd](machine: Machine[S, E, Cmd])
  infix def @@(aspect: Aspect): Machine[S, E, Cmd]
```

## DSL Extension Methods

```scala
// State via Event - creates ViaBuilder
extension [S](inline state: S)(using NotGiven[S <:< IsMatcher])
  inline infix def via[E](inline event: E): ViaBuilder[S, E]

// ViaBuilder.to - creates TransitionSpec
final class ViaBuilder[S, E](...):
  inline infix def to[S2](target: S2): TransitionSpec[S, E, Nothing]
  inline def to: ToBuilder[S, E]  // For stay/stop

// all[T] - matches all subtypes of sealed type
inline def all[T]: AllMatcher[T]

// event[T] - matches events by type
inline def event[E]: EventMatcher[E]

// anyOf(...) - matches specific states
inline def anyOf[S](inline first: S, inline rest: S*): AnyOfMatcher[S]

// anyOfEvents(...) - matches specific events
inline def anyOfEvents[E](inline first: E, inline rest: E*): AnyOfEventMatcher[E]
```

## Duplicate Detection

### Compile-Time (Inline Specs)

The `build` and `buildAll` macros detect duplicates at compile time for **inline transition specs**:

```scala
val bad = build(
  A via E1 to B,
  A via E1 to C,  // ERROR: Duplicate transition
)
// Compile error:
// Duplicate transition detected!
//   Transition: A via E1
//   First at spec #1: -> B
//   Also at spec #2: -> C
// To override, use: @@ Aspect.overriding
```

### Hierarchy Expansion

```scala
val bad = build(
  all[Parent] via E1 to B,    // Expands to ChildA, ChildB, ChildC
  ChildA via E1 to C,         // ERROR: Conflicts with expansion above
)
```

To fix, use override:

```scala
val good = build(
  all[Parent] via E1 to B,
  (ChildA via E1 to C) @@ Aspect.overriding,  // OK: Intentional override
)
```

### Override Info Messages

When overrides are detected in **inline specs**, the compiler emits informational messages:

```
[mechanoid] Override info (1 overrides):
  ChildA via E1: -> B (spec #1) -> -> C (spec #2)
```

### Runtime Detection (Machine Composition)

When composing machines (e.g., `build(baseMachine, ...)`), the macro cannot inspect the contents of already-constructed `Machine` values. Detection happens at **runtime** during machine construction - you'll get a clear `IllegalArgumentException` at application startup, not when events are sent:

```scala
// baseMachine is an opaque expression - macro can't see inside it
val composed = build(
  baseMachine,                          // Machine[S, E, ?]
  A via E1 to C,                        // Inline spec
)
// If baseMachine contains A via E1 -> B, throws IllegalArgumentException at machine construction

// Use override for intentional overwrites
val composed = build(
  baseMachine,
  (A via E1 to C) @@ Aspect.overriding,  // OK: intentional override
)
```

### Summary Table

| Scenario | Detection | When |
|----------|-----------|------|
| Inline specs (`A via E to B`) | **Compile time** | Macro inspects AST |
| Same val twice (`build(t1, t1)`) | **Compile time** | Symbol tracking |
| Machine composition | **Runtime** | At machine construction |

## Type Inference

Types are inferred from the transitions using Least Upper Bound (LUB) computation:

```scala
// Types inferred as Machine[TestState, TestEvent, Nothing]
val machine = build(
  A via E1 to B,   // TransitionSpec[A.type, E1.type, Nothing]
  B via E2 to C,   // TransitionSpec[B.type, E2.type, Nothing]
)
// LUB: TestState is common sealed parent of A, B, C
// LUB: TestEvent is common sealed parent of E1, E2
```

Explicit type parameters still work when needed:

```scala
val machine = build[OrderState, OrderEvent](
  Pending via Pay to Paid,
)
```

## Design Decisions

1. **Infix syntax** - `State via Event to Target` reads naturally and avoids `.type` annotations

2. **NotGiven for disambiguation** - Extension methods use `NotGiven[S <:< IsMatcher]` to prevent ambiguity with matcher types

3. **include() wrapper** - Required for val references in buildAll to avoid compiler warnings about pure expressions

4. **User-defined timeout events** - Instead of a built-in `Timeout` singleton, users define their own timeout events, enabling type safety and rich handling

5. **Invariant TransitionSpec** - Made invariant in S and E to preserve types through the DSL for proper inference

6. **Compile-time hash extraction** - State and event hashes are extracted at compile time for duplicate detection

## Evolution Notes

The DSL evolved through several iterations:

1. **Initial proposal**: `when[S].on[E](target)` - Required explicit `.type` annotations
2. **Intermediate**: `S.via(E).to(Target)` - Still verbose
3. **Final**: `S via E to Target` - Clean infix, minimal syntax

Key insight: Using `NotGiven` to disambiguate extension methods allowed clean infix syntax without `.type` annotations on enum case values.
