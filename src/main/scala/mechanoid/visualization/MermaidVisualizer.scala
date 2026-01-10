package mechanoid.visualization

import mechanoid.core.*
import mechanoid.dsl.FSMDefinition
import mechanoid.persistence.command.*
import scala.concurrent.duration.Duration

/** Generates Mermaid diagram syntax for FSM visualization. */
object MermaidVisualizer:

  /** Generate a state diagram showing FSM structure.
    *
    * Output format:
    * ```mermaid
    * stateDiagram-v2
    *     [*] --> Created
    *     Created --> Processing: StartEvent
    *     Processing --> Completed: FinishEvent
    * ```
    */
  def stateDiagram[S <: MState, E <: MEvent, R, Err](
      fsm: FSMDefinition[S, E, R, Err],
      initialState: Option[S] = None,
  ): String =
    val sb = StringBuilder()
    sb.append("stateDiagram-v2\n")

    // Add initial state arrow if provided
    initialState.foreach { init =>
      sb.append(s"    [*] --> ${fsm.stateEnum.nameOf(init)}\n")
    }

    // Group transitions by source state for cleaner output
    val transitionsBySource = fsm.transitionMeta.groupBy(_.fromStateOrdinal)

    // Add transitions
    transitionsBySource.toList.sortBy(_._1).foreach { case (fromOrdinal, transitions) =>
      val fromName = fsm.stateEnum.nameFor(fromOrdinal)
      transitions.foreach { meta =>
        val eventName = fsm.eventEnum.nameFor(meta.eventOrdinal)
        val label     = meta.description match
          case Some(desc) if desc.startsWith("stop") => s"$eventName [stop]"
          case Some("dynamic")                       => s"$eventName [dynamic]"
          case _                                     => eventName

        val guardLabel = if meta.hasGuard then s"$label [guard]" else label

        meta.targetStateOrdinal match
          case Some(targetOrdinal) =>
            val targetName = fsm.stateEnum.nameFor(targetOrdinal)
            sb.append(s"    $fromName --> $targetName: $guardLabel\n")
          case None if meta.description.exists(_.startsWith("stop")) =>
            sb.append(s"    $fromName --> [*]: $guardLabel\n")
          case None =>
            // Stay transition - self-loop
            sb.append(s"    $fromName --> $fromName: $guardLabel\n")
      }
    }

    // Add state notes for timeouts and lifecycles
    fsm.timeouts.foreach { case (stateOrdinal, duration) =>
      val stateName = fsm.stateEnum.nameFor(stateOrdinal)
      sb.append(s"    note right of $stateName: timeout: ${formatDuration(duration)}\n")
    }

    // Add notes for states with entry/exit actions
    fsm.lifecycles.foreach { case (stateOrdinal, lifecycle) =>
      val stateName   = fsm.stateEnum.nameFor(stateOrdinal)
      val annotations = List(
        lifecycle.onEntry.map(_ => simplifyFunctionName(lifecycle.onEntryDescription.getOrElse("entry action"))),
        lifecycle.onExit.map(_ => simplifyFunctionName(lifecycle.onExitDescription.getOrElse("exit action"))),
      ).flatten
      if annotations.nonEmpty then sb.append(s"    note left of $stateName: ${annotations.mkString(", ")}\n")
    }

    sb.toString
  end stateDiagram

  /** Generate a sequence diagram showing execution trace.
    *
    * Output format:
    * ```mermaid
    * sequenceDiagram
    *     participant FSM
    *     Note over FSM: Created
    *     FSM->>FSM: StartEvent
    *     Note over FSM: Processing
    *     FSM->>FSM: FinishEvent
    *     Note over FSM: Completed
    * ```
    */
  def sequenceDiagram[S <: MState, E <: MEvent](
      trace: ExecutionTrace[S, E],
      stateEnum: SealedEnum[S],
      eventEnum: SealedEnum[E],
  ): String =
    val sb = StringBuilder()
    sb.append("sequenceDiagram\n")
    sb.append(s"    participant FSM as ${trace.instanceId}\n")

    // Initial state
    sb.append(s"    Note over FSM: ${stateEnum.nameOf(trace.initialState)}\n")

    // Each transition step
    trace.steps.foreach { step =>
      val eventName =
        if step.isTimeout then "Timeout"
        else eventEnum.nameOf(step.event)

      val toName = stateEnum.nameOf(step.to)

      if step.isSelfTransition then sb.append(s"    FSM->>FSM: $eventName (stay)\n")
      else
        sb.append(s"    FSM->>FSM: $eventName\n")
        sb.append(s"    Note over FSM: $toName\n")
    }

    // Current state marker
    sb.append(s"    Note over FSM: Current: ${stateEnum.nameOf(trace.currentState)}\n")

    sb.toString
  end sequenceDiagram

  /** Generate a flowchart showing execution path with highlighting.
    *
    * Output format:
    * ```mermaid
    * flowchart LR
    *     Created((Created))
    *     Processing((Processing))
    *     Completed((Completed))
    *     Created -->|StartEvent| Processing
    *     Processing -->|FinishEvent| Completed
    *     style Completed fill:#90EE90
    * ```
    */
  def flowchart[S <: MState, E <: MEvent, R, Err](
      fsm: FSMDefinition[S, E, R, Err],
      trace: Option[ExecutionTrace[S, E]] = None,
  ): String =
    val sb = StringBuilder()
    sb.append("flowchart LR\n")

    // Collect all states
    val allStates = fsm.transitionMeta
      .flatMap { meta =>
        List(meta.fromStateOrdinal) ++ meta.targetStateOrdinal.toList
      }
      .distinct
      .sorted

    // Define state nodes
    allStates.foreach { stateOrdinal =>
      val stateName = fsm.stateEnum.nameFor(stateOrdinal)
      sb.append(s"    $stateName(($stateName))\n")
    }

    // Add terminal node if any stop transitions
    val hasStopTransitions = fsm.transitionMeta.exists(_.description.exists(_.startsWith("stop")))
    if hasStopTransitions then sb.append("    END([End])\n")

    sb.append("\n")

    // Add transitions
    fsm.transitionMeta.foreach { meta =>
      val fromName  = fsm.stateEnum.nameFor(meta.fromStateOrdinal)
      val eventName = fsm.eventEnum.nameFor(meta.eventOrdinal)
      val label     =
        if meta.hasGuard then s"$eventName [guard]"
        else eventName

      meta.targetStateOrdinal match
        case Some(targetOrdinal) =>
          val targetName = fsm.stateEnum.nameFor(targetOrdinal)
          sb.append(s"    $fromName -->|$label| $targetName\n")
        case None if meta.description.exists(_.startsWith("stop")) =>
          sb.append(s"    $fromName -->|$label| END\n")
        case None =>
          sb.append(s"    $fromName -->|$label| $fromName\n")
    }

    // Highlight current state and visited path if trace provided
    trace.foreach { t =>
      sb.append("\n")
      // Highlight visited states
      t.visitedStates.foreach { state =>
        val stateName = fsm.stateEnum.nameOf(state)
        sb.append(s"    style $stateName fill:#ADD8E6\n") // Light blue for visited
      }
      // Highlight current state
      val currentName = fsm.stateEnum.nameOf(t.currentState)
      sb.append(s"    style $currentName fill:#90EE90\n") // Light green for current
    }

    sb.toString
  end flowchart

  private def formatDuration(d: Duration): String =
    if d.toMillis < 1000 then s"${d.toMillis}ms"
    else if d.toSeconds < 60 then s"${d.toSeconds}s"
    else if d.toMinutes < 60 then s"${d.toMinutes}m"
    else s"${d.toHours}h"

  /** Escape special characters for Mermaid diagram text. */
  private def escapeMermaid(text: String): String =
    text
      .replace("#", "#35;")
      .replace(";", "#59;")
      .replace("<", "#60;")
      .replace(">", "#62;")

  /** Format a command string for Mermaid visualization.
    *
    * Returns (commandType, formattedDetails) where:
    *   - commandType is just the class name (e.g., "ProcessPayment")
    *   - formattedDetails is the parameters formatted for a Note box with line breaks
    */
  private def formatCommandForMermaid(text: String): (String, String) =
    val openParen  = text.indexOf('(')
    val closeParen = text.lastIndexOf(')')
    if openParen > 0 && closeParen > openParen then
      val cmdType = text.substring(0, openParen)
      val args    = text.substring(openParen + 1, closeParen)
      // Escape each arg first, then join with line breaks
      val formattedArgs = args.split(", ").map(escapeMermaid).mkString("<br/>")
      (cmdType, formattedArgs)
    else (escapeMermaid(text), "")
  end formatCommandForMermaid

  // ============================================
  // Enhanced Visualizations with Command Info
  // ============================================

  /** Command mapping for visualization - maps state ordinals to commands triggered on entry. */
  case class StateCommandMapping[Cmd](
      stateOrdinal: Int,
      commands: List[Cmd],
  )

  /** Generate a state diagram with command information.
    *
    * Shows which commands are triggered when entering each state.
    */
  def stateDiagramWithCommands[S <: MState, E <: MEvent, R, Err, Cmd](
      fsm: FSMDefinition[S, E, R, Err],
      stateCommands: Map[Int, List[String]], // stateOrdinal -> command type names
      initialState: Option[S] = None,
  ): String =
    val sb = StringBuilder()
    sb.append("stateDiagram-v2\n")

    // Add initial state arrow if provided
    initialState.foreach { init =>
      sb.append(s"    [*] --> ${fsm.stateEnum.nameOf(init)}\n")
    }

    // Group transitions by source state for cleaner output
    val transitionsBySource = fsm.transitionMeta.groupBy(_.fromStateOrdinal)

    // Add transitions
    transitionsBySource.toList.sortBy(_._1).foreach { case (fromOrdinal, transitions) =>
      val fromName = fsm.stateEnum.nameFor(fromOrdinal)
      transitions.foreach { meta =>
        val eventName = fsm.eventEnum.nameFor(meta.eventOrdinal)
        val label     = meta.description match
          case Some(desc) if desc.startsWith("stop") => s"$eventName [stop]"
          case Some("dynamic")                       => s"$eventName [dynamic]"
          case _                                     => eventName

        val guardLabel = if meta.hasGuard then s"$label [guard]" else label

        meta.targetStateOrdinal match
          case Some(targetOrdinal) =>
            val targetName = fsm.stateEnum.nameFor(targetOrdinal)
            sb.append(s"    $fromName --> $targetName: $guardLabel\n")
          case None if meta.description.exists(_.startsWith("stop")) =>
            sb.append(s"    $fromName --> [*]: $guardLabel\n")
          case None =>
            sb.append(s"    $fromName --> $fromName: $guardLabel\n")
      }
    }

    // Add state notes for timeouts
    fsm.timeouts.foreach { case (stateOrdinal, duration) =>
      val stateName = fsm.stateEnum.nameFor(stateOrdinal)
      sb.append(s"    note right of $stateName: timeout: ${formatDuration(duration)}\n")
    }

    // Add notes for states with entry actions AND commands
    // Note: Mermaid state diagrams are sensitive to special chars in notes
    fsm.lifecycles.foreach { case (stateOrdinal, lifecycle) =>
      val stateName = fsm.stateEnum.nameFor(stateOrdinal)
      val entryDesc = lifecycle.onEntry.flatMap(_ => lifecycle.onEntryDescription).map(simplifyFunctionName)
      val commands  = stateCommands.getOrElse(stateOrdinal, Nil)

      val annotations = List(
        entryDesc,
        if commands.nonEmpty then Some(s"[${commands.mkString(", ")}]") else None,
        lifecycle.onExit.flatMap(_ => lifecycle.onExitDescription).map(simplifyFunctionName),
      ).flatten

      if annotations.nonEmpty then sb.append(s"    note left of $stateName : ${annotations.mkString(" ")}\n")
    }

    sb.toString
  end stateDiagramWithCommands

  /** Simplify a fully qualified function name to just the method name for cleaner display. */
  private def simplifyFunctionName(fqn: String): String =
    // Extract just the method name from fully qualified names like
    // "mechanoid.examples.PetStoreApp$.OrderFSMManager.enqueuePaymentCommand"
    fqn.split('.').lastOption.getOrElse(fqn)

  /** Generate a sequence diagram showing execution trace with command details.
    *
    * Shows FSM state transitions alongside command execution.
    */
  def sequenceDiagramWithCommands[S <: MState, E <: MEvent, Id, Cmd](
      trace: ExecutionTrace[S, E],
      stateEnum: SealedEnum[S],
      eventEnum: SealedEnum[E],
      commands: List[PendingCommand[Id, Cmd]],
      commandName: Cmd => String,
  ): String =
    val sb = StringBuilder()
    // Add CSS to left-align note text
    sb.append("%%{init: {'themeCSS': '.noteText { text-align: left !important; }'}}%%\n")
    sb.append("sequenceDiagram\n")
    sb.append(s"    participant FSM as ${trace.instanceId}\n")
    sb.append("    participant CQ as CommandQueue\n")
    sb.append("    participant W as Worker\n")
    sb.append("\n")

    // Sort commands by enqueued time
    val sortedCommands = commands.sortBy(_.enqueuedAt)

    // Initial state
    sb.append(s"    Note over FSM: ${stateEnum.nameOf(trace.initialState)}\n")

    // Interleave FSM transitions with command execution
    var cmdIdx = 0
    trace.steps.foreach { step =>
      val eventName =
        if step.isTimeout then "Timeout"
        else eventEnum.nameOf(step.event)

      val toName = stateEnum.nameOf(step.to)

      // FSM transition
      if step.isSelfTransition then sb.append(s"    FSM->>FSM: $eventName (stay)\n")
      else
        sb.append(s"    FSM->>FSM: $eventName\n")
        sb.append(s"    Note over FSM: $toName\n")

      // Show commands enqueued around this transition
      while cmdIdx < sortedCommands.length && sortedCommands(cmdIdx).enqueuedAt.isBefore(
          step.timestamp.plusMillis(100)
        )
      do
        val cmd                   = sortedCommands(cmdIdx)
        val fullName              = commandName(cmd.command)
        val (cmdType, cmdDetails) = formatCommandForMermaid(fullName)
        val statusIcon            = cmd.status match
          case CommandStatus.Completed => "✅"
          case CommandStatus.Failed    => "❌"
          case CommandStatus.Skipped   => "⏭️"
          case _                       => "⏳"

        sb.append(s"    FSM->>CQ: enqueue($cmdType)\n")
        if cmdDetails.nonEmpty then sb.append(s"    Note right of CQ: $cmdDetails\n")
        if cmd.status == CommandStatus.Completed || cmd.status == CommandStatus.Failed then
          sb.append(s"    CQ->>W: claim\n")
          sb.append(s"    W->>CQ: $statusIcon ${cmd.status}\n")
        cmdIdx += 1
      end while
    }

    // Show remaining commands
    while cmdIdx < sortedCommands.length do
      val cmd                   = sortedCommands(cmdIdx)
      val fullName              = commandName(cmd.command)
      val (cmdType, cmdDetails) = formatCommandForMermaid(fullName)
      val statusIcon            = cmd.status match
        case CommandStatus.Completed => "✅"
        case CommandStatus.Failed    => "❌"
        case CommandStatus.Skipped   => "⏭️"
        case _                       => "⏳"

      sb.append(s"    FSM->>CQ: enqueue($cmdType)\n")
      if cmdDetails.nonEmpty then sb.append(s"    Note right of CQ: $cmdDetails\n")
      if cmd.status == CommandStatus.Completed || cmd.status == CommandStatus.Failed then
        sb.append(s"    CQ->>W: claim\n")
        sb.append(s"    W->>CQ: $statusIcon ${cmd.status}\n")
      cmdIdx += 1
    end while

    // Current state marker
    sb.append(s"    Note over FSM: Current: ${stateEnum.nameOf(trace.currentState)}\n")

    sb.toString
  end sequenceDiagramWithCommands

  /** Generate a flowchart showing FSM structure with command lanes.
    *
    * Shows states in one lane and commands in another, with connections.
    */
  def flowchartWithCommands[S <: MState, E <: MEvent, R, Err](
      fsm: FSMDefinition[S, E, R, Err],
      stateCommands: Map[Int, List[String]], // stateOrdinal -> command type names
      trace: Option[ExecutionTrace[S, E]] = None,
  ): String =
    val sb = StringBuilder()
    sb.append("flowchart TB\n")

    // Create subgraph for FSM states
    sb.append("    subgraph FSM[\"🔄 FSM States\"]\n")
    sb.append("        direction LR\n")

    // Collect all states
    val allStates = fsm.transitionMeta
      .flatMap { meta =>
        List(meta.fromStateOrdinal) ++ meta.targetStateOrdinal.toList
      }
      .distinct
      .sorted

    // Define state nodes with emojis for special states
    allStates.foreach { stateOrdinal =>
      val stateName = fsm.stateEnum.nameFor(stateOrdinal)
      val label     = stateName.toLowerCase match
        case n if n.contains("cancel") || n.contains("fail")                              => s"❌ $stateName"
        case n if n.contains("complete") || n.contains("deliver") || n.contains("done")   => s"✅ $stateName"
        case n if n.contains("process") || n.contains("pending") || n.contains("request") => s"⏳ $stateName"
        case n if n.contains("ship")                                                      => s"📦 $stateName"
        case n if n.contains("paid") || n.contains("payment")                             => s"💰 $stateName"
        case n if n.contains("creat")                                                     => s"🆕 $stateName"
        case _                                                                            => stateName
      sb.append(s"        $stateName((\"$label\"))\n")
    }

    // Add transitions
    fsm.transitionMeta.foreach { meta =>
      val fromName  = fsm.stateEnum.nameFor(meta.fromStateOrdinal)
      val eventName = fsm.eventEnum.nameFor(meta.eventOrdinal)

      meta.targetStateOrdinal match
        case Some(targetOrdinal) =>
          val targetName = fsm.stateEnum.nameFor(targetOrdinal)
          sb.append(s"        $fromName -->|$eventName| $targetName\n")
        case None =>
          sb.append(s"        $fromName -->|$eventName| $fromName\n")
    }
    sb.append("    end\n\n")

    // Create subgraph for commands with icons
    val allCommandTypes = stateCommands.values.flatten.toList.distinct.sorted
    if allCommandTypes.nonEmpty then
      sb.append("    subgraph Commands[\"⚡ Commands Triggered\"]\n")
      sb.append("        direction LR\n")
      allCommandTypes.foreach { cmdType =>
        val safeName = cmdType.replaceAll("[^a-zA-Z0-9]", "_")
        val icon     = cmdType.toLowerCase match
          case n if n.contains("payment") => "💳"
          case n if n.contains("ship")    => "🚚"
          case n if n.contains("notif")   => "📧"
          case _                          => "📋"
        sb.append(s"        $safeName[\"$icon $cmdType\"]\n")
      }
      sb.append("    end\n\n")

      // Connect states to their commands
      stateCommands.foreach { case (stateOrdinal, cmdTypes) =>
        val stateName = fsm.stateEnum.nameFor(stateOrdinal)
        cmdTypes.foreach { cmdType =>
          val safeName = cmdType.replaceAll("[^a-zA-Z0-9]", "_")
          sb.append(s"    $stateName -.->|on entry| $safeName\n")
        }
      }
    end if

    // Add styling
    sb.append("\n")
    // Style for terminal/error states
    allStates.foreach { stateOrdinal =>
      val stateName = fsm.stateEnum.nameFor(stateOrdinal)
      stateName.toLowerCase match
        case n if n.contains("cancel") || n.contains("fail") =>
          sb.append(s"    style $stateName fill:#FFB6C1,stroke:#DC143C,stroke-width:2px\n")
        case n if n.contains("complete") || n.contains("deliver") || n.contains("done") =>
          sb.append(s"    style $stateName fill:#98FB98,stroke:#228B22,stroke-width:2px\n")
        case _ => // default styling
    }

    // Style command nodes
    allCommandTypes.foreach { cmdType =>
      val safeName = cmdType.replaceAll("[^a-zA-Z0-9]", "_")
      cmdType.toLowerCase match
        case n if n.contains("payment") =>
          sb.append(s"    style $safeName fill:#FFD700,stroke:#DAA520,stroke-width:2px\n")
        case n if n.contains("ship") =>
          sb.append(s"    style $safeName fill:#87CEEB,stroke:#4682B4,stroke-width:2px\n")
        case n if n.contains("notif") =>
          sb.append(s"    style $safeName fill:#DDA0DD,stroke:#9932CC,stroke-width:2px\n")
        case _ =>
          sb.append(s"    style $safeName fill:#F0E68C,stroke:#BDB76B,stroke-width:2px\n")
    }

    // Highlight current state and visited path if trace provided
    trace.foreach { t =>
      sb.append("\n")
      t.visitedStates.foreach { state =>
        val stateName = fsm.stateEnum.nameOf(state)
        sb.append(s"    style $stateName fill:#ADD8E6,stroke:#4169E1,stroke-width:3px\n")
      }
      val currentName = fsm.stateEnum.nameOf(t.currentState)
      sb.append(s"    style $currentName fill:#90EE90,stroke:#228B22,stroke-width:4px\n")
    }

    sb.toString
  end flowchartWithCommands

end MermaidVisualizer
