package mechanoid.machine

import scala.quoted.*

/** Assembly-related macro implementations.
  *
  * Contains the implementations for `assembly`, `assemblyAll`, and `include` macros.
  */
private[machine] object AssemblyMacros:

  /** Implementation of `assembly` macro - creates a compile-time composable collection of specs.
    *
    * This macro:
    *   1. Accepts TransitionSpec and Included[S, E, ?] arguments (from include macro)
    *   2. Performs Level 1 duplicate detection (within this assembly)
    *   3. Infers command type from all specs
    *   4. Generates a literal `Assembly.apply(List(...))` expression for compile-time visibility
    */
  def assemblyImpl[S: Type, E: Type](
      first: Expr[TransitionSpec[S, E, ?] | Included[S, E]],
      rest: Expr[Seq[TransitionSpec[S, E, ?] | Included[S, E]]],
  )(using Quotes): Expr[Assembly[S, E]] =
    import quotes.reflect.*

    // Use shared type checkers from MacroUtils
    def isTransitionSpecType(tpe: TypeRepr): Boolean = MacroUtils.isTransitionSpecType(tpe)
    def isIncludedType(tpe: TypeRepr): Boolean       = MacroUtils.isIncludedType(tpe)

    // Extract individual arg expressions - first is guaranteed, rest may be empty
    val restTerms: List[Term] = rest match
      case Varargs(exprs) => exprs.toList.map(_.asTerm)
      case other          =>
        report.errorAndAbort(s"Expected varargs, got: ${other.show}")

    val argTerms: List[Term] = first.asTerm :: restTerms

    // Extract hash info from Included terms' hashInfos field
    def extractHashInfosFromIncluded(term: Term): List[MacroUtils.SpecHashInfo] =
      def extractFromIncludedConstructor(t: Term): List[MacroUtils.SpecHashInfo] =
        t match
          // Match new Included[S, E](assembly, hashInfos) - TypeApply constructor
          case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
              if tpt.show.contains("Included") && args.length >= 2 =>
            extractHashInfoList(args(1))
          // Match new Included(assembly, hashInfos) - without type args
          case Apply(Select(New(tpt), "<init>"), args) if tpt.show.contains("Included") && args.length >= 2 =>
            extractHashInfoList(args(1))
          case Apply(_, args) =>
            if args.length >= 2 then extractHashInfoList(args(1)) else Nil
          case Inlined(_, _, inner) =>
            extractFromIncludedConstructor(inner)
          case Block(_, expr) =>
            extractFromIncludedConstructor(expr)
          case _ =>
            Nil

      def extractHashInfoList(listTerm: Term): List[MacroUtils.SpecHashInfo] =
        MacroUtils.extractListElements(listTerm).flatMap(extractSingleHashInfo)

      def extractSingleHashInfo(term: Term): Option[MacroUtils.SpecHashInfo] =
        term match
          // Match IncludedHashInfo constructor
          case Apply(_, args) if args.length >= 6 =>
            val stateHashes = MacroUtils.extractSetInts(args(0))
            val eventHashes = MacroUtils.extractSetInts(args(1))
            val stateNames  = MacroUtils.extractListStrings(args(2))
            val eventNames  = MacroUtils.extractListStrings(args(3))
            val targetDesc  = args(4) match
              case Literal(StringConstant(s)) => s
              case _                          => "?"
            val isOverride = MacroUtils.extractBoolean(args(5))
            if stateHashes.nonEmpty || eventHashes.nonEmpty then
              Some(
                MacroUtils.SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, isOverride, "?")
              )
            else None
          case Inlined(_, _, inner) =>
            extractSingleHashInfo(inner)
          // Handle SeqLiteral - varargs container
          case other if other.getClass.getName.contains("SeqLiteral") =>
            var result: Option[MacroUtils.SpecHashInfo] = None
            object ApplyFinder extends TreeAccumulator[Unit]:
              def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
                if result.isEmpty then
                  tree match
                    case Apply(fn, args) if args.length >= 6 && fn.show.contains("IncludedHashInfo") =>
                      val stateHashes = MacroUtils.extractSetInts(args(0))
                      val eventHashes = MacroUtils.extractSetInts(args(1))
                      val stateNames  = MacroUtils.extractListStrings(args(2))
                      val eventNames  = MacroUtils.extractListStrings(args(3))
                      val targetDesc  = args(4) match
                        case Literal(StringConstant(s)) => s
                        case _                          => "?"
                      val isOverride = MacroUtils.extractBoolean(args(5))
                      if stateHashes.nonEmpty || eventHashes.nonEmpty then
                        result = Some(
                          MacroUtils.SpecHashInfo(
                            stateHashes,
                            eventHashes,
                            stateNames,
                            eventNames,
                            targetDesc,
                            isOverride,
                            "?",
                          )
                        )
                      end if
                    case _ =>
                      foldOverTree((), tree)(owner)
            end ApplyFinder
            ApplyFinder.foldTree((), other)(Symbol.spliceOwner)
            result
          case _ =>
            None

      extractFromIncludedConstructor(term)
    end extractHashInfosFromIncluded

    // Build allSpecInfos in SOURCE ORDER by iterating over argTerms
    // This preserves the order: include(base), then spec @@ overriding
    var globalIdx    = 0
    val allSpecInfos = argTerms.flatMap { term =>
      if isTransitionSpecType(term.tpe) then
        val info   = MacroUtils.getSpecInfo(term, globalIdx)
        val result = List((info, globalIdx))
        globalIdx += 1
        result
      else if isIncludedType(term.tpe) then
        // Extract hash infos from this Included and add them in order
        val includedInfos = extractHashInfosFromIncluded(term)
        includedInfos.map { info =>
          val sourceDesc = MacroUtils.sourceDescFromInfo(info, globalIdx)
          val result     = (info.copy(sourceDesc = sourceDesc), globalIdx)
          globalIdx += 1
          result
        }
      else Nil
    }

    MacroUtils.checkDuplicates(allSpecInfos, "Duplicate transition in assembly")

    // LUB validation for producing effects
    // Get E's hash (the FSM's event type) and its sealed ancestors
    val eventTypeHash    = TypeRepr.of[E].typeSymbol.fullName.hashCode
    val eventAncestors   = ProducingMacros.getSealedAncestorHashes[E]
    val validEventHashes = eventAncestors + eventTypeHash

    // Validate each spec's producing effect has a common ancestor with E
    argTerms.zipWithIndex.foreach { case (term, idx) =>
      if isTransitionSpecType(term.tpe) then
        MacroUtils.extractProducingAncestorHashes(term) match
          case Some(producedAncestors) =>
            val commonAncestors = producedAncestors.intersect(validEventHashes)
            if commonAncestors.isEmpty then
              report.errorAndAbort(
                s"""Type mismatch in producing effect at spec #${idx + 1}!
                   |
                   |The produced event type does not share a common sealed ancestor with the FSM's event type.
                   |The produced event must be a case within the same sealed hierarchy as the FSM's event type ${TypeRepr
                    .of[E]
                    .typeSymbol
                    .name}.""".stripMargin,
                term.pos,
              )
            end if
          case None => () // No producing effect on this spec
    }

    // Code generation - include hash info as literal for compile-time extraction by include()
    val orderedSpecLists: List[Expr[List[TransitionSpec[S, E, ?]]]] = argTerms.map { term =>
      if isIncludedType(term.tpe) then '{ ${ term.asExpr.asInstanceOf[Expr[Included[S, E]]] }.specs }
      else '{ List(${ term.asExpr.asInstanceOf[Expr[TransitionSpec[S, E, ?]]] }) }
    }
    val orderedSpecListsExpr: Expr[List[List[TransitionSpec[S, E, ?]]]] =
      Expr.ofList(orderedSpecLists)

    // Generate literal hash info for compile-time extraction by include()
    val allHashInfoExprs = allSpecInfos.map { case (info, _) => MacroUtils.hashInfoToExpr(info) }
    val hashInfosExpr    = Expr.ofList(allHashInfoExprs)

    // Compute orphan overrides using shared helper
    val orphanInfoExprs = MacroUtils.computeOrphanExprs(allSpecInfos)
    val orphansExpr     = '{ Set(${ Varargs(orphanInfoExprs) }*) }

    '{ Assembly.apply[S, E]($orderedSpecListsExpr.flatten, $hashInfosExpr, $orphansExpr) }
  end assemblyImpl

  /** Implementation of `include` macro - wraps an assembly for composition.
    *
    * This macro extracts hash info from the Assembly's hashInfos field (which was embedded by the assembly macro). This
    * enables compile-time duplicate detection across includes.
    */
  def includeImpl[S: Type, E: Type](
      assemblyExpr: Expr[Assembly[S, E]]
  )(using Quotes): Expr[Included[S, E]] =
    import quotes.reflect.*

    // Extract hashInfos from Assembly.apply(specs, hashInfos, orphans) constructor
    def extractAssemblyHashInfos(term: Term): List[Term] =
      term match
        // Assembly.apply(specs, hashInfos, orphans) - 2+ args
        case Apply(TypeApply(Select(_, "apply"), _), args) if args.length >= 2 =>
          MacroUtils.extractListElements(args(1))
        case Apply(Select(_, "apply"), args) if args.length >= 2 =>
          MacroUtils.extractListElements(args(1))
        // new Assembly(specs, hashInfos, orphans)
        case Apply(Select(New(tpt), "<init>"), args) if tpt.show.contains("Assembly") && args.length >= 2 =>
          MacroUtils.extractListElements(args(1))
        case Inlined(_, _, inner) =>
          extractAssemblyHashInfos(inner)
        case Block(_, expr) =>
          extractAssemblyHashInfos(expr)
        // Follow val reference
        case Ident(_) if term.symbol.exists =>
          try
            term.symbol.tree match
              case ValDef(_, _, Some(rhs)) => extractAssemblyHashInfos(rhs)
              case _                       => Nil
          catch
            case _: Exception =>
              Nil
        case _ =>
          Nil

    val term          = assemblyExpr.asTerm
    val hashInfoTerms = extractAssemblyHashInfos(term)

    if hashInfoTerms.nonEmpty then
      // Convert the literal IncludedHashInfo constructors to expressions
      val hashInfoExprs: List[Expr[IncludedHashInfo]] = hashInfoTerms.flatMap(extractHashInfoFromTerm)

      if hashInfoExprs.nonEmpty then
        val hashInfosExpr = Expr.ofList(hashInfoExprs)
        '{ new Included($assemblyExpr, $hashInfosExpr) }
      else
        // Fall back to runtime extraction
        '{
          val asm = $assemblyExpr
          new Included(asm, asm.hashInfos)
        }
    else
      // Couldn't extract hash info - use assembly's hashInfos at runtime
      '{
        val asm = $assemblyExpr
        new Included(asm, asm.hashInfos)
      }
    end if
  end includeImpl

  /** Extract a single IncludedHashInfo from an AST term representing its constructor. */
  private def extractHashInfoFromTerm(using Quotes)(term: quotes.reflect.Term): Option[Expr[IncludedHashInfo]] =
    import quotes.reflect.*

    def extractFromArgs(args: List[Term]): Option[Expr[IncludedHashInfo]] =
      if args.length >= 6 then
        val stateHashes = MacroUtils.extractSetInts(args(0))
        val eventHashes = MacroUtils.extractSetInts(args(1))
        val stateNames  = MacroUtils.extractListStrings(args(2))
        val eventNames  = MacroUtils.extractListStrings(args(3))
        val targetDesc  = MacroUtils.unwrap(args(4)) match
          case Literal(StringConstant(s)) => s
          case _                          => "?"
        val isOverride = MacroUtils.extractBoolean(args(5))

        if stateHashes.nonEmpty || eventHashes.nonEmpty then
          val stateHashesExpr = Expr(stateHashes)
          val eventHashesExpr = Expr(eventHashes)
          val stateNamesExpr  = Expr(stateNames)
          val eventNamesExpr  = Expr(eventNames)
          val targetDescExpr  = Expr(targetDesc)
          val isOverrideExpr  = Expr(isOverride)
          Some('{
            IncludedHashInfo(
              $stateHashesExpr,
              $eventHashesExpr,
              $stateNamesExpr,
              $eventNamesExpr,
              $targetDescExpr,
              $isOverrideExpr,
            )
          })
        else None
        end if
      else None

    term match
      case Apply(TypeApply(_, _), args) if args.length >= 6 =>
        extractFromArgs(args)
      case Apply(_, args) if args.length >= 6 =>
        extractFromArgs(args)
      case Inlined(_, _, inner) =>
        extractHashInfoFromTerm(inner)
      // Handle SeqLiteral - varargs container that holds the actual Apply nodes
      case other if other.getClass.getName.contains("SeqLiteral") =>
        var result: Option[Expr[IncludedHashInfo]] = None
        object ApplyFinder extends TreeAccumulator[Unit]:
          def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
            if result.isEmpty then
              tree match
                case Apply(fn, args) if args.length >= 6 && fn.show.contains("IncludedHashInfo") =>
                  val _ = fn // Suppress unused warning
                  result = extractFromArgs(args)
                case _ =>
                  foldOverTree((), tree)(owner)
        ApplyFinder.foldTree((), other)(Symbol.spliceOwner)
        result
      case _ => None
    end match
  end extractHashInfoFromTerm

  /** Implementation of `assemblyAll` macro - block syntax for creating Assemblies. */
  def assemblyAllImpl[S: Type, E: Type](
      block: Expr[Any]
  )(using Quotes): Expr[Assembly[S, E]] =
    import quotes.reflect.*

    val term = block.asTerm

    // Use shared type checkers from MacroUtils
    def isTransitionSpec(term: Term): Boolean = MacroUtils.isTransitionSpecType(term.tpe)
    def isAssembly(term: Term): Boolean       = MacroUtils.isAssemblyType(term.tpe)
    def isIncluded(term: Term): Boolean       = MacroUtils.isIncludedType(term.tpe)

    def isIncludeCall(term: Term): Boolean =
      term match
        case Apply(Ident("include"), _)                   => true
        case Apply(Select(_, "include"), _)               => true
        case Apply(TypeApply(Ident("include"), _), _)     => true
        case Apply(TypeApply(Select(_, "include"), _), _) => true
        case Inlined(Some(call), _, _)                    =>
          call.show.contains("include") || call.symbol.name == "include"
        case Inlined(None, _, inner) => isIncludeCall(inner)
        case _                       => false

    def isUnwrappedValRef(term: Term): Boolean =
      if isIncludeCall(term) then false
      else
        term match
          case Ident(_)             => isAssembly(term) || isTransitionSpec(term)
          case Inlined(_, _, inner) => isUnwrappedValRef(inner)
          case _                    => false

    def isHelperValDef(stmt: Statement): Boolean = stmt match
      case ValDef(_, _, Some(rhs)) => !isTransitionSpec(rhs) && !isAssembly(rhs) && !isIncluded(rhs)
      case _                       => false

    def validateStatement(stmt: Statement): Unit = stmt match
      case term: Term if isUnwrappedValRef(term) =>
        val name = term match
          case Ident(n) => n
          case _        => "value"
        report.errorAndAbort(
          s"""Val reference '$name' requires include() wrapper in assemblyAll block.
             |
             |Use: include($name)""".stripMargin,
          term.pos,
        )
      case _ => ()

    case class BlockContents(
        helperVals: List[Statement],
        specTerms: List[Term],
        includedTerms: List[Term],
        orderedTerms: List[Term],
    )

    def processBlock(t: Term): BlockContents = t match
      case Block(statements, finalExpr) =>
        statements.foreach(validateStatement)
        finalExpr match
          case term: Term if isUnwrappedValRef(term) => validateStatement(term)
          case _                                     => ()

        val helperVals = statements.filter(isHelperValDef)

        val specTerms = statements.flatMap {
          case term: Term if isTransitionSpec(term)             => List(term)
          case ValDef(_, _, Some(rhs)) if isTransitionSpec(rhs) =>
            List(
              Ref(Symbol.requiredModule("scala.Predef"))
                .select(Symbol.requiredMethod("identity"))
                .appliedToType(rhs.tpe)
                .appliedTo(rhs)
            )
          case _ => Nil
        }

        val includedTerms = statements.flatMap {
          case term: Term if isIncluded(term)             => List(term)
          case ValDef(_, _, Some(rhs)) if isIncluded(rhs) =>
            List(
              Ref(Symbol.requiredModule("scala.Predef"))
                .select(Symbol.requiredMethod("identity"))
                .appliedToType(rhs.tpe)
                .appliedTo(rhs)
            )
          case _ => Nil
        }

        val orderedTerms = statements.flatMap { stmt =>
          stmt match
            case term: Term if isTransitionSpec(term) || isIncluded(term)            => List(term)
            case ValDef(_, _, Some(rhs)) if isTransitionSpec(rhs) || isIncluded(rhs) =>
              List(
                Ref(Symbol.requiredModule("scala.Predef"))
                  .select(Symbol.requiredMethod("identity"))
                  .appliedToType(rhs.tpe)
                  .appliedTo(rhs)
              )
            case _ => Nil
        }

        val finalSpecs    = if isTransitionSpec(finalExpr) then List(finalExpr) else Nil
        val finalIncluded = if isIncluded(finalExpr) then List(finalExpr) else Nil
        val finalOrdered  = if isTransitionSpec(finalExpr) || isIncluded(finalExpr) then List(finalExpr) else Nil

        BlockContents(
          helperVals,
          specTerms ++ finalSpecs,
          includedTerms ++ finalIncluded,
          orderedTerms ++ finalOrdered,
        )

      case Inlined(_, bindings, inner) =>
        val BlockContents(innerVals, innerSpecs, innerIncluded, innerOrdered) = processBlock(inner)
        BlockContents(bindings.toList ++ innerVals, innerSpecs, innerIncluded, innerOrdered)

      case other if isTransitionSpec(other) =>
        BlockContents(Nil, List(other), Nil, List(other))

      case other if isIncluded(other) =>
        BlockContents(Nil, Nil, List(other), List(other))

      case _ => BlockContents(Nil, Nil, Nil, Nil)

    val BlockContents(helperVals, specTerms, includedTerms, orderedTerms) = processBlock(term)

    if specTerms.isEmpty && includedTerms.isEmpty then
      report.errorAndAbort(
        "No TransitionSpec or include() expressions found in block. " +
          "Each line should be a transition (State via Event to Target) or include(assembly)."
      )

    // Extract specs from included for validation
    val includedSpecTerms = includedTerms.flatMap { term =>
      MacroUtils.extractAssemblySpecTerms(term)
    }

    val allSpecTerms = specTerms ++ includedSpecTerms

    // Hash-based duplicate detection using shared helper
    val specInfos = allSpecTerms.zipWithIndex.map { case (term, idx) =>
      (MacroUtils.getSpecInfo(term, idx), idx)
    }

    MacroUtils.checkDuplicates(specInfos, "Duplicate transition in assemblyAll")

    // LUB validation for producing effects
    // Get E's hash (the FSM's event type) and its sealed ancestors
    val eventTypeHash    = TypeRepr.of[E].typeSymbol.fullName.hashCode
    val eventAncestors   = ProducingMacros.getSealedAncestorHashes[E]
    val validEventHashes = eventAncestors + eventTypeHash

    // Validate each spec's producing effect has a common ancestor with E
    specTerms.zipWithIndex.foreach { case (term, idx) =>
      MacroUtils.extractProducingAncestorHashes(term) match
        case Some(producedAncestors) =>
          val commonAncestors = producedAncestors.intersect(validEventHashes)
          if commonAncestors.isEmpty then
            report.errorAndAbort(
              s"""Type mismatch in producing effect at spec #${idx + 1}!
                 |
                 |The produced event type does not share a common sealed ancestor with the FSM's event type.
                 |The produced event must be a case within the same sealed hierarchy as the FSM's event type ${TypeRepr
                  .of[E]
                  .typeSymbol
                  .name}.""".stripMargin,
              term.pos,
            )
          end if
        case None => () // No producing effect on this spec
    }

    // Code generation
    val orderedSpecLists: List[Expr[List[TransitionSpec[S, E, ?]]]] = orderedTerms.map { term =>
      if isIncluded(term) then '{ ${ term.asExpr.asInstanceOf[Expr[Included[S, E]]] }.specs }
      else '{ List(${ term.asExpr.asInstanceOf[Expr[TransitionSpec[S, E, ?]]] }) }
    }
    val orderedSpecListsExpr: Expr[List[List[TransitionSpec[S, E, ?]]]] =
      Expr.ofList(orderedSpecLists)

    // Generate literal hash info for compile-time extraction by include()
    val allHashInfoExprs = specInfos.map { case (info, _) => MacroUtils.hashInfoToExpr(info) }
    val hashInfosExpr    = Expr.ofList(allHashInfoExprs)

    // Compute orphan overrides using shared helper
    val orphanInfoExprs = MacroUtils.computeOrphanExprs(specInfos)
    val orphansExpr     = '{ Set(${ Varargs(orphanInfoExprs) }*) }

    val assemblyExpr = '{ Assembly.apply[S, E]($orderedSpecListsExpr.flatten, $hashInfosExpr, $orphansExpr) }

    if helperVals.nonEmpty then
      val assemblyTerm = assemblyExpr.asTerm
      Block(helperVals.toList, assemblyTerm).asExprOf[Assembly[S, E]]
    else assemblyExpr
  end assemblyAllImpl

end AssemblyMacros
