# Assembly Implementation Plan

> **Progress Tracking**: This document serves as both the implementation plan and a historical record of the refactoring.

---

## Progress Log

| Date | Phase | Status | Notes |
|------|-------|--------|-------|
| 2026-01-15 | Planning | Complete | Plan finalized with 7 phases |
| 2026-01-15 | Phase 1 | Complete | Assembly.scala created, exports added to Mechanoid.scala |
| 2026-01-15 | Phase 2 | Complete | assembly macro created with Level 1 duplicate detection |
| 2026-01-15 | Phase 3 | Complete | build/buildAll updated for Assembly with Level 2 validation |
| 2026-01-15 | Phase 4 | Complete | Machine composition removed, build/buildAll use order-preserving code generation |
| 2026-01-15 | Phase 5 | Complete | Tests updated: MachineSpec uses Assembly, 202 core tests pass |
| 2026-01-15 | Phase 6 | Complete | Verification: All modules compile, 355 total tests pass (core + integration) |
| 2026-01-15 | Phase 7 | Complete | Documentation updated: DOCUMENTATION.md Assembly section, README.md examples, API references |

---

## Overview

Introduce `Assembly[S, E, Cmd]` as a **compile-time composable** alternative to Machine-in-Machine composition. This is a **breaking change** that solves the cyclic macro dependency problem preventing compile-time validation when composing Machines.

---

## Problem

When `build()` macro receives a `Machine` val, looking up its definition via `sym.tree` causes a cyclic dependency because the Machine was itself created by a `build()` macro. This forces runtime-only duplicate detection for machine composition.

## Solution

Create `Assembly` as a partial machine that:
1. Holds `List[TransitionSpec]` directly (not behind macro expansion)
2. **Cannot be run** (no FSM runtime methods)
3. Can be composed in `build`/`buildAll` with **full compile-time validation**

---

## Two-Level Compile-Time Validation

**Key principle: A problematic machine should NEVER compile if the issue is detectable.**

### Level 1: Assembly Scope (Small)

The `assembly` macro validates its own specs:

```scala
// COMPILE ERROR - duplicate without override in same assembly
val bad = assembly[S, E](
  A via E1 to B,
  A via E1 to C,  // ERROR: Duplicate transition without override
)

// OK with override - emits info message
val ok = assembly[S, E](
  A via E1 to B,
  (A via E1 to C) @@ Aspect.overriding,  // INFO: Override detected
)
```

**Behavior:**
- `report.errorAndAbort` for duplicates without override
- `report.info` for intentional overrides (developer feedback)

### Level 2: Build Scope (Large)

The `build` macro validates ACROSS all assemblies + inline specs:

```scala
val assembly1 = assembly[S, E](A via E1 to B)
val assembly2 = assembly[S, E](A via E1 to C)  // Each assembly is valid alone

// COMPILE ERROR - conflict between assemblies
val bad = build[S, E](assembly1, assembly2)
// ERROR: Duplicate transition A via E1
//   First in assembly1: -> B
//   Also in assembly2: -> C
// To override, use: @@ Aspect.overriding

// OK - override resolves conflict
val ok = build[S, E](
  assembly1,
  assembly2 @@ Aspect.overriding,  // INFO: Override detected (larger scope)
)
```

**Behavior:**
- Extracts specs from each Assembly at compile time
- Merges all specs (from assemblies + inline)
- `report.errorAndAbort` for any duplicates without override
- `report.info` for overrides, showing cross-assembly context

### Info Messages

Override info messages provide developer feedback at different scopes:

```
// Within assembly
[mechanoid] Override in assembly (1):
  A via E1: -> B (spec #1) overridden by -> C (spec #2)

// Across assemblies in build
[mechanoid] Override in machine (2 assemblies):
  A via E1: -> B (assembly1) overridden by -> C (assembly2)
  Running via Cancel: -> Cancelled (happyPath) overridden by stay (inline)
```

---

## Design

### Assembly Class

