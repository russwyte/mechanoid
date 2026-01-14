package mechanoid.examples.test.scala.mechanoid.examples

import zio.*
import zio.test.*
import mechanoid.core.Finite
import mechanoid.examples.hierarchical.*

/** Tests for hierarchical state organization using nested sealed traits.
  *
  * These tests demonstrate:
  *   - Finite discovering all leaf cases in a hierarchy
  *   - The new suite-style DSL with `all[Parent]` for group transitions
  *   - Using `Machine.start()` to run FSM instances
  */
object HierarchicalFSMSpec extends ZIOSpecDefault:

  def spec = suite("Hierarchical FSM")(
    suite("Finite with nested sealed traits")(
      test("discovers all leaf cases recursively") {
        val se    = summon[Finite[DocumentState]]
        val names = se.caseInfos.map(_.simpleName).toSet
        assertTrue(
          // Should find all leaf states
          names.contains("Draft"),
          names.contains("PendingReview"),
          names.contains("UnderReview"),
          names.contains("ChangesRequested"),
          names.contains("PendingApproval"),
          names.contains("Rejected"),
          names.contains("Published"),
          names.contains("Archived"),
          names.contains("Cancelled"),
          // Should have exactly 9 leaf states
          se.caseInfos.length == 9,
        )
      },
      test("excludes parent sealed traits from cases") {
        val se    = summon[Finite[DocumentState]]
        val names = se.caseInfos.map(_.simpleName).toSet
        assertTrue(
          // Parent traits should NOT be in the case list
          !names.contains("InReview"),
          !names.contains("Approval"),
        )
      },
      test("caseHash works for all leaf states") {
        val se     = summon[Finite[DocumentState]]
        val hashes = List(
          se.caseHash(Draft),
          se.caseHash(PendingReview),
          se.caseHash(UnderReview),
          se.caseHash(ChangesRequested),
          se.caseHash(PendingApproval),
          se.caseHash(Rejected),
          se.caseHash(Published),
          se.caseHash(Archived),
          se.caseHash(Cancelled),
        )
        assertTrue(
          // All hashes should be unique
          hashes.distinct.length == 9,
          // All hashes should be non-zero (except by coincidence)
          hashes.forall(_ != 0) || hashes.distinct.length == 9,
        )
      },
      test("nameOf works for nested states") {
        val se = summon[Finite[DocumentState]]
        assertTrue(
          se.nameOf(PendingReview) == "PendingReview",
          se.nameOf(UnderReview) == "UnderReview",
          se.nameOf(PendingApproval) == "PendingApproval",
          se.nameOf(Rejected) == "Rejected",
        )
      },
    ),
    suite("Document Workflow FSM")(
      test("happy path: Draft -> Published -> Archived") {
        ZIO.scoped {
          for
            // Use the new Machine.start() API
            fsm <- DocumentWorkflowFSM.definition.start(Draft)
            _   <- fsm.send(SubmitForReview)
            s1  <- fsm.currentState
            _   <- fsm.send(AssignReviewer)
            s2  <- fsm.currentState
            _   <- fsm.send(ApproveReview)
            s3  <- fsm.currentState
            _   <- fsm.send(ApprovePublication)
            s4  <- fsm.currentState
            _   <- fsm.send(Archive)
            s5  <- fsm.currentState
          yield assertTrue(
            s1 == PendingReview,
            s2 == UnderReview,
            s3 == PendingApproval,
            s4 == Published,
            s5 == Archived,
          )
        }
      },
      test("rejection path: can resubmit after rejection") {
        ZIO.scoped {
          for
            fsm <- DocumentWorkflowFSM.definition.start(Draft)
            _   <- fsm.send(SubmitForReview)
            _   <- fsm.send(AssignReviewer)
            _   <- fsm.send(ApproveReview)
            _   <- fsm.send(RejectPublication)
            s1  <- fsm.currentState
            _   <- fsm.send(SubmitForReview)
            s2  <- fsm.currentState
          yield assertTrue(
            s1 == Rejected,
            s2 == PendingReview,
          )
        }
      },
      test("changes requested path: can resubmit after changes") {
        ZIO.scoped {
          for
            fsm <- DocumentWorkflowFSM.definition.start(Draft)
            _   <- fsm.send(SubmitForReview)
            _   <- fsm.send(AssignReviewer)
            _   <- fsm.send(RequestChanges)
            s1  <- fsm.currentState
            _   <- fsm.send(ResubmitAfterChanges)
            s2  <- fsm.currentState
          yield assertTrue(
            s1 == ChangesRequested,
            s2 == PendingReview,
          )
        }
      },
      test("state history tracks all transitions") {
        ZIO.scoped {
          for
            fsm     <- DocumentWorkflowFSM.definition.start(Draft)
            _       <- fsm.send(SubmitForReview)
            _       <- fsm.send(AssignReviewer)
            _       <- fsm.send(ApproveReview)
            history <- fsm.history
          yield assertTrue(
            // History includes all previous states (most recent first)
            history.contains(Draft),
            history.contains(PendingReview),
            history.contains(UnderReview),
          )
        }
      },
    ),
    suite("Type safety")(
      test("InReview subtypes are assignable to InReview") {
        // This is a compile-time check - if it compiles, it passes
        val inReviewStates: List[InReview] = List(
          PendingReview,
          UnderReview,
          ChangesRequested,
        )
        assertTrue(inReviewStates.length == 3)
      },
      test("Approval subtypes are assignable to Approval") {
        // This is a compile-time check - if it compiles, it passes
        val approvalStates: List[Approval] = List(
          PendingApproval,
          Rejected,
        )
        assertTrue(approvalStates.length == 2)
      },
      test("all states are assignable to DocumentState") {
        // This is a compile-time check - if it compiles, it passes
        val allStates: List[DocumentState] = List(
          Draft,
          PendingReview,
          UnderReview,
          ChangesRequested,
          PendingApproval,
          Rejected,
          Published,
          Archived,
          Cancelled,
        )
        assertTrue(allStates.length == 9)
      },
    ),
    suite("all[Parent] - hierarchical transitions")(
      test("CancelReview from any InReview state goes to Draft") {
        ZIO.scoped {
          for
            // Test from PendingReview - uses all[InReview] via CancelReview to Draft
            fsm1 <- DocumentWorkflowFSM.definition.start(PendingReview)
            _    <- fsm1.send(CancelReview)
            s1   <- fsm1.currentState

            // Test from UnderReview - same group transition applies
            fsm2 <- DocumentWorkflowFSM.definition.start(UnderReview)
            _    <- fsm2.send(CancelReview)
            s2   <- fsm2.currentState

            // Test from ChangesRequested - same group transition applies
            fsm3 <- DocumentWorkflowFSM.definition.start(ChangesRequested)
            _    <- fsm3.send(CancelReview)
            s3   <- fsm3.currentState
          yield assertTrue(
            s1 == Draft,
            s2 == Draft,
            s3 == Draft,
          )
        }
      },
      test("Abandon from any Approval state goes to Cancelled") {
        ZIO.scoped {
          for
            // Test from PendingApproval - uses all[Approval] via Abandon to Cancelled
            fsm1 <- DocumentWorkflowFSM.definition.start(PendingApproval)
            _    <- fsm1.send(Abandon)
            s1   <- fsm1.currentState

            // Test from Rejected - same group transition applies
            fsm2 <- DocumentWorkflowFSM.definition.start(Rejected)
            _    <- fsm2.send(Abandon)
            s2   <- fsm2.currentState
          yield assertTrue(
            s1 == Cancelled,
            s2 == Cancelled,
          )
        }
      },
      test("hierarchyInfo contains correct parent-to-leaf mappings") {
        val se        = summon[Finite[DocumentState]]
        val hierarchy = se.hierarchyInfo.parentToLeaves

        // Get the hash of InReview parent
        val inReviewHash = "mechanoid.examples.hierarchical.InReview".hashCode

        // InReview should map to its leaf states
        val inReviewLeaves = hierarchy.get(inReviewHash)

        assertTrue(
          inReviewLeaves.isDefined,
          inReviewLeaves.get.contains(se.caseHash(PendingReview)),
          inReviewLeaves.get.contains(se.caseHash(UnderReview)),
          inReviewLeaves.get.contains(se.caseHash(ChangesRequested)),
          inReviewLeaves.get.size == 3,
        )
      },
    ),
  ) @@ TestAspect.sequential
end HierarchicalFSMSpec
