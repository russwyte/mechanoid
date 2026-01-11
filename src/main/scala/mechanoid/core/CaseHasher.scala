package mechanoid.core

/** Marker trait for case hash algorithms.
  *
  * Mechanoid provides two built-in hashers:
  *   - `CaseHasher.Default` - Uses `String.hashCode`, lightweight and efficient
  *   - `CaseHasher.Murmur3` - Uses MurmurHash3, better distribution for collision resistance
  *
  * The default hasher is used automatically for all MState and MEvent types. Use Murmur3 only if you encounter hash
  * collisions (the compiler will tell you).
  *
  * Hash collisions are detected at compile time and result in a compilation error.
  *
  * Example:
  * {{{
  * // Default (automatic)
  * enum MyState extends MState:
  *   case Idle, Running
  *
  * // Murmur3 (explicit, only if needed for collision resolution)
  * object MyState:
  *   given murmur3Hasher = MState.deriveWithHasher[MyState](CaseHasher.Murmur3)
  * }}}
  */
sealed trait CaseHasher

object CaseHasher:
  /** Default hasher - uses String.hashCode.
    *
    * Lightweight and efficient, suitable for most use cases. Hash collisions are extremely rare for typical sealed
    * hierarchies.
    */
  case object Default extends CaseHasher

  /** MurmurHash3 hasher - better distribution for collision resistance.
    *
    * Use this if the default hasher produces collisions (the compiler will tell you).
    */
  case object Murmur3 extends CaseHasher
end CaseHasher
