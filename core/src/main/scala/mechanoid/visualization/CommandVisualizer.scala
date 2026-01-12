package mechanoid.visualization

import mechanoid.persistence.command.*
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Generates visualizations for command queue processing.
  *
  * Provides multiple visualization formats:
  *   - Summary tables showing command counts by status
  *   - Per-instance command lists with details
  *   - Mermaid diagrams showing command flow
  */
object CommandVisualizer:

  private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

  /** Configuration for command visualization. */
  case class Config(
      showTimestamps: Boolean = true,
      showAttempts: Boolean = true,
      showErrors: Boolean = true,
      maxCommandsPerInstance: Int = 50,
  )

  object Config:
    val default: Config = Config()

  /** Generate a summary table of command statuses.
    *
    * Output format (Markdown):
    * ```
    * | Status | Count |
    * |--------|-------|
    * | Pending | 2 |
    * | Completed | 15 |
    * ```
    */
  def summaryTable(counts: Map[CommandStatus, Long]): String =
    val sb = StringBuilder()
    sb.append("| Status | Count |\n")
    sb.append("|--------|-------|\n")

    val orderedStatuses = List(
      CommandStatus.Pending,
      CommandStatus.Processing,
      CommandStatus.Completed,
      CommandStatus.Failed,
      CommandStatus.Skipped,
    )

    orderedStatuses.foreach { status =>
      val count = counts.getOrElse(status, 0L)
      val icon  = status match
        case CommandStatus.Pending    => "⏳"
        case CommandStatus.Processing => "⚙️"
        case CommandStatus.Completed  => "✅"
        case CommandStatus.Failed     => "❌"
        case CommandStatus.Skipped    => "⏭️"
      sb.append(s"| $icon $status | $count |\n")
    }

    sb.toString
  end summaryTable

  /** Generate a detailed command list grouped by instance.
    *
    * @param commands
    *   All commands to visualize
    * @param commandName
    *   Function to extract a display name from a command
    * @param config
    *   Visualization configuration
    */
  def commandList[Id, Cmd](
      commands: List[PendingCommand[Id, Cmd]],
      commandName: Cmd => String,
      config: Config = Config.default,
  ): String =
    val sb = StringBuilder()

    val byInstance = commands.groupBy(_.instanceId)
    byInstance.toList.sortBy(_._1.toString).foreach { case (instanceId, cmds) =>
      sb.append(s"### Instance: $instanceId\n\n")
      sb.append("| # | Command | Status | Attempts | Enqueued |\n")
      sb.append("|---|---------|--------|----------|----------|\n")

      cmds.sortBy(_.enqueuedAt).take(config.maxCommandsPerInstance).foreach { cmd =>
        val name       = commandName(cmd.command)
        val statusIcon = statusToIcon(cmd.status)
        val attempts   = if config.showAttempts then cmd.attempts.toString else "-"
        val enqueued   = if config.showTimestamps then formatInstant(cmd.enqueuedAt) else "-"

        sb.append(s"| ${cmd.id} | $name | $statusIcon ${cmd.status} | $attempts | $enqueued |\n")

        // Show error if failed and config allows
        if config.showErrors && cmd.lastError.isDefined && cmd.status == CommandStatus.Failed then
          sb.append(s"|   | ↳ Error: ${cmd.lastError.get.take(60)} |   |   |   |\n")
      }
      sb.append("\n")
    }

    sb.toString
  end commandList

  /** Generate a Mermaid flowchart showing command processing results.
    *
    * Shows command types and their final statuses as a flow diagram.
    */
  def flowchart[Id, Cmd](
      commands: List[PendingCommand[Id, Cmd]],
      commandType: Cmd => String,
  ): String =
    val sb = StringBuilder()
    sb.append("flowchart LR\n")

    // Group commands by type and status
    val byTypeAndStatus = commands.groupBy(c => (commandType(c.command), c.status))

    // Get unique command types
    val types = commands.map(c => commandType(c.command)).distinct.sorted

    // Create nodes for each command type with emoji icons
    types.zipWithIndex.foreach { case (cmdType, idx) =>
      val shortName = cmdType.split('.').last
      val icon      = shortName.toLowerCase match
        case n if n.contains("payment")  => "💳"
        case n if n.contains("ship")     => "🚚"
        case n if n.contains("notif")    => "📧"
        case n if n.contains("callback") => "🔄"
        case n if n.contains("request")  => "📋"
        case _                           => "⚡"
      sb.append(s"    cmd$idx[\"$icon $shortName\"]\n")
    }

    sb.append("\n")

    // Create status nodes
    sb.append("    completed((\"✅ Completed\"))\n")
    sb.append("    failed((\"❌ Failed\"))\n")
    sb.append("    pending((\"⏳ Pending\"))\n")
    sb.append("\n")

    // Style the status nodes
    sb.append("    style completed fill:#90EE90,stroke:#228B22,stroke-width:2px\n")
    sb.append("    style failed fill:#FFB6C1,stroke:#DC143C,stroke-width:2px\n")
    sb.append("    style pending fill:#FFFFE0,stroke:#DAA520,stroke-width:2px\n")
    sb.append("\n")

    // Style command nodes based on type
    types.zipWithIndex.foreach { case (cmdType, idx) =>
      val shortName = cmdType.split('.').last
      shortName.toLowerCase match
        case n if n.contains("payment") =>
          sb.append(s"    style cmd$idx fill:#FFD700,stroke:#DAA520,stroke-width:2px\n")
        case n if n.contains("ship") =>
          sb.append(s"    style cmd$idx fill:#87CEEB,stroke:#4682B4,stroke-width:2px\n")
        case n if n.contains("notif") =>
          sb.append(s"    style cmd$idx fill:#DDA0DD,stroke:#9932CC,stroke-width:2px\n")
        case n if n.contains("callback") =>
          sb.append(s"    style cmd$idx fill:#98FB98,stroke:#228B22,stroke-width:2px\n")
        case _ =>
          sb.append(s"    style cmd$idx fill:#F0E68C,stroke:#BDB76B,stroke-width:2px\n")
      end match
    }
    sb.append("\n")

    // Add edges with counts
    types.zipWithIndex.foreach { case (cmdType, idx) =>
      val completedCount =
        byTypeAndStatus.getOrElse((cmdType, CommandStatus.Completed), Nil).size
      val failedCount =
        byTypeAndStatus.getOrElse((cmdType, CommandStatus.Failed), Nil).size
      val pendingCount =
        byTypeAndStatus.getOrElse((cmdType, CommandStatus.Pending), Nil).size +
          byTypeAndStatus.getOrElse((cmdType, CommandStatus.Processing), Nil).size

      if completedCount > 0 then sb.append(s"    cmd$idx -->|$completedCount| completed\n")
      if failedCount > 0 then sb.append(s"    cmd$idx -->|$failedCount| failed\n")
      if pendingCount > 0 then sb.append(s"    cmd$idx -->|$pendingCount| pending\n")
    }

    sb.toString
  end flowchart

  /** Generate a Mermaid sequence diagram showing command execution flow.
    *
    * Shows the lifecycle of commands: enqueue → claim → complete/fail
    */
  def sequenceDiagram[Id, Cmd](
      commands: List[PendingCommand[Id, Cmd]],
      commandName: Cmd => String,
      instanceName: Id => String,
  ): String =
    val sb = StringBuilder()
    sb.append("sequenceDiagram\n")
    sb.append("    participant Q as CommandQueue\n")
    sb.append("    participant W as Worker\n")

    // Get unique instances
    val instances = commands.map(_.instanceId).distinct
    instances.foreach { id =>
      sb.append(s"    participant I${id.toString.replace("-", "")} as ${instanceName(id)}\n")
    }

    sb.append("\n")

    // Sort commands by enqueued time and show flow
    commands.sortBy(_.enqueuedAt).foreach { cmd =>
      val instanceId = s"I${cmd.instanceId.toString.replace("-", "")}"
      val name       = commandName(cmd.command)

      // Enqueue
      sb.append(s"    $instanceId->>Q: enqueue($name)\n")

      // Processing
      if cmd.attempts > 0 then
        sb.append(s"    Q->>W: claim($name)\n")

        cmd.status match
          case CommandStatus.Completed =>
            sb.append(s"    W->>Q: complete ✅\n")
          case CommandStatus.Failed =>
            val err = cmd.lastError.map(e => s": ${e.take(20)}").getOrElse("")
            sb.append(s"    W->>Q: fail ❌$err\n")
          case CommandStatus.Skipped =>
            sb.append(s"    W->>Q: skip ⏭️\n")
          case _ => // pending/processing - still in flight
            sb.append(s"    Note over W: processing...\n")
        end match
      end if
    }

    sb.toString
  end sequenceDiagram

  /** Generate a Mermaid Gantt chart showing command execution timeline.
    *
    * Shows when commands were enqueued and processed.
    */
  def ganttChart[Id, Cmd](
      commands: List[PendingCommand[Id, Cmd]],
      commandName: Cmd => String,
      title: String = "Command Execution Timeline",
  ): String =
    val sb = StringBuilder()
    sb.append("gantt\n")
    sb.append(s"    title $title\n")
    sb.append("    dateFormat HH:mm:ss\n")
    sb.append("\n")

    // Group by instance
    val byInstance = commands.groupBy(_.instanceId)
    byInstance.toList.sortBy(_._1.toString).foreach { case (instanceId, cmds) =>
      sb.append(s"    section $instanceId\n")

      cmds.sortBy(_.enqueuedAt).foreach { cmd =>
        val name      = commandName(cmd.command).take(20)
        val startTime = formatTime(cmd.enqueuedAt)
        val endTime   = cmd.lastAttemptAt.map(formatTime).getOrElse(startTime)
        val status    = cmd.status match
          case CommandStatus.Completed => "done"
          case CommandStatus.Failed    => "crit"
          case CommandStatus.Skipped   => "done"
          case _                       => "active"

        sb.append(s"    $name :$status, $startTime, $endTime\n")
      }
    }

    sb.toString
  end ganttChart

  /** Generate a complete markdown report of command processing.
    *
    * Combines summary, list, and diagrams into one document.
    */
  def report[Id, Cmd](
      commands: List[PendingCommand[Id, Cmd]],
      counts: Map[CommandStatus, Long],
      commandName: Cmd => String,
      commandType: Cmd => String,
      title: String = "Command Processing Report",
      config: Config = Config.default,
  ): String =
    val sb = StringBuilder()
    sb.append(s"# $title\n\n")

    // Summary
    sb.append("## Summary\n\n")
    sb.append(summaryTable(counts))
    sb.append("\n")

    // Flowchart
    sb.append("## Command Flow\n\n")
    sb.append("```mermaid\n")
    sb.append(flowchart(commands, commandType))
    sb.append("```\n\n")

    // Detailed list
    sb.append("## Commands by Instance\n\n")
    sb.append(commandList(commands, commandName, config))

    sb.toString
  end report

  private def statusToIcon(status: CommandStatus): String = status match
    case CommandStatus.Pending    => "⏳"
    case CommandStatus.Processing => "⚙️"
    case CommandStatus.Completed  => "✅"
    case CommandStatus.Failed     => "❌"
    case CommandStatus.Skipped    => "⏭️"

  private def formatInstant(instant: Instant): String =
    timeFormatter.format(instant.atZone(java.time.ZoneId.systemDefault()))

  private def formatTime(instant: Instant): String =
    DateTimeFormatter.ofPattern("HH:mm:ss").format(instant.atZone(java.time.ZoneId.systemDefault()))

end CommandVisualizer