```scala
// core/src/main/scala/mechanoid/machine/Assembly.scala

final class Assembly[S, E, +Cmd] private[machine] (
    val specs: List[TransitionSpec[S, E, Cmd]]
):
  /** Mark all specs as overriding. */
  infix def @@(aspect: Aspect): Assembly[S, E, Cmd] = aspect match
    case Aspect.overriding =>
      new Assembly(specs.map(_.copy(isOverride = true)))
    case _ => this

object Assembly:
  def empty[S, E]: Assembly[S, E, Nothing] = new Assembly(Nil)
```

### DSL Function

```scala
transparent inline def assembly[S, E](
    inline specs: TransitionSpec[S, E, ?]*
): Assembly[S, E, ?] =
  ${ Macros.assemblyImpl[S, E]('specs) }
```

### Key Insight: Compile-Time Visibility

The `assembly` macro expands to a **literal expression**: `new Assembly(List(spec1, spec2, ...))`. When `build` or another `assembly` encounters an Assembly val, it pattern matches on the AST to find this constructor call and extracts specs directly—no `sym.tree` lookup needed.

```scala
def extractAssemblySpecs(term: Term): Option[List[Term]] =
  term match
    case Apply(TypeApply(Select(New(tpt), "<init>"), _), List(listArg))
        if tpt.show.contains("Assembly") =>
      extractListElements(listArg)
    case Inlined(_, _, inner) => extractAssemblySpecs(inner)
    case _ => None
```

### Nested Assembly Composition

Since Assembly holds specs directly (not behind macro expansion), **assemblies can compose other assemblies**:

```scala
val errorHandling = assembly[S, E](
  all[Error] via Reset to Idle,
  all[Error] via Retry to Retrying,
)

val timeoutHandling = assembly[S, E](
  Processing via Timeout to TimedOut,
  Waiting via Timeout to TimedOut,
)

// Compose assemblies into a larger assembly
val commonBehaviors = assembly[S, E](
  errorHandling,      // Flattened at compile time
  timeoutHandling,    // Flattened at compile time
  Idle via Start to Running,
)

// Final machine
val machine = build[S, E](
  commonBehaviors,
  Running via Complete to Done,
)
```

**How it works:**
1. `assembly` macro detects Assembly arguments (same pattern matching as `build`)
2. Extracts and flattens specs from nested assemblies
3. Validates duplicates across ALL flattened specs (compile-time)
4. Generates `new Assembly(List(flattenedSpecs...))`

**No cyclic dependency** because Assembly is just data - accessing `assembly.specs` doesn't trigger macro expansion.

---

## Usage

```scala
// Define reusable assemblies (compile-time composable!)
val cancelBehaviors = assembly[State, Event](
  all[InProgress] via Cancel to Cancelled,
  all[Pending] via Timeout to Failed,
)

val happyPath = assembly[State, Event](
  Idle via Start to Running,
  Running via Complete to Done,
)

// Compose with full compile-time validation
val machine = build[State, Event](
  cancelBehaviors,
  happyPath,
  (Running via Cancel to stay) @@ Aspect.overriding,  // Override specific behavior
)

// Or with buildAll
val machine = buildAll[State, Event]:
  include(cancelBehaviors)
  include(happyPath)
  Done via Archive to Archived
```

---

## Implementation Steps

### Phase 1: Create Assembly Structure

**File:** `core/src/main/scala/mechanoid/machine/Assembly.scala` (NEW)

1. Create `Assembly[S, E, +Cmd]` class holding `List[TransitionSpec]`
2. Add `@@` operator support for `Aspect.overriding` (marks all specs)
3. Add `Assembly.empty[S, E]` factory

### Phase 2: Create assembly Macro

**File:** `core/src/main/scala/mechanoid/machine/Macros.scala`

1. Add `assemblyImpl[S, E]` macro implementation:
   - Extract specs from varargs
   - Perform duplicate detection within the assembly
   - Infer command type from specs
   - Generate `new Assembly(List(...))` expression

2. Add `assembly[S, E](...)` top-level inline function

### Phase 3: Update build/buildAll for Assembly

**File:** `core/src/main/scala/mechanoid/machine/Macros.scala`

1. Add `isAssemblyType(tpe: TypeRepr): Boolean` helper

2. Add `extractAssemblySpecs(term: Term): Option[List[Term]]`:
   - Pattern match on `new Assembly(List(...))` constructor
   - Handle `Inlined` wrappers
   - Extract spec terms from List constructor

