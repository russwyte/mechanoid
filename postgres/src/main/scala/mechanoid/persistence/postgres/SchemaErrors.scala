package mechanoid.persistence.postgres

import mechanoid.core.MechanoidError

/** Error indicating the schema resource file could not be loaded.
  *
  * @param message
  *   Description of what went wrong
  * @param cause
  *   The underlying exception, if available
  */
final case class SchemaResourceError(
    message: String,
    cause: Option[Throwable] = None,
) extends Exception(message, cause.orNull)
    with MechanoidError

object SchemaResourceError:
  def apply(cause: Throwable): SchemaResourceError =
    SchemaResourceError(Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName), Some(cause))

/** Error indicating schema validation failed.
  *
  * This occurs when existing database tables don't match the expected schema. The `issues` list contains detailed
  * information about each mismatch found.
  *
  * @param issues
  *   List of specific schema mismatches
  */
final case class SchemaValidationError(issues: List[SchemaIssue])
    extends Exception(
      s"Schema validation failed with ${issues.size} issue(s):\n${issues.map(i => s"  - ${i.message}").mkString("\n")}"
    )
    with MechanoidError

/** Represents a specific schema validation issue.
  *
  * Each variant describes a different type of schema mismatch between the expected schema (from the DDL resource) and
  * the actual database schema.
  */
enum SchemaIssue:
  /** A required table is missing from the database. */
  case MissingTable(name: String)

  /** A required column is missing from a table. */
  case MissingColumn(table: String, column: String)

  /** A column exists but has the wrong data type. */
  case TypeMismatch(table: String, column: String, expected: String, actual: String)

  /** The primary key constraint doesn't match expectations. */
  case MissingPrimaryKey(table: String, expected: List[String], actual: List[String])

  /** An unexpected column exists in the table (may indicate schema drift or newer version). */
  case ExtraColumn(table: String, column: String)

  /** A column's nullability doesn't match expectations. */
  case NullabilityMismatch(table: String, column: String, expectedNullable: Boolean, actualNullable: Boolean)

  /** Human-readable description of the issue. */
  def message: String = this match
    case MissingTable(n)                 => s"Missing table: $n"
    case MissingColumn(t, c)             => s"Missing column '$c' in table '$t'"
    case TypeMismatch(t, c, e, a)        => s"Type mismatch for $t.$c: expected '$e', found '$a'"
    case MissingPrimaryKey(t, e, a)      => s"Primary key mismatch for '$t': expected $e, found $a"
    case ExtraColumn(t, c)               => s"Unexpected column '$c' in table '$t' (may be from newer schema)"
    case NullabilityMismatch(t, c, e, a) =>
      val expected = if e then "nullable" else "not null"
      val actual   = if a then "nullable" else "not null"
      s"Nullability mismatch for $t.$c: expected $expected, found $actual"
end SchemaIssue
