# Suite-Style DSL Design

## Motivation

The current chained builder pattern (`_.when[A].on[E].goto(B).when[B]...`) makes compile-time validation complex because we must traverse a deeply nested AST. A suite-style approach treats each transition as a discrete expression, enabling:

1. **Simpler validation** - Just iterate over a list of specs
2. **Better readability** - Each transition stands alone
3. **Precise errors** - Point directly to the problematic line
4. **Composition** - Combine definitions like zio-test suites
5. **Familiar pattern** - Users who know zio-test get it immediately

## Proposed Syntax

### Basic Usage

```scala
import mechanoid.dsl.SuiteDSL.*

// Varargs style - on[E](target) uses apply for transitions
val definition = build[OrderState, OrderEvent](
  when[Pending.type].on[Pay.type](Paid),
  when[Paid.type].on[Ship.type](Shipped),
  when[Shipped.type].on[Deliver.type](Delivered)
)

// Block style (like suiteAll)
val definition = buildAll[OrderState, OrderEvent] {
  when[Pending.type].on[Pay.type](Paid)
  when[Paid.type].on[Ship.type](Shipped)
  when[Shipped.type].on[Deliver.type](Delivered)
}

// Non-transition actions
when[Processing.type].on[Cancel.type].stay          // stay in current state
when[Processing.type].on[Abort.type].stop           // stop FSM
when[Processing.type].on[Error.type].stop("failed") // stop with reason
```

### Override with Aspects (like zio-test)

```scala
import mechanoid.dsl.SuiteDSL.*
import mechanoid.dsl.TransitionAspect.*

// Single transition override
val definition = build[State, Event](
  when[A.type].on[E1.type](B),
  when[A.type].on[E1.type](C) @@ overriding  // This one wins
)

// Override entire definition (all specs become overridable)
val baseDefinition = build[State, Event](
  when[A.type].on[E1.type](B),
  when[B.type].on[E1.type](C)
) @@ overriding

// Compose with overrides
val extended = build[State, Event](
  baseDefinition,  // All these can be overridden
  when[A.type].on[E1.type](D)  // This wins over base
)
```

### Hierarchical States

```scala
sealed trait ReviewState extends MState
case object PendingReview extends ReviewState
case object UnderReview extends ReviewState
case object ChangesRequested extends ReviewState

// when[Parent] expands to all children at compile time
val definition = build[State, Event](
  when[ReviewState].on[Cancel.type](Draft),  // Applies to all ReviewState children
  when[UnderReview.type].on[Cancel.type](NeedsRework) @@ overriding  // Override for specific child
)
```

### Timeout Transitions

```scala
val definition = build[State, Event](
  when[Processing.type].onTimeout(TimedOut),
  when[Waiting.type].onTimeout.stop("Wait timeout exceeded")
)
```

### Composition

```scala
// Base transitions - reusable module
val paymentTransitions = build[OrderState, OrderEvent](
  when[Pending.type].on[Pay.type](Paid),
  when[Paid.type].on[Refund.type](Refunded)
)

// Shipping transitions - another module
val shippingTransitions = build[OrderState, OrderEvent](
  when[Paid.type].on[Ship.type](Shipped),
  when[Shipped.type].on[Deliver.type](Delivered)
)

// Compose them - Machines work directly as expressions
val fullWorkflow = build[OrderState, OrderEvent](
  paymentTransitions,
  shippingTransitions
)

// Block style works too
val fullWorkflow = buildAll[OrderState, OrderEvent] {
  paymentTransitions
  shippingTransitions
  when[Delivered.type].on[Return.type](Returned)  // Add more
}
```

### Conditional Composition

```scala
val baseWorkflow = build[State, Event](...)

val withCancellation = if enableCancellation then
  build[State, Event](
    baseWorkflow,
    when[Pending.type].on[Cancel.type](Cancelled)
  )
else baseWorkflow
```

## Core Types

```scala
/** A single transition specification. */
final case class TransitionSpec[-S <: MState, -E <: MEvent, +D](
  stateHashes: Set[Int],      // For duplicate detection (expanded from sealed hierarchies)
  eventHashes: Set[Int],      // For duplicate detection
  stateNames: List[String],   // For error messages
  eventNames: List[String],   // For error messages
  targetDesc: String,         // "-> Paid", "stay", "stop" - for error messages
  isOverride: Boolean,        // If true, won't trigger duplicate error
  handler: TransitionHandler[S, E, D]
)

/** Aspect that can modify transition specs. */
sealed trait TransitionAspect:
  def apply[S <: MState, E <: MEvent, D](spec: TransitionSpec[S, E, D]): TransitionSpec[S, E, D]

object TransitionAspect:
  /** Mark transition as an override. */
  case object overriding extends TransitionAspect:
    def apply[S <: MState, E <: MEvent, D](spec: TransitionSpec[S, E, D]): TransitionSpec[S, E, D] =
      spec.copy(isOverride = true)

/** Extension for @@ syntax. */
extension [S <: MState, E <: MEvent, D](spec: TransitionSpec[S, E, D])
  def @@(aspect: TransitionAspect): TransitionSpec[S, E, D] = aspect(spec)

extension [S <: MState, E <: MEvent, D](defn: Machine[S, E, D])
  def @@(aspect: TransitionAspect): Machine[S, E, D] =
    defn.mapSpecs(aspect.apply)
```