3. Update `buildWithInferredCmdImpl`:
   - Separate Assembly, Machine, and TransitionSpec arguments
   - For each Assembly, extract specs at compile time
   - Merge assembly specs with inline specs for duplicate detection
   - Update code generation to flatten assembly specs

4. Update `buildAllImpl` similarly

5. Add `extractCmdTypeFromAssembly` for command type inference

### Phase 4: Remove Machine Composition & Refactor Macros

**Current state:** `Macros.scala` is 1701 lines - too large and contains dead code.

#### 4a. Split Macros.scala into focused modules

| New File | Contents | ~Lines |
|----------|----------|--------|
| `MacroUtils.scala` | Shared utilities: type helpers, hash extraction, `SpecHashInfo`, duplicate detection logic | ~300 |
| `MatcherMacros.scala` | `all[]`, `anyOf[]`, `anyOfEvents[]`, `event[]`, `state[]` implementations | ~200 |
| `BuildMacros.scala` | `buildImpl`, `buildWithInferredCmdImpl`, `buildAllImpl`, `assemblyImpl` | ~800 |
| `Macros.scala` | Top-level `inline def` wrappers, exports, DSL extensions | ~300 |

#### 4b. Remove Machine composition code

1. Remove `isMachineType` checks and related code
2. Remove runtime spec extraction from machines
3. Remove two-pass override algorithm in `Machine.fromSpecs`
4. Add clear compile error when Machine is passed to build

#### 4c. Scala 3.7 metaprogramming review

1. **Replace `asInstanceOf` with safer patterns** where possible:
   - Use `asExprOf[T]` instead of `.asInstanceOf[Expr[T]]`
   - Use pattern matching with type tests

2. **Use `quotes.reflect.asTerm` consistently**

3. **Review `Expr.ofList` usage** - ensure type safety

4. **Clean up dead code paths**:
   - Remove DSL string parsing fallback (may no longer be needed)
   - Remove unused helper functions
   - Simplify overly complex pattern matching

**File:** `core/src/main/scala/mechanoid/machine/Machine.scala`

1. Remove the two-pass override algorithm in `fromSpecs` (no longer needed)
2. Simplify duplicate detection - all validation now at compile time

### Phase 5: Update Tests

**File:** `core/src/test/scala/mechanoid/machine/AssemblySpec.scala` (NEW)

**Level 1 tests (assembly scope):**
1. Assembly creation with various spec types (inline, all[], anyOf, etc.)
2. Duplicate within assembly without override → compile error (commented test)
3. Duplicate within assembly with override → compiles, runtime check works
4. Override info message emitted at compile time

**Level 2 tests (build scope):**
5. Conflict between two assemblies without override → compile error (commented test)
6. Conflict between two assemblies with one marked `@@ Aspect.overriding` → compiles
7. Mixed assembly + inline specs with conflict → compile error without override
8. Cross-assembly override info messages emitted

**Other tests:**
9. Command type inference across assemblies
10. Composition with buildAll and include()
11. Machine argument in build → clear compile error message

**File:** `core/src/test/scala/mechanoid/machine/MachineSpec.scala`

1. Remove all machine composition tests (the "machine composition" suite)
2. Update any tests that used machine composition to use Assembly pattern instead

### Phase 6: Verification

Run verification steps before documentation overhaul:

1. **Compile**: `sbt compile` - all modules compile
2. **Tests**: `sbt test` - all tests pass
3. **Level 1 - Assembly scope**:
   - Duplicate within assembly without override → compile error
   - Duplicate within assembly with override → compiles, emits info
4. **Level 2 - Build scope**:
   - Conflict between assemblies without override → compile error
   - Conflict between assemblies with override → compiles, emits info
   - Mixed assembly + inline conflict → same behavior
5. **Machine removal**: Verify `build(machine, ...)` produces compile error directing to assembly
6. **Examples**: Update and verify examples compile

### Phase 7: Documentation Overhaul (Final Step)

**After all verifications pass**, perform a comprehensive documentation overhaul.

#### 7a. ScalaDoc Updates

Review and update ScalaDocs for all modified/new files:

