package mechanoid.core

import scala.annotation.implicitNotFound
import scala.compiletime.*
import scala.deriving.Mirror

/** Type class that proves a type is a sealed enum or sealed trait.
  *
  * This provides compile-time verification that state and event types are closed hierarchies, which is required for
  * ordinal-based matching in FSM definitions.
  *
  * The `@implicitNotFound` annotation provides a clear error message when users try to use non-sealed types.
  */
@implicitNotFound(
  "Type ${T} must be a sealed trait or enum.\n" +
    "Mechanoid requires compile-time knowledge of all cases for ordinal-based matching.\n" +
    "Make sure your type is declared as:\n" +
    "  - 'enum MyType extends MState/MEvent' (recommended), or\n" +
    "  - 'sealed trait MyType extends MState/MEvent' with all subtypes in the same file"
)
trait SealedEnum[T]:
  /** Extract the ordinal (case index) from an instance. */
  def ordinal(value: T): Int

  /** Get all case names as an array, indexed by ordinal. */
  def caseNames: Array[String]

  /** Get the name for a specific ordinal. */
  def nameFor(ordinal: Int): String = caseNames(ordinal)

  /** Get the name for an instance. */
  def nameOf(value: T): String = nameFor(ordinal(value))
end SealedEnum

object SealedEnum:
  /** Extract case names from a tuple of string literals. */
  private inline def extractNames[T <: Tuple]: Array[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Array.empty[String]
      case _: (head *: tail) =>
        constValue[head].toString +: extractNames[tail]

  /** Automatically derive SealedEnum for any type with a Mirror.SumOf (sealed types and enums). */
  inline given derived[T](using m: Mirror.SumOf[T]): SealedEnum[T] = new SealedEnum[T]:
    def ordinal(value: T): Int    = m.ordinal(value.asInstanceOf[m.MirroredMonoType])
    val caseNames: Array[String]  = extractNames[m.MirroredElemLabels]
