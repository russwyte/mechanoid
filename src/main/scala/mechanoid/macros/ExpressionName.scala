package mechanoid.macros

import scala.quoted.*

/** Macro utilities for extracting compile-time information from expressions. */
object ExpressionName:

  /** Extract the fully qualified method/function name from an expression at compile time.
    *
    * This macro inspects the expression tree and extracts the full path to the method or function, making it easy to
    * locate in the codebase.
    *
    * Examples:
    *   - `foo(x)` -> "pkg.Obj.foo" (fully qualified)
    *   - `bar.baz(x)` -> "pkg.Bar.baz" (fully qualified)
    *   - `obj.method` -> "pkg.Obj.method" (fully qualified)
    *   - Simple identifiers -> just the name if no symbol available
    *   - Complex expressions -> simplified string representation
    *
    * This is useful for automatically generating human-readable descriptions of actions for visualization without
    * requiring explicit string parameters.
    */
  inline def of[T](inline expr: T): String = ${ extractNameImpl('expr) }

  private def extractNameImpl[T: Type](expr: Expr[T])(using Quotes): Expr[String] =
    import quotes.reflect.*

    def getFullName(term: Term): String =
      val symbol = term.symbol
      if symbol.isNoSymbol then extractSimpleName(term)
      else symbol.fullName

    def extractSimpleName(term: Term): String = term match
      case Apply(inner, _)      => extractSimpleName(inner)
      case Select(_, name)      => name
      case Ident(name)          => name
      case TypeApply(inner, _)  => extractSimpleName(inner)
      case Block(_, expr)       => extractSimpleName(expr)
      case Typed(expr, _)       => extractSimpleName(expr)
      case Inlined(_, _, expr)  => extractSimpleName(expr)
      case _                    =>
        val full = term.show(using Printer.TreeShortCode)
        if full.length > 60 then full.take(60) + "..." else full

    def extractName(term: Term): String = term match
      // Method call: foo(args) or obj.foo(args)
      case app @ Apply(Select(_, _), _) => getFullName(app)
      case app @ Apply(Ident(_), _)     => getFullName(app)

      // Multiple argument lists: foo(a)(b)
      case Apply(inner: Apply, _) => extractName(inner)

      // Property access: obj.foo
      case sel @ Select(_, _) => getFullName(sel)

      // Simple identifier: foo
      case id @ Ident(_) => getFullName(id)

      // Block: { ...; expr }
      case Block(_, expr) => extractName(expr)

      // Typed expression: (expr: Type)
      case Typed(expr, _) => extractName(expr)

      // Inlined expression (from inline methods)
      case Inlined(_, _, expr) => extractName(expr)

      // Type application: foo[T]
      case TypeApply(inner, _) => extractName(inner)

      // Fallback: show shortened version of the expression
      case _ =>
        val full = term.show(using Printer.TreeShortCode)
        if full.length > 60 then full.take(60) + "..." else full

    Expr(extractName(expr.asTerm))
end ExpressionName
