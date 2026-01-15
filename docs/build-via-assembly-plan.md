# Build via Assembly Delegation Plan

> **Goal**: Simplify `build` and `buildAll` macros by delegating to `assembly` internally, eliminating duplicate validation logic.

---

## Progress Log

| Date | Phase | Status | Notes |
|------|-------|--------|-------|
| | Phase 1 | Pending | Extract shared macro utilities to MacroUtils.scala |
| | Phase 2 | Pending | Refactor assembly macro to use MacroUtils |
| | Phase 3 | Pending | Refactor build to delegate to assembly |
| | Phase 4 | Pending | Refactor buildAll to delegate to assembly |
| | Phase 5 | Pending | Split macros into focused modules |
| | Phase 6 | Pending | Verification |

---

## Problem

The current implementation has significant code duplication across three macros:

| Duplicated Code | Occurrences | Lines Each |
|-----------------|-------------|------------|
| `extractSetInts` / `extractListStrings` | 3x | ~20 |
| `extractAssemblySpecTerms` | 3x | ~50 |
| `hasAssemblyOverride` | 3x | ~5 |
| Command type inference (LUB) | 3x | ~20 |
| Hash-based duplicate detection | 3x | ~50 |
| `SpecHashInfo` case class | 3x | ~10 |

**Total: ~450+ lines of duplicated code**

## Solution

Delegate `build` and `buildAll` to `assembly` internally:

```scala
// Current flow:
build(specs...) → [duplicate validation] → Machine.fromSpecs(specs)

// Proposed flow:
build(specs...) → assembly(specs...) → Machine.fromSpecs(assembly.specs)
```

This provides:
1. **Single source of truth** for duplicate detection
2. **~450 fewer lines** of duplicate code
3. **Easier maintenance** - fix validation bugs in one place
4. **Natural composition** - Level 2 validation composes from Level 1

---

## Implementation

### Phase 1: Extract Shared Macro Utilities

**Create `MacroUtils.scala`** with shared helpers:

```scala
// core/src/main/scala/mechanoid/machine/MacroUtils.scala
package mechanoid.machine

import scala.quoted.*

private[machine] object MacroUtils:

  // ====== AST Extraction Helpers ======

  def extractSetInts(using Quotes)(setTerm: quotes.reflect.Term): Set[Int] = ...

  def extractListStrings(using Quotes)(listTerm: quotes.reflect.Term): List[String] = ...

  def extractAssemblySpecTerms(using Quotes)(term: quotes.reflect.Term): List[quotes.reflect.Term] = ...

  def hasAssemblyOverride(using Quotes)(term: quotes.reflect.Term): Boolean = ...

  // ====== Type Helpers ======

  def isAssemblyType(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean = ...

  def isTransitionSpecType(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean = ...

  def isMachineType(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean = ...

  // ====== Command Type Inference ======

  def inferCommandType(using Quotes)(cmdTypes: List[quotes.reflect.TypeRepr]): quotes.reflect.TypeRepr = ...

  // ====== Hash Info Extraction ======

  case class SpecHashInfo(
    stateHashes: Set[Int],
    eventHashes: Set[Int],
    stateNames: List[String],
    eventNames: List[String],
    targetDesc: String,
    isOverride: Boolean,
    sourceDesc: String,
  )

  def extractHashInfo(using Quotes)(term: quotes.reflect.Term): Option[SpecHashInfo] = ...

  def getDefinitionTree(using Quotes)(term: quotes.reflect.Term): Option[quotes.reflect.Term] = ...
```

### Phase 2: Refactor assembly Macro

Update `assemblyImpl` to use shared utilities:

```scala
def assemblyImpl[S: Type, E: Type](
    args: Expr[Seq[TransitionSpec[S, E, ?] | Assembly[S, E, ?]]]
)(using Quotes): Expr[Assembly[S, E, ?]] =
  import quotes.reflect.*
  import MacroUtils.*

  // Use shared helpers instead of local definitions
  val rawExprs = args match
    case Varargs(exprs) => exprs.toList
    case _ => report.errorAndAbort("Expected varargs")

  // ... rest of implementation using MacroUtils
```

### Phase 3: Refactor build to Delegate to assembly

**Key insight**: `build` can call `assemblyImpl` internally, then wrap the result:

```scala
def buildWithInferredCmdImpl[S: Type, E: Type](
    args: Expr[Seq[TransitionSpec[S, E, ?] | Assembly[S, E, ?]]]
)(using Quotes): Expr[Machine[S, E, ?]] =
  import quotes.reflect.*
  import MacroUtils.*

  // Step 1: Delegate to assembly for validation and spec flattening
  val assemblyExpr = assemblyImpl[S, E](args)

  // Step 2: Extract the inferred command type from the assembly
  val cmdType = assemblyExpr.asTerm.tpe match
    case AppliedType(_, List(_, _, cmd)) => cmd
    case _ => TypeRepr.of[Nothing]

  // Step 3: Create Machine from assembly specs
  cmdType.asType match
    case '[cmd] =>
      val stateEnumExpr = Expr.summon[Finite[S]].getOrElse(
        report.errorAndAbort("Cannot find Finite instance for state type")
      )
      val eventEnumExpr = Expr.summon[Finite[E]].getOrElse(
        report.errorAndAbort("Cannot find Finite instance for event type")
      )

      '{
        given Finite[S] = $stateEnumExpr
        given Finite[E] = $eventEnumExpr
        Machine.fromSpecs[S, E, cmd](${ assemblyExpr.asExprOf[Assembly[S, E, cmd]] }.specs)
      }
```

