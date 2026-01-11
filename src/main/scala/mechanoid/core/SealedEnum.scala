package mechanoid.core

import scala.annotation.implicitNotFound
import mechanoid.macros.SealedEnumMacros
import mechanoid.macros.SealedEnumMacros.CaseInfo

/** Type class that proves a type is a sealed enum or sealed trait.
  *
  * This provides compile-time verification that state and event types are closed hierarchies, which is required for
  * hash-based matching in FSM definitions.
  *
  * Each case is identified by its `caseHash` - a hash of the fully qualified name. This provides stable identity that
  * doesn't depend on declaration order and avoids conflicts with Scala 3 enum's built-in `ordinal` method.
  *
  * Supports nested sealed traits - all leaf cases (non-sealed case classes/objects) are discovered recursively.
  *
  * This is an internal type - users should not interact with it directly. SealedEnum instances are automatically
  * derived for MState and MEvent subtypes.
  */
@implicitNotFound(
  "Type ${T} must be a sealed trait or enum.\n" +
    "Mechanoid requires compile-time knowledge of all cases for hash-based matching.\n" +
    "Make sure your type is declared as:\n" +
    "  - 'enum MyType extends MState/MEvent' (recommended), or\n" +
    "  - 'sealed trait MyType extends MState/MEvent' with subtypes (can be nested)"
)
private[mechanoid] trait SealedEnum[T]:
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
end SealedEnum

private[mechanoid] object SealedEnum:
  /** Automatically derive SealedEnum for any sealed type using macros.
    *
    * This derivation:
    *   - Recursively finds all leaf cases (including nested sealed traits)
    *   - Extracts fully qualified names for all cases at compile time
    *   - Computes stable hash values from those names using String.hashCode
    *   - Detects hash collisions at compile time (results in compilation error)
    *   - Generates pattern-matching code for caseHash lookups
    *
    * This is used internally by MState.sealedEnum and MEvent.sealedEnum givens. Users don't interact with this
    * directly.
    */
  inline given derived[T]: SealedEnum[T] =
    deriveWithHasher[T](CaseHasher.Default)

  /** Derive a SealedEnum with a specific hasher.
    *
    * This is used internally by MState.deriveWithHasher and MEvent.deriveWithHasher. Users don't interact with this
    * directly.
    */
  inline def deriveWithHasher[T](inline hasher: CaseHasher): SealedEnum[T] =
    new SealedEnum[T]:
      val caseInfos: Array[CaseInfo] = SealedEnumMacros.extractCaseInfo[T](hasher)

      val caseNames: Map[Int, String] = caseInfos.map(ci => ci.hash -> ci.simpleName).toMap

      // Use macro-generated pattern matching for caseHash
      // This works with nested sealed traits (Mirror.ordinal only works with direct children)
      private val hashFn: T => Int = SealedEnumMacros.generateCaseHash[T](hasher)

      def caseHash(value: T): Int = hashFn(value)
end SealedEnum
