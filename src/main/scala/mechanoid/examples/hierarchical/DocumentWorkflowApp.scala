package mechanoid.examples.hierarchical

import zio.*
import mechanoid.*
import mechanoid.runtime.FSMRuntime
import mechanoid.visualization.toMermaidStateDiagram

/** Demonstrates hierarchical state organization in Mechanoid FSMs.
  *
  * This example shows how to:
  *   1. Organize related states using nested sealed traits
  *   2. Define transitions for leaf states only
  *   3. Use the hierarchy for code organization without affecting runtime behavior
  *
  * Run with: `sbt "runMain mechanoid.examples.hierarchical.DocumentWorkflowApp"`
  */
object DocumentWorkflowApp extends ZIOAppDefault:

  override def run: ZIO[Any, Any, Unit] =
    for
      _ <- Console.printLine("=== Document Workflow Demo (Hierarchical States) ===")
      _ <- Console.printLine("")

      // Show the state hierarchy
      _ <- Console.printLine("State Hierarchy:")
      _ <- Console.printLine("  DocumentState")
      _ <- Console.printLine("  ├── Draft (initial)")
      _ <- Console.printLine("  ├── InReview (parent)")
      _ <- Console.printLine("  │   ├── PendingReview")
      _ <- Console.printLine("  │   ├── UnderReview")
      _ <- Console.printLine("  │   └── ChangesRequested")
      _ <- Console.printLine("  ├── Approval (parent)")
      _ <- Console.printLine("  │   ├── PendingApproval")
      _ <- Console.printLine("  │   └── Rejected")
      _ <- Console.printLine("  ├── Published (final)")
      _ <- Console.printLine("  └── Archived (final)")
      _ <- Console.printLine("")

      // Generate and display the state diagram
      _ <- Console.printLine("Mermaid State Diagram:")
      _ <- Console.printLine("```mermaid")
      _ <- Console.printLine(DocumentWorkflowFSM.definition.toMermaidStateDiagram)
      _ <- Console.printLine("```")
      _ <- Console.printLine("")

      // Run through a happy path workflow
      _ <- Console.printLine("Running Happy Path Workflow:")
      _ <- Console.printLine("-" * 40)

      result <- ZIO.scoped {
        for
          fsm <- FSMRuntime.make(DocumentWorkflowFSM.definition, Draft)

          // Draft -> PendingReview
          _  <- Console.printLine(s"Initial: Draft")
          _  <- fsm.send(SubmitForReview)
          s1 <- fsm.currentState
          _  <- Console.printLine(s"After SubmitForReview: $s1")

          // PendingReview -> UnderReview
          _  <- fsm.send(AssignReviewer)
          s2 <- fsm.currentState
          _  <- Console.printLine(s"After AssignReviewer: $s2")

          // UnderReview -> PendingApproval
          _  <- fsm.send(ApproveReview)
          s3 <- fsm.currentState
          _  <- Console.printLine(s"After ApproveReview: $s3")

          // PendingApproval -> Published
          _  <- fsm.send(ApprovePublication)
          s4 <- fsm.currentState
          _  <- Console.printLine(s"After ApprovePublication: $s4")

          // Published -> Archived
          _  <- fsm.send(Archive)
          s5 <- fsm.currentState
          _  <- Console.printLine(s"After Archive: $s5")

          history <- fsm.history
        yield history
      }

      _ <- Console.printLine("")
      _ <- Console.printLine("Full state history (most recent first):")
      _ <- ZIO.foreach(result)(s => Console.printLine(s"  - $s"))

      _ <- Console.printLine("")
      _ <- Console.printLine("=== Rejection Path Demo ===")
      _ <- Console.printLine("-" * 40)

      _ <- ZIO.scoped {
        for
          fsm <- FSMRuntime.make(DocumentWorkflowFSM.definition, Draft)

          _  <- Console.printLine(s"Initial: Draft")
          _  <- fsm.send(SubmitForReview)
          _  <- fsm.send(AssignReviewer)
          _  <- fsm.send(ApproveReview)
          s1 <- fsm.currentState
          _  <- Console.printLine(s"After review approval: $s1")

          // Reject!
          _  <- fsm.send(RejectPublication)
          s2 <- fsm.currentState
          _  <- Console.printLine(s"After RejectPublication: $s2")

          // Resubmit for review
          _  <- fsm.send(SubmitForReview)
          s3 <- fsm.currentState
          _  <- Console.printLine(s"After SubmitForReview (resubmit): $s3")
        yield ()
      }

      _ <- Console.printLine("")
      _ <- Console.printLine("=== Changes Requested Path Demo ===")
      _ <- Console.printLine("-" * 40)

      _ <- ZIO.scoped {
        for
          fsm <- FSMRuntime.make(DocumentWorkflowFSM.definition, Draft)

          _  <- Console.printLine(s"Initial: Draft")
          _  <- fsm.send(SubmitForReview)
          _  <- fsm.send(AssignReviewer)
          s1 <- fsm.currentState
          _  <- Console.printLine(s"Under review: $s1")

          // Request changes
          _  <- fsm.send(RequestChanges)
          s2 <- fsm.currentState
          _  <- Console.printLine(s"After RequestChanges: $s2")

          // Resubmit after making changes
          _  <- fsm.send(ResubmitAfterChanges)
          s3 <- fsm.currentState
          _  <- Console.printLine(s"After ResubmitAfterChanges: $s3")
        yield ()
      }

      _ <- Console.printLine("")
      _ <- Console.printLine("Demo complete!")
    yield ()
end DocumentWorkflowApp