**Benefits:**
- ~500 lines removed from `buildWithInferredCmdImpl`
- All validation happens in `assemblyImpl`
- Single code path for duplicate detection

### Phase 4: Refactor buildAll to Delegate to assembly

`buildAll` is more complex due to block syntax with helper vals. Approach:

```scala
def buildAllImpl[S: Type, E: Type](
    block: Expr[Any]
)(using Quotes): Expr[Machine[S, E, ?]] =
  import quotes.reflect.*
  import MacroUtils.*

  // Step 1: Process block to extract helper vals and ordered terms
  val BlockContents(helperVals, specTerms, assemblyTerms, orderedTerms) = processBlock(block.asTerm)

  // Step 2: Convert ordered terms to varargs-style and call assemblyImpl
  // This requires creating a synthetic Varargs expression
  val specsAndAssembliesExpr: Expr[Seq[TransitionSpec[S, E, ?] | Assembly[S, E, ?]]] =
    Expr.ofSeq(orderedTerms.map(_.asExpr.asInstanceOf[Expr[TransitionSpec[S, E, ?] | Assembly[S, E, ?]]]))

  val assemblyExpr = assemblyImpl[S, E](specsAndAssembliesExpr)

  // Step 3: Create Machine, wrapping with helper vals if needed
  val cmdType = extractCmdTypeFromAssembly(assemblyExpr)

  cmdType.asType match
    case '[cmd] =>
      val fromSpecsExpr = '{
        given Finite[S] = $stateEnumExpr
        given Finite[E] = $eventEnumExpr
        Machine.fromSpecs[S, E, cmd](${ assemblyExpr.asExprOf[Assembly[S, E, cmd]] }.specs)
      }

      if helperVals.nonEmpty then
        Block(helperVals.toList, fromSpecsExpr.asTerm).asExprOf[Machine[S, E, cmd]]
      else
        fromSpecsExpr
```

**Note**: `processBlock` remains in `buildAllImpl` as it's specific to block syntax.

### Phase 5: Split Macros into Multiple Files

Even after delegation, ~1200 lines is too large for easy reasoning. Split into focused modules:

| New File | Contents | ~Lines |
|----------|----------|--------|
| `MacroUtils.scala` | Shared utilities (from Phase 1) | ~200 |
| `MatcherMacros.scala` | `all[]`, `anyOf[]`, `anyOfEvents[]`, `event[]`, `state[]` | ~150 |
| `AssemblyMacros.scala` | `assemblyImpl` - the core validation logic | ~400 |
| `BuildMacros.scala` | `buildWithInferredCmdImpl`, `buildAllImpl` (now thin wrappers) | ~200 |
| `Macros.scala` | Top-level `inline def` wrappers, DSL extensions, exports | ~250 |

**Benefits:**
- Each file has a single responsibility
- Easier to understand and modify
- Better IDE navigation
- Logical grouping: utilities → matchers → assembly → build → API

### Phase 6: Verification

1. **Compile**: `sbt compile` - all modules
2. **Tests**: `sbt test` - all 355 tests pass
3. **Examples**: `sbt examples/compile` - examples compile
4. **Behavior preservation**:
   - Duplicate detection at compile time (Level 1 and Level 2)
   - Override messages with `report.info`
   - Machine rejection with helpful error
   - Order preservation (later wins on override)

---

## Files to Modify

| File | Action |
|------|--------|
| `core/src/main/scala/mechanoid/machine/MacroUtils.scala` | CREATE - shared utilities |
| `core/src/main/scala/mechanoid/machine/MatcherMacros.scala` | CREATE - matcher implementations |
| `core/src/main/scala/mechanoid/machine/AssemblyMacros.scala` | CREATE - assembly macro |
| `core/src/main/scala/mechanoid/machine/BuildMacros.scala` | CREATE - build/buildAll macros |
| `core/src/main/scala/mechanoid/machine/Macros.scala` | MODIFY - slim down to API layer |

---

## Estimated Impact

| Metric | Before | After |
|--------|--------|-------|
| Lines in Macros.scala | ~2100 | ~250 |
| Total macro lines | ~2100 | ~1200 |
| Largest file | 2100 | ~400 |
| Duplicated helper functions | 12+ | 0 |
| Validation logic copies | 3 | 1 |
| Test coverage | 355 | 355 (unchanged) |

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Subtle behavior changes | Comprehensive test suite (355 tests) |
| Compile-time performance | Minimal - assembly already does the work |
| Error message quality | Preserve source location tracking |

