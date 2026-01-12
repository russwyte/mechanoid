package mechanoid.examples.hierarchical

import zio.*
import zio.json.*
import mechanoid.*

// ============================================
// Document Workflow - Hierarchical States
// ============================================

/** Document lifecycle states using hierarchical organization.
  *
  * This demonstrates how to organize related states using nested sealed traits. The hierarchy enables:
  *   - Clear visual grouping of related states
  *   - Type-safe state categories (e.g., `InReview` type for all review states)
  *   - Using `whenAny[ParentState]` to define transitions for all states in a group
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
  *   ├── Archived                 (leaf - final state)
  *   └── Cancelled                (leaf - cancelled state)
  * }}}
  *
  * The `whenAny[T]` method allows defining transitions that apply to all leaf states under a parent:
  * {{{
  * // Cancel from any InReview state goes back to Draft
  * .whenAny[InReview].on(CancelReview).goto(Draft)
  *
  * // Abandon from any Approval state also goes to Cancelled
  * .whenAny[Approval].on(Abandon).goto(Cancelled)
  * }}}
  *
  * Leaf-level transitions can override parent-level ones when defined after `whenAny`.
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

/** Document cancelled - workflow terminated. */
case object Cancelled extends DocumentState

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
case object CancelReview         extends DocumentEvent // Cancel from any review state
case object Abandon              extends DocumentEvent // Abandon from any approval state

// ============================================
// Document Workflow FSM Definition
// ============================================

object DocumentWorkflowFSM:

  /** Create the document workflow FSM definition.
    *
    * This definition demonstrates both individual state transitions and `whenAny[T]` for parent-level transitions.
    *
    * Individual transitions are defined for specific leaf states, while `whenAny[T]` creates transitions for all leaf
    * states under a parent sealed trait. This is useful for:
    *   - Cancel/abort actions that apply to multiple related states
    *   - Default behaviors that can be overridden by specific states
    *
    * Note: `whenAny` transitions can be overridden by leaf-level transitions defined after them.
    */
  val definition: FSMDefinition[DocumentState, DocumentEvent, Nothing] =
    fsm[DocumentState, DocumentEvent]
      // ---- Draft transitions ----
      .when(Draft)
      .on(SubmitForReview)
      .goto(PendingReview)

      // ---- whenAny[InReview]: applies to PendingReview, UnderReview, ChangesRequested ----
      // Any state in the review phase can be cancelled, returning to Draft
      .whenAny[InReview]
      .on(CancelReview)
      .goto(Draft)

      // ---- Review Phase transitions (individual leaf states) ----
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

      // ---- whenAny[Approval]: applies to PendingApproval, Rejected ----
      // Any state in the approval phase can be abandoned, going to Cancelled
      .whenAny[Approval]
      .on(Abandon)
      .goto(Cancelled)

      // ---- Approval Phase transitions (individual leaf states) ----
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