| File | ScalaDoc Updates |
|------|------------------|
| `Assembly.scala` | Full class/method documentation with examples |
| `Macros.scala` | Update `build`, `buildAll`, `assembly` inline def docs |
| `MacroUtils.scala` | Document shared utilities |
| `MatcherMacros.scala` | Document matcher implementations |
| `BuildMacros.scala` | Document macro implementations |
| `Machine.scala` | Update to reflect simplified `fromSpecs` |
| `TransitionSpec.scala` | Ensure `isOverride` field documented |

**ScalaDoc requirements:**
- Every public API has clear description
- Examples use the new Assembly pattern
- Remove any references to Machine composition
- Consistent formatting and cross-references

#### 7b. Main Documentation Updates

**File:** `docs/DOCUMENTATION.md`

1. **Assembly Section** (NEW):
   - Why Assembly exists (compile-time validation vs Machine composition)
   - Creating assemblies with `assembly[S, E](...)`
   - Composing assemblies (nested composition)
   - Marking assemblies as overriding with `@@ Aspect.overriding`
   - Examples showing real-world patterns

2. **Duplicate Detection Section**:
   - Update table to show Assembly as compile-time
   - Remove Machine composition row
   - Add two-level validation explanation

3. **Build/BuildAll Section**:
   - Update to show Assembly as primary composition mechanism
   - Examples with assemblies + inline specs
   - Show override patterns

4. **Remove/Update**:
   - Remove all Machine composition examples
   - Update any examples using old patterns
   - Ensure consistency throughout

**File:** `docs/suite-dsl-design.md`

1. Update design rationale for Assembly
2. Document the cyclic macro dependency problem
3. Explain the compile-time extraction pattern
4. Update any diagrams/tables

#### 7c. README Updates

**File:** `README.md`

1. Update quick-start examples to show Assembly pattern
2. Ensure feature list reflects current API
3. Update any code snippets

#### 7d. Documentation Review Checklist

Before completing:
- [ ] No references to Machine composition remain (except migration notes)
- [ ] All code examples compile with current API
- [ ] ScalaDocs render correctly (`sbt doc`)
- [ ] Consistent terminology throughout
- [ ] Examples demonstrate best practices
- [ ] Error messages documented (what users will see)

---

## Files Summary

| File | Action | Description |
|------|--------|-------------|
| **Source Files** | | |
| `core/src/main/scala/mechanoid/machine/Assembly.scala` | CREATE | Assembly class |
| `core/src/main/scala/mechanoid/machine/MacroUtils.scala` | CREATE | Shared macro utilities |
| `core/src/main/scala/mechanoid/machine/MatcherMacros.scala` | CREATE | Matcher implementations |
| `core/src/main/scala/mechanoid/machine/BuildMacros.scala` | CREATE | build/buildAll/assembly macros |
| `core/src/main/scala/mechanoid/machine/Macros.scala` | REFACTOR | Slim down to inline defs + exports |
| `core/src/main/scala/mechanoid/machine/Machine.scala` | MODIFY | Simplify fromSpecs |
| `core/src/main/scala/mechanoid/Mechanoid.scala` | MODIFY | Export assembly and Assembly |
| **Test Files** | | |
| `core/src/test/scala/mechanoid/machine/AssemblySpec.scala` | CREATE | Assembly tests |
| `core/src/test/scala/mechanoid/machine/MachineSpec.scala` | MODIFY | Update composition tests |
| **Documentation** | | |
| `docs/DOCUMENTATION.md` | OVERHAUL | Comprehensive Assembly docs, remove Machine composition |
| `docs/suite-dsl-design.md` | MODIFY | Update design rationale |
| `README.md` | MODIFY | Update examples |

---

## Breaking Change Notes

This is a **breaking change** - Machine composition is fully removed:

**Before (no longer compiles):**
```scala
val base = build[S, E](...)
val extended = build[S, E](base, ...)  // COMPILE ERROR
```

**After:**
```scala
val base = assembly[S, E](...)         // Use assembly for reusable fragments
val extended = build[S, E](base, ...)  // Compile-time validation!
```

Since mechanoid is not yet released, this is acceptable. The migration is straightforward:
- Replace `build` with `assembly` for reusable transition collections
- `build` creates runnable Machines from specs and/or assemblies
- `assembly` creates composable fragments (cannot run)
