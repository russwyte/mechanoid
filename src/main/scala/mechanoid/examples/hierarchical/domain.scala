package mechanoid.examples.hierarchical

import java.time.Instant

/** Domain types for the Document Workflow example. */

/** A document being processed through the workflow. */
case class Document(
    id: String,
    title: String,
    content: String,
    author: Author,
    createdAt: Instant,
)

/** The author of a document. */
case class Author(
    id: String,
    name: String,
    email: String,
)

/** A review comment on a document. */
case class ReviewComment(
    reviewer: String,
    comment: String,
    timestamp: Instant,
)

/** Approval decision. */
enum ApprovalDecision:
  case Approved(approver: String, comments: Option[String])
  case Rejected(approver: String, reason: String)
