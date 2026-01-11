package mechanoid.examples.hierarchical

import zio.*
import zio.json.*
import mechanoid.*

// ============================================
// Document Workflow - Hierarchical States
// ============================================

/** Document lifecycle states using hierarchical organization.
  *
  * This demonstrates how to organize related states using nested sealed traits. The hierarchy is purely for code
  * organization - at runtime, only leaf states participate in FSM transitions.
  *
  * Hierarchy:
  * {{{
  *   DocumentState
  *   ├── Draft                    (leaf - initial state)
  *   ├── InReview                 (parent - groups review states)
  *   │   ├── PendingReview        (leaf)
  *   │   ├── UnderReview          (leaf)
  *   │   └── ChangesRequested     (leaf)
  *   ├── Approval                 (parent - groups approval states)
  *   │   ├── PendingApproval      (leaf)
  *   │   └── Rejected             (leaf)
  *   ├── Published                (leaf - final state)
  *   └── Archived                 (leaf - final state)
  * }}}
  *
  * Benefits of hierarchical organization:
  *   - Clear visual grouping of related states
  *   - Type-safe state categories (e.g., `InReview` type for all review states)
  *   - IDE navigation: jump to parent to see all related states
  *   - Documentation: hierarchy serves as visual documentation
  *
  * Important: Transitions are defined only for leaf states. Parent states (InReview, Approval) cannot be used in
  * `.when()` directly because they're abstract - you can only have instances of their subtypes.
  */
sealed trait DocumentState extends MState derives JsonCodec

// Initial state - document being drafted
case object Draft extends DocumentState

// ---- Review Phase States (grouped under InReview) ----
sealed trait InReview extends DocumentState

/** Waiting to be picked up by a reviewer. */
case object PendingReview extends InReview

/** Currently being reviewed. */
case object UnderReview extends InReview

/** Reviewer requested changes - needs revision. */
case object ChangesRequested extends InReview

// ---- Approval Phase States (grouped under Approval) ----
sealed trait Approval extends DocumentState

/** Waiting for final approval. */
case object PendingApproval extends Approval

/** Rejected by approver - needs to go back to review. */
case object Rejected extends Approval

// ---- Final States ----
/** Document published and visible. */
case object Published extends DocumentState

/** Document archived (no longer active). */
case object Archived extends DocumentState

// ============================================
// Document Workflow Events
// ============================================

/** Events that drive document workflow transitions. */
sealed trait DocumentEvent extends MEvent derives JsonCodec

case object SubmitForReview      extends DocumentEvent
case object AssignReviewer       extends DocumentEvent
case object RequestChanges       extends DocumentEvent
case object ResubmitAfterChanges extends DocumentEvent
case object ApproveReview        extends DocumentEvent
case object ApprovePublication   extends DocumentEvent
case object RejectPublication    extends DocumentEvent
case object Publish              extends DocumentEvent
case object Archive              extends DocumentEvent

// ============================================
// Document Workflow FSM Definition
// ============================================

object DocumentWorkflowFSM:

  /** Create the document workflow FSM definition.
    *
    * Note how transitions are defined for leaf states only:
    *   - Draft (leaf at root level)
    *   - PendingReview, UnderReview, ChangesRequested (leaves under InReview)
    *   - PendingApproval, Rejected (leaves under Approval)
    *   - Published, Archived (leaves at root level)
    *
    * Parent states (InReview, Approval) are abstract sealed traits and cannot be instantiated - this means you can only
    * use their concrete subtypes in `.when()`, which is enforced by Scala's type system.
    */
  val definition: FSMDefinition[DocumentState, DocumentEvent, Nothing] =
    fsm[DocumentState, DocumentEvent]
      // ---- Draft transitions ----
      .when(Draft)
      .on(SubmitForReview)
      .goto(PendingReview)

      // ---- Review Phase transitions (all leaf states under InReview) ----
      // PendingReview: waiting for reviewer assignment
      .when(PendingReview)
      .on(AssignReviewer)
      .goto(UnderReview)

      // UnderReview: reviewer working on it
      .when(UnderReview)
      .on(RequestChanges)
      .goto(ChangesRequested)
      .when(UnderReview)
      .on(ApproveReview)
      .goto(PendingApproval)

      // ChangesRequested: author making revisions
      .when(ChangesRequested)
      .on(ResubmitAfterChanges)
      .goto(PendingReview)

      // ---- Approval Phase transitions (all leaf states under Approval) ----
      // PendingApproval: waiting for final sign-off
      .when(PendingApproval)
      .on(ApprovePublication)
      .goto(Published)
      .when(PendingApproval)
      .on(RejectPublication)
      .goto(Rejected)

      // Rejected: can resubmit for review
      .when(Rejected)
      .on(SubmitForReview)
      .goto(PendingReview)

      // ---- Final State transitions ----
      // Published can be archived
      .when(Published)
      .on(Archive)
      .goto(Archived)
  end definition
end DocumentWorkflowFSM