## Builder Types

```scala
/** Builder after when[S]. */
final class WhenBuilder[S <: MState](
  stateHashes: Set[Int],
  stateNames: List[String]
):
  inline def on[E <: MEvent]: OnBuilder[S, E] = ...
  inline def onTimeout: OnTimeoutBuilder[S] = ...

/** Builder after when[S].on[E] - has apply for transitions. */
final class OnBuilder[S <: MState, E <: MEvent](
  stateHashes: Set[Int],
  eventHashes: Set[Int],
  stateNames: List[String],
  eventNames: List[String]
):
  def apply(target: S): TransitionSpec[S, E, Nothing] = ...  // when[A].on[E](B)
  def stay: TransitionSpec[S, E, Nothing] = ...              // when[A].on[E].stay
  def stop: TransitionSpec[S, E, Nothing] = ...              // when[A].on[E].stop
  def stop(reason: String): TransitionSpec[S, E, Nothing] = ... // when[A].on[E].stop("reason")

/** Builder after when[S].onTimeout - also has apply. */
final class OnTimeoutBuilder[S <: MState](
  stateHashes: Set[Int],
  stateNames: List[String]
):
  def apply(target: S): TransitionSpec[S, MEvent, Nothing] = ... // when[A].onTimeout(B)
  def stay: TransitionSpec[S, MEvent, Nothing] = ...
  def stop: TransitionSpec[S, MEvent, Nothing] = ...
  def stop(reason: String): TransitionSpec[S, MEvent, Nothing] = ...
```

## Macros

### when[S] Macro

```scala
inline def when[S <: MState]: WhenBuilder[S] = ${ whenImpl[S] }

def whenImpl[S <: MState: Type](using Quotes): Expr[WhenBuilder[S]] =
  // 1. Get TypeRepr of S
  // 2. If sealed, expand to all children
  // 3. Compute hashes for duplicate detection
  // 4. Return WhenBuilder with captured info
```

### on[E] Macro

```scala
// In WhenBuilder
inline def on[E <: MEvent]: OnBuilder[S, E] = ${ onImpl[S, E]('this) }

def onImpl[S, E](when: Expr[WhenBuilder[S]])(using Quotes): Expr[OnBuilder[S, E]] =
  // 1. Get TypeRepr of E
  // 2. If sealed, expand to all children
  // 3. Compute hashes
  // 4. Return OnBuilder combining when's state info + event info
```

### build Macro

```scala
inline def build[S <: MState, E <: MEvent](
  inline specs: (TransitionSpec[S, E, ?] | Machine[S, E, ?])*
): Machine[S, E, Nothing] = ${ buildImpl[S, E]('specs) }

def buildImpl[S, E](specs: Expr[Seq[...]])(using Quotes): Expr[Machine[S, E, Nothing]] =
  // 1. Extract all TransitionSpec expressions
  // 2. For each spec, extract stateHashes/eventHashes (if statically available)
  // 3. Check for duplicate (stateHash, eventHash) pairs among non-override specs
  // 4. Report compile error with helpful message if duplicates found
  // 5. Return Machine.fromSpecs(...)
```

### buildAll Macro

```scala
inline def buildAll[S <: MState, E <: MEvent](inline block: Unit): Machine[S, E, Nothing] =
  ${ buildAllImpl[S, E]('block) }

def buildAllImpl[S, E](block: Expr[Unit])(using Quotes): Expr[Machine[S, E, Nothing]] =
  // 1. Parse block AST
  // 2. Collect all top-level expressions that are TransitionSpec or include(...)
  // 3. Validate for duplicates
  // 4. Return Machine.fromSpecs(...)
```

## Validation

### Compile-Time Validation

The build/buildAll macros detect duplicates at compile time:

```scala
val bad = build[State, Event](
  when[A.type].on[E1.type](B),
  when[A.type].on[E1.type](C)  // ERROR: Duplicate transition
)
// Compile error:
// Duplicate transition detected.
//   State: A (conflicts with: A)
//   Event: E1 (conflicts with: E1)
//   First definition:  -> B
//   Second definition: -> C
// To fix: Remove duplicate OR use @@ overriding
```

### Hierarchy Expansion

```scala
val bad = build[State, Event](
  when[Parent].on[E1.type](B),      // Expands to ChildA, ChildB, ChildC
  when[ChildA.type].on[E1.type](C)  // ERROR: Conflicts with expansion above
)
```

To fix, use override:

```scala
val good = build[State, Event](
  when[Parent].on[E1.type](B),
  when[ChildA.type].on[E1.type](C) @@ overriding  // OK: Intentional override
)
```

## Migration Path

1. Keep old `TypedDSL` working during transition
2. Add new `SuiteDSL` alongside
3. Update examples and tests incrementally
4. Deprecate old DSL
5. Remove old DSL in future version

## Design Decisions

1. **Direct composition** - Machines work directly as expressions in build/buildAll, no `include()` needed

2. **Bulk override** - `definition @@ overriding` marks ALL specs in that definition as overridable

3. **Named transitions** - Skip for now, add later if needed

4. **Data carrying** - Keep existing `D` type parameter pattern, flows through TransitionSpec

## Future Considerations

- Aspect composition (`spec @@ overriding @@ logging`)
- Conditional transitions with guards
- Named transitions for debugging/tracing
