package mechanoid.core

import scala.annotation.{implicitNotFound, nowarn}
import mechanoid.macros.FiniteMacros
import mechanoid.macros.FiniteMacros.{CaseInfo, HierarchyInfo}

/** Type class that proves a type is a finite (sealed) set of cases.
  *
  * This provides compile-time verification that state and event types are closed hierarchies, which is required for
  * hash-based matching in FSM definitions. The name "Finite" connects to "Finite State Machine" - your states and
  * events must be finite, enumerable sets.
  *
  * Each case is identified by its `caseHash` - a hash of the fully qualified name. This provides stable identity that
  * doesn't depend on declaration order and avoids conflicts with Scala 3 enum's built-in `ordinal` method.
  *
  * Supports nested sealed traits - all leaf cases (non-sealed case classes/objects) are discovered recursively.
  *
  * Usage:
  * {{{
  * enum MyState derives Finite:
  *   case Idle, Running, Stopped
  *
  * enum MyEvent derives Finite:
  *   case Start, Stop, Pause
  * }}}
  */
@implicitNotFound(
  "Type ${T} must be a sealed trait or enum with `derives Finite`.\n" +
    "Mechanoid requires compile-time knowledge of all cases for hash-based matching.\n" +
    "Make sure your type is declared as:\n" +
    "  - 'enum MyType derives Finite' (recommended), or\n" +
    "  - 'sealed trait MyType' with subtypes and a given Finite instance"
)
trait Finite[T]:
  /** Extract the caseHash (hash of fully qualified name) from an instance.
    *
    * This provides stable identification that doesn't depend on declaration order.
    */
  def caseHash(value: T): Int

  /** Get all case names mapped by their hash. */
  def caseNames: Map[Int, String]

  /** Get all case information (simple name, full name, hash). */
  def caseInfos: Array[CaseInfo]

  /** Get the name for a specific hash. */
  def nameFor(hash: Int): String = caseNames.getOrElse(hash, s"Unknown($hash)")

  /** Get the name for an instance. */
  def nameOf(value: T): String = nameFor(caseHash(value))

  /** Get all hashes in declaration order. */
  def allHashes: Array[Int] = caseInfos.map(_.hash)

  /** Get hierarchy information mapping parent types to their leaf descendants.
    *
    * This enables `all[ParentState]` to expand to transitions for all leaf states under that parent.
    */
  def hierarchyInfo: HierarchyInfo

  /** Get all leaf hashes that are descendants of the given parent hash.
    *
    * Returns empty set if the hash doesn't correspond to a parent type.
    */
  def leafHashesFor(parentHash: Int): Set[Int] =
    hierarchyInfo.parentToLeaves.getOrElse(parentHash, Set.empty)
end Finite

object Finite:
  /** Automatically derive Finite for any sealed type using macros.
    *
    * This derivation:
    *   - Recursively finds all leaf cases (including nested sealed traits)
    *   - Extracts fully qualified names for all cases at compile time
    *   - Computes stable hash values from those names using String.hashCode
    *   - Detects hash collisions at compile time (results in compilation error)
    *   - Generates pattern-matching code for caseHash lookups
    *
    * Usage:
    * {{{
    * enum MyState derives Finite:
    *   case Idle, Running
    * }}}
    */
  inline given derived[T]: Finite[T] =
    deriveWithHasher[T](CaseHasher.Default)

  /** Derive a Finite instance with a specific hasher.
    *
    * Use this when the default hasher produces collisions (the compiler will tell you):
    * {{{
    * enum MyState derives Finite:
    *   case Idle, Running
    *
    * object MyState:
    *   given Finite[MyState] = Finite.deriveWithHasher[MyState](CaseHasher.Murmur3)
    * }}}
    */
  @nowarn("msg=New anonymous class definition will be duplicated")
  inline def deriveWithHasher[T](inline hasher: CaseHasher): Finite[T] =
    new Finite[T]:
      val caseInfos: Array[CaseInfo] = FiniteMacros.extractCaseInfo[T](hasher)

      val caseNames: Map[Int, String] = caseInfos.map(ci => ci.hash -> ci.simpleName).toMap

      val hierarchyInfo: HierarchyInfo = FiniteMacros.extractHierarchyInfo[T](hasher)

      // Use macro-generated pattern matching for caseHash
      // This works with nested sealed traits (Mirror.ordinal only works with direct children)
      private val hashFn: T => Int = FiniteMacros.generateCaseHash[T](hasher)

      def caseHash(value: T): Int = hashFn(value)
end Finite
