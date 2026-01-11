package mechanoid.macros

import scala.quoted.*
import mechanoid.core.CaseHasher

/** Macro utilities for deriving SealedEnum instances with fully qualified names and hash-based identification. */
object SealedEnumMacros:

  /** Case information extracted at compile time. */
  case class CaseInfo(simpleName: String, fullName: String, hash: Int)

  /** Extract case information for a sealed type at compile time.
    *
    * Returns an array of CaseInfo containing:
    *   - simpleName: The simple case name (e.g., "Idle")
    *   - fullName: The fully qualified name (e.g., "com.example.MyState.Idle")
    *   - hash: Computed at compile time
    *
    * Hash collisions are detected at compile time and result in a compilation error.
    *
    * @param hasher
    *   The CaseHasher to use (CaseHasher.Default or CaseHasher.Murmur3)
    */
  inline def extractCaseInfo[T](inline hasher: CaseHasher): Array[CaseInfo] =
    ${ extractCaseInfoImpl[T]('hasher) }

  private def extractCaseInfoImpl[T: Type](hasher: Expr[CaseHasher])(using Quotes): Expr[Array[CaseInfo]] =
    import quotes.reflect.*

    val tpe    = TypeRepr.of[T]
    val symbol = tpe.typeSymbol

    if !symbol.flags.is(Flags.Sealed) then
      report.errorAndAbort(
        s"Type ${symbol.fullName} must be a sealed trait or enum for SealedEnum derivation"
      )

    // Recursively find all leaf cases (non-sealed case classes/objects)
    def findLeafCases(sym: Symbol): List[Symbol] =
      sym.children.flatMap { child =>
        if child.flags.is(Flags.Sealed) then
          // Recurse into nested sealed traits
          findLeafCases(child)
        else if child.flags.is(Flags.Case) || child.flags.is(Flags.Enum) then
          // Leaf case - include it
          List(child)
        else Nil
      }

    val cases = findLeafCases(symbol)

    if cases.isEmpty then
      report.errorAndAbort(
        s"Sealed type ${symbol.fullName} has no leaf cases. Ensure it has at least one case class or case object."
      )

    // Detect which hasher is being used
    val (hashFn, hasherName) = detectHasher(hasher)

    // Extract case names and compute hashes at compile time
    val caseInfosWithHashes = cases.map { caseSymbol =>
      val simpleName = caseSymbol.name
      val fullName   = caseSymbol.fullName
      val hash       = hashFn(fullName)
      (simpleName, fullName, hash)
    }

    // Check for collisions at compile time
    val hashToNames = caseInfosWithHashes.groupBy(_._3)
    val collisions  = hashToNames.filter(_._2.size > 1)

    if collisions.nonEmpty then
      val collisionMsg = collisions
        .map { case (hash, cases) =>
          s"  Hash $hash: ${cases.map(_._2).mkString(", ")}"
        }
        .mkString("\n")
      report.errorAndAbort(
        s"Hash collision detected in sealed type ${symbol.fullName} using $hasherName hasher!\n" +
          s"The following cases have the same hash:\n$collisionMsg\n" +
          s"Consider using CaseHasher.murmur3 for better distribution."
      )
    end if

    // Generate array with pre-computed hashes
    val caseInfoExprs = caseInfosWithHashes.map { case (simple, full, hash) =>
      '{ CaseInfo(${ Expr(simple) }, ${ Expr(full) }, ${ Expr(hash) }) }
    }

    '{ Array(${ Varargs(caseInfoExprs) }*) }
  end extractCaseInfoImpl

  /** Detect the hasher type from the expression and return a compile-time hash function.
    *
    * Detection is based on the type of the hasher expression. For murmur3 to be detected, use the specific type:
    * `given CaseHasher.Murmur3.type = CaseHasher.murmur3`
    */
  private def detectHasher(hasher: Expr[CaseHasher])(using Quotes): (String => Int, String) =
    import quotes.reflect.*

    val murmur3Type = TypeRepr.of[CaseHasher.Murmur3.type]
    val hasherType  = hasher.asTerm.tpe.dealias

    if hasherType <:< murmur3Type then (scala.util.hashing.MurmurHash3.stringHash, "murmur3")
    else (_.hashCode, "default")
  end detectHasher

  /** Get the caseHash for a value of a sealed type.
    *
    * This macro extracts the ordinal from the value and maps it to the corresponding hash. Hashes are computed at
    * compile time.
    */
  inline def caseHashOf[T](inline value: T)(using inline hasher: CaseHasher): Int =
    ${ caseHashOfImpl[T]('value, 'hasher) }

  private def caseHashOfImpl[T: Type](value: Expr[T], hasher: Expr[CaseHasher])(using Quotes): Expr[Int] =
    import quotes.reflect.*

    val tpe    = TypeRepr.of[T]
    val symbol = tpe.typeSymbol

    if !symbol.flags.is(Flags.Sealed) then
      report.errorAndAbort(
        s"Type ${symbol.fullName} must be a sealed trait or enum"
      )

    // Recursively find all leaf cases (non-sealed case classes/objects)
    def findLeafCases(sym: Symbol): List[Symbol] =
      sym.children.flatMap { child =>
        if child.flags.is(Flags.Sealed) then findLeafCases(child)
        else if child.flags.is(Flags.Case) || child.flags.is(Flags.Enum) then List(child)
        else Nil
      }

    val cases = findLeafCases(symbol)

    // Get compile-time hash function
    val (hashFn, _) = detectHasher(hasher)

    // Build lookup based on comparing ordinal or class identity
    // For enums, use ordinal; for sealed traits with case classes, use class identity
    val isEnum = symbol.flags.is(Flags.Enum)

    if isEnum then
      // For enums, we can use ordinal - build a hash array indexed by ordinal
      // First, we need to map each case's ordinal to its hash
      // The ordinal matches the order of children
      val directChildren = symbol.children
      val hashByOrdinal  = directChildren.zipWithIndex.map { (child, ordinal) =>
        val hash = cases.find(_.fullName == child.fullName).map(c => hashFn(c.fullName)).getOrElse(-1)
        (ordinal, hash)
      }.toMap

      val maxOrdinal = directChildren.length
      val hashArray  = (0 until maxOrdinal).map(i => hashByOrdinal.getOrElse(i, -1)).toArray

      val hashArrayExpr = Expr(hashArray)
      '{
        val ordinal = $value.asInstanceOf[scala.reflect.Enum].ordinal
        $hashArrayExpr(ordinal)
      }
    else
      // For sealed traits (not enums), distinguish case objects vs case classes
      val baseCase: Expr[Int] = '{ throw new MatchError($value) }

      cases.foldRight(baseCase) { (caseSymbol, accExpr) =>
        val hash = Expr(hashFn(caseSymbol.fullName))

        if caseSymbol.flags.is(Flags.Module) then
          // Case object - use reference equality (eq)
          val singleton = Ref(caseSymbol).asExprOf[AnyRef]
          '{ if ($value.asInstanceOf[AnyRef] eq $singleton) then $hash else $accExpr }
        else
          // Case class - use isInstanceOf
          val caseType = caseSymbol.typeRef
          caseType.asType match
            case '[t] =>
              '{ if $value.isInstanceOf[t] then $hash else $accExpr }
      }
    end if
  end caseHashOfImpl

  /** Generate a caseHash function for a sealed type.
    *
    * This creates a function that pattern-matches on all leaf cases and returns their pre-computed hashes. Unlike
    * caseHashOf which takes a value inline, this returns a reusable function.
    */
  inline def generateCaseHash[T](inline hasher: CaseHasher): T => Int =
    ${ generateCaseHashImpl[T]('hasher) }

  private def generateCaseHashImpl[T: Type](hasher: Expr[CaseHasher])(using Quotes): Expr[T => Int] =
    import quotes.reflect.*

    val tpe    = TypeRepr.of[T]
    val symbol = tpe.typeSymbol

    if !symbol.flags.is(Flags.Sealed) then
      report.errorAndAbort(
        s"Type ${symbol.fullName} must be a sealed trait or enum"
      )

    // Recursively find all leaf cases
    def findLeafCases(sym: Symbol): List[Symbol] =
      sym.children.flatMap { child =>
        if child.flags.is(Flags.Sealed) then findLeafCases(child)
        else if child.flags.is(Flags.Case) || child.flags.is(Flags.Enum) then List(child)
        else Nil
      }

    val cases = findLeafCases(symbol)

    if cases.isEmpty then
      report.errorAndAbort(
        s"Sealed type ${symbol.fullName} has no leaf cases."
      )

    // Get compile-time hash function
    val (hashFn, _) = detectHasher(hasher)

    // Build lookup based on comparing ordinal or class identity
    // For enums, use ordinal; for sealed traits with case classes, use class identity
    val isEnum = symbol.flags.is(Flags.Enum)

    if isEnum then
      // For enums, we can use ordinal - build a hash array indexed by ordinal
      val directChildren = symbol.children
      val hashByOrdinal  = directChildren.zipWithIndex.map { (child, ordinal) =>
        val hash = cases.find(_.fullName == child.fullName).map(c => hashFn(c.fullName)).getOrElse(-1)
        (ordinal, hash)
      }.toMap

      val maxOrdinal = directChildren.length
      val hashArray  = (0 until maxOrdinal).map(i => hashByOrdinal.getOrElse(i, -1)).toArray

      val hashArrayExpr = Expr(hashArray)
      '{ (value: T) =>
        val ordinal = value.asInstanceOf[scala.reflect.Enum].ordinal
        $hashArrayExpr(ordinal)
      }
    else
      // For sealed traits (not enums), distinguish case objects vs case classes
      '{ (value: T) =>
        ${
          val baseCase: Expr[Int] = '{ throw new MatchError(value) }

          cases.foldRight(baseCase) { (caseSymbol, accExpr) =>
            val hash = Expr(hashFn(caseSymbol.fullName))

            if caseSymbol.flags.is(Flags.Module) then
              // Case object - use reference equality (eq)
              val singleton = Ref(caseSymbol).asExprOf[AnyRef]
              '{ if (value.asInstanceOf[AnyRef] eq $singleton) then $hash else $accExpr }
            else
              // Case class - use isInstanceOf
              val caseType = caseSymbol.typeRef
              caseType.asType match
                case '[t] =>
                  '{ if value.isInstanceOf[t] then $hash else $accExpr }
          }
        }
      }
    end if
  end generateCaseHashImpl

end SealedEnumMacros
