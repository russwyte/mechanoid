package mechanoid.core

import scala.annotation.implicitNotFound
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
end SealedEnum

object SealedEnum:
  /** Automatically derive SealedEnum for any type with a Mirror.SumOf (sealed types and enums). */
  given derived[T](using m: Mirror.SumOf[T]): SealedEnum[T] with
    def ordinal(value: T): Int = m.ordinal(value.asInstanceOf[m.MirroredMonoType])
