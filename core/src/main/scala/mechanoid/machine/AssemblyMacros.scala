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
      args: Expr[Seq[TransitionSpec[S, E, ?] | Included[S, E, ?]]]
  )(using Quotes): Expr[Assembly[S, E, ?]] =
    import quotes.reflect.*

    val transitionSpecTypeName = "TransitionSpec"
    val includedTypeName       = "mechanoid.machine.Included"

    // Check if a type is TransitionSpec
    def isTransitionSpecType(tpe: TypeRepr): Boolean =
      tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName.contains(transitionSpecTypeName)
        case _ => false

    // Check if a type is Included[S, E, Cmd]
    def isIncludedType(tpe: TypeRepr): Boolean =
      tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName == includedTypeName
        case _ => false

    // Extract individual arg expressions from varargs
    val argTerms: List[Term] = args match
      case Varargs(exprs) => exprs.toList.map(_.asTerm)
      case other          =>
        report.errorAndAbort(s"Expected varargs, got: ${other.show}")

    // Separate individual specs from Included wrappers
    val specTerms     = argTerms.filter(t => isTransitionSpecType(t.tpe))
    val includedTerms = argTerms.filter(t => isIncludedType(t.tpe))

    // Helper to extract boolean from term (handles Inlined wrapper)
    def extractBoolean(term: Term): Boolean =
      term match
        case Literal(BooleanConstant(b)) => b
        case Inlined(_, _, inner)        => extractBoolean(inner)
        case Typed(inner, _)             => extractBoolean(inner)
        case Block(_, expr)              => extractBoolean(expr)
        case _                           => false

    // Extract hash info from Included terms' hashInfos field
    def extractHashInfosFromIncluded(term: Term): List[MacroUtils.SpecHashInfo] =
      def extractFromIncludedConstructor(t: Term): List[MacroUtils.SpecHashInfo] =
        t match
          // Match new Included[S, E, Cmd](assembly, hashInfos) - TypeApply constructor
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
            val isOverride = extractBoolean(args(5))
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
                      val isOverride = extractBoolean(args(5))
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

    // For direct specs, extract hash info
    def getSpecInfo(term: Term, idx: Int): MacroUtils.SpecHashInfo =
      val termShowStr           = term.show
      val hasOverrideAtCallSite = termShowStr.contains("overriding")
      MacroUtils.extractHashInfo(term) match
        case Some(h) =>
          val sourceDesc =
            if h.stateNames.nonEmpty && h.eventNames.nonEmpty then
              s"${h.stateNames.mkString(",")} via ${h.eventNames.mkString(",")}"
            else s"spec #${idx + 1}"
          h.copy(
            isOverride = h.isOverride || hasOverrideAtCallSite,
            sourceDesc = sourceDesc,
          )
        case None =>
          MacroUtils.SpecHashInfo(Set.empty, Set.empty, Nil, Nil, "?", hasOverrideAtCallSite, s"spec #${idx + 1}")
      end match
    end getSpecInfo

    // Build allSpecInfos in SOURCE ORDER by iterating over argTerms
    // This preserves the order: include(base), then spec @@ overriding
    var globalIdx    = 0
    val allSpecInfos = argTerms.flatMap { term =>
      if isTransitionSpecType(term.tpe) then
        val info       = getSpecInfo(term, globalIdx)
        val sourceDesc =
          if info.stateNames.nonEmpty && info.eventNames.nonEmpty then
            s"${info.stateNames.mkString(",")} via ${info.eventNames.mkString(",")}"
          else s"spec #${globalIdx + 1}"
        val result = List((info.copy(sourceDesc = sourceDesc), globalIdx))
        globalIdx += 1
        result
      else if isIncludedType(term.tpe) then
        // Extract hash infos from this Included and add them in order
        val includedInfos = extractHashInfosFromIncluded(term)
        includedInfos.map { info =>
          val sourceDesc =
            if info.stateNames.nonEmpty && info.eventNames.nonEmpty then
              s"${info.stateNames.mkString(",")} via ${info.eventNames.mkString(",")}"
            else s"included spec #${globalIdx + 1}"
          val result = (info.copy(sourceDesc = sourceDesc), globalIdx)
          globalIdx += 1
          result
        }
      else Nil
    }

    if argTerms.isEmpty then '{ Assembly.empty[S, E] }
    else
      MacroUtils.checkDuplicates(allSpecInfos, "Duplicate transition in assembly")

      // Command type inference
      val specCmdTypes     = specTerms.flatMap(t => MacroUtils.extractCmdType(t.tpe))
      val includedCmdTypes = includedTerms.flatMap(t => MacroUtils.extractCmdType(t.tpe))
      val allCmdTypes      = specCmdTypes ++ includedCmdTypes
      val inferredCmdType  = MacroUtils.inferCommandType(allCmdTypes)

      // Code generation - include hash info as literal for compile-time extraction by include()
      inferredCmdType.asType match
        case '[cmd] =>
          val orderedSpecLists: List[Expr[List[TransitionSpec[S, E, cmd]]]] = argTerms.map { term =>
            if isIncludedType(term.tpe) then '{ ${ term.asExpr.asInstanceOf[Expr[Included[S, E, cmd]]] }.specs }
            else '{ List(${ term.asExpr.asInstanceOf[Expr[TransitionSpec[S, E, cmd]]] }) }
          }
          val orderedSpecListsExpr: Expr[List[List[TransitionSpec[S, E, cmd]]]] =
            Expr.ofList(orderedSpecLists)

          // Generate literal hash info for compile-time extraction by include()
          val allHashInfoExprs: List[Expr[IncludedHashInfo]] = allSpecInfos.map { case (info, _) =>
            val stateHashesExpr = Expr(info.stateHashes)
            val eventHashesExpr = Expr(info.eventHashes)
            val stateNamesExpr  = Expr(info.stateNames)
            val eventNamesExpr  = Expr(info.eventNames)
            val targetDescExpr  = Expr(info.targetDesc)
            val isOverrideExpr  = Expr(info.isOverride)
            '{
              IncludedHashInfo(
                $stateHashesExpr,
                $eventHashesExpr,
                $stateNamesExpr,
                $eventNamesExpr,
                $targetDescExpr,
                $isOverrideExpr,
              )
            }
          }
          val hashInfosExpr = Expr.ofList(allHashInfoExprs)

          '{ Assembly.apply[S, E, cmd]($orderedSpecListsExpr.flatten, $hashInfosExpr) }
      end match
    end if
  end assemblyImpl

  /** Implementation of `include` macro - wraps an assembly for composition.
    *
    * This macro extracts hash info from the Assembly's hashInfos field (which was embedded by the assembly macro). This
    * enables compile-time duplicate detection across includes.
    */
  def includeImpl[S: Type, E: Type, Cmd: Type](
      assemblyExpr: Expr[Assembly[S, E, Cmd]]
  )(using Quotes): Expr[Included[S, E, Cmd]] =
    import quotes.reflect.*

    // Extract hashInfos from Assembly.apply(specs, hashInfos) constructor
    def extractAssemblyHashInfos(term: Term): List[Term] =
      term match
        // Assembly.apply(specs, hashInfos)
        case Apply(TypeApply(Select(_, "apply"), _), List(_, hashInfosArg)) =>
          MacroUtils.extractListElements(hashInfosArg)
        case Apply(Select(_, "apply"), List(_, hashInfosArg)) =>
          MacroUtils.extractListElements(hashInfosArg)
        // new Assembly(specs, hashInfos)
        case Apply(Select(New(tpt), "<init>"), List(_, hashInfosArg)) if tpt.show.contains("Assembly") =>
          MacroUtils.extractListElements(hashInfosArg)
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

    // Helper to extract boolean from term (handles Inlined wrapper)
    def extractBooleanFromTerm(t: Term): Boolean =
      t match
        case Literal(BooleanConstant(b)) => b
        case Inlined(_, _, inner)        => extractBooleanFromTerm(inner)
        case Typed(inner, _)             => extractBooleanFromTerm(inner)
        case Block(_, expr)              => extractBooleanFromTerm(expr)
        case _                           => false

    def extractFromArgs(args: List[Term]): Option[Expr[IncludedHashInfo]] =
      if args.length >= 6 then
        val stateHashes = MacroUtils.extractSetInts(args(0))
        val eventHashes = MacroUtils.extractSetInts(args(1))
        val stateNames  = MacroUtils.extractListStrings(args(2))
        val eventNames  = MacroUtils.extractListStrings(args(3))
        val targetDesc  = args(4) match
          case Literal(StringConstant(s)) => s
          case _                          => "?"
        val isOverride = extractBooleanFromTerm(args(5))

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
  )(using Quotes): Expr[Assembly[S, E, ?]] =
    import quotes.reflect.*

    val term                   = block.asTerm
    val transitionSpecTypeName = "TransitionSpec"
    val assemblyTypeName       = "mechanoid.machine.Assembly"
    val includedTypeName       = "mechanoid.machine.Included"

    def isTransitionSpec(term: Term): Boolean =
      term.tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName.contains(transitionSpecTypeName)
        case _ => false

    def isAssembly(term: Term): Boolean =
      term.tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName == assemblyTypeName
        case _ => false

    def isIncluded(term: Term): Boolean =
      term.tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName == includedTypeName
        case _ => false

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

    // Hash-based duplicate detection
    def getSpecInfo(term: Term, idx: Int): MacroUtils.SpecHashInfo =
      val hasOverrideAtCallSite = term.show.contains("overriding")
      MacroUtils.extractHashInfo(term) match
        case Some(h) =>
          val sourceDesc =
            if h.stateNames.nonEmpty && h.eventNames.nonEmpty then
              s"${h.stateNames.mkString(",")} via ${h.eventNames.mkString(",")}"
            else s"spec #${idx + 1}"
          h.copy(
            isOverride = h.isOverride || hasOverrideAtCallSite,
            sourceDesc = sourceDesc,
          )
        case None =>
          MacroUtils.SpecHashInfo(Set.empty, Set.empty, Nil, Nil, "?", hasOverrideAtCallSite, s"spec #${idx + 1}")
      end match
    end getSpecInfo

    val specInfos = allSpecTerms.zipWithIndex.map { case (term, idx) =>
      (getSpecInfo(term, idx), idx)
    }

    MacroUtils.checkDuplicates(specInfos, "Duplicate transition in assemblyAll")

    // Command type inference
    val specCmdTypes     = specTerms.flatMap(t => MacroUtils.extractCmdType(t.tpe))
    val includedCmdTypes = includedTerms.flatMap(t => MacroUtils.extractCmdType(t.tpe))
    val allCmdTypes      = specCmdTypes ++ includedCmdTypes
    val inferredCmdType  = MacroUtils.inferCommandType(allCmdTypes)

    // Code generation
    inferredCmdType.asType match
      case '[cmd] =>
        val orderedSpecLists: List[Expr[List[TransitionSpec[S, E, cmd]]]] = orderedTerms.map { term =>
          if term.tpe.dealias.widen match
              case AppliedType(base, _) => base.typeSymbol.fullName == includedTypeName
              case _                    => false
          then '{ ${ term.asExpr.asInstanceOf[Expr[Included[S, E, cmd]]] }.specs }
          else '{ List(${ term.asExpr.asInstanceOf[Expr[TransitionSpec[S, E, cmd]]] }) }
        }
        val orderedSpecListsExpr: Expr[List[List[TransitionSpec[S, E, cmd]]]] =
          Expr.ofList(orderedSpecLists)

        // Generate literal hash info for compile-time extraction by include()
        val allHashInfoExprs: List[Expr[IncludedHashInfo]] = specInfos.map { case (info, _) =>
          val stateHashesExpr = Expr(info.stateHashes)
          val eventHashesExpr = Expr(info.eventHashes)
          val stateNamesExpr  = Expr(info.stateNames)
          val eventNamesExpr  = Expr(info.eventNames)
          val targetDescExpr  = Expr(info.targetDesc)
          val isOverrideExpr  = Expr(info.isOverride)
          '{
            IncludedHashInfo(
              $stateHashesExpr,
              $eventHashesExpr,
              $stateNamesExpr,
              $eventNamesExpr,
              $targetDescExpr,
              $isOverrideExpr,
            )
          }
        }
        val hashInfosExpr = Expr.ofList(allHashInfoExprs)

        val assemblyExpr = '{ Assembly.apply[S, E, cmd]($orderedSpecListsExpr.flatten, $hashInfosExpr) }

        if helperVals.nonEmpty then
          val assemblyTerm = assemblyExpr.asTerm
          Block(helperVals.toList, assemblyTerm).asExprOf[Assembly[S, E, cmd]]
        else assemblyExpr
    end match
  end assemblyAllImpl

end AssemblyMacros
