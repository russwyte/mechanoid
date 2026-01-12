package mechanoid.persistence.postgres

import saferis.*
import zio.*

// Row models for information_schema queries
@tableName("tables")
private[postgres] final case class TableInfo(
    @label("table_name") tableName: String
) derives Table

@tableName("columns")
private[postgres] final case class ColumnInfo(
    @label("column_name") columnName: String,
    @label("data_type") dataType: String,
    @label("is_nullable") isNullable: String,
) derives Table

@tableName("key_column_usage")
private[postgres] final case class PrimaryKeyInfo(
    @label("column_name") columnName: String
) derives Table

/** PostgreSQL schema initializer for Mechanoid.
  *
  * Provides utilities to create and verify the database schema required by Mechanoid's PostgreSQL persistence layer.
  * Uses Saferis DDL for table creation with custom SQL for constraints that Saferis doesn't yet support.
  *
  * ==Usage==
  * {{{
  * // Initialize schema (creates if missing, verifies if exists)
  * PostgresSchema.initialize.provide(transactorLayer)
  * }}}
  */
object PostgresSchema:

  /** Result of schema initialization. */
  enum InitResult:
    /** Tables were created using Saferis DDL. */
    case Created

    /** Tables already existed and passed verification. */
    case Verified

  /** List of tables managed by Mechanoid. */
  val ManagedTables: List[String] = List(
    "fsm_events",
    "fsm_snapshots",
    "scheduled_timeouts",
    "fsm_instance_locks",
    "leases",
    "commands",
  )

  /** Expected schema definition for verification.
    *
    * Maps table names to their expected columns with (name, pgType, nullable).
    */
  private val ExpectedSchema: Map[String, TableDefinition] = Map(
    "fsm_events" -> TableDefinition(
      columns = List(
        ColumnDef("id", "bigint", nullable = false),
        ColumnDef("instance_id", "text", nullable = false),
        ColumnDef("sequence_nr", "bigint", nullable = false),
        ColumnDef("event_data", "jsonb", nullable = false),
        ColumnDef("created_at", "timestamp with time zone", nullable = false),
      ),
      primaryKey = List("id"),
    ),
    "fsm_snapshots" -> TableDefinition(
      columns = List(
        ColumnDef("instance_id", "text", nullable = false),
        ColumnDef("state_data", "jsonb", nullable = false),
        ColumnDef("sequence_nr", "bigint", nullable = false),
        ColumnDef("created_at", "timestamp with time zone", nullable = false),
      ),
      primaryKey = List("instance_id"),
    ),
    "scheduled_timeouts" -> TableDefinition(
      columns = List(
        ColumnDef("instance_id", "text", nullable = false),
        ColumnDef("state", "text", nullable = false),
        ColumnDef("deadline", "timestamp with time zone", nullable = false),
        ColumnDef("created_at", "timestamp with time zone", nullable = false),
        ColumnDef("claimed_by", "text", nullable = true),
        ColumnDef("claimed_until", "timestamp with time zone", nullable = true),
      ),
      primaryKey = List("instance_id"),
    ),
    "fsm_instance_locks" -> TableDefinition(
      columns = List(
        ColumnDef("instance_id", "text", nullable = false),
        ColumnDef("node_id", "text", nullable = false),
        ColumnDef("acquired_at", "timestamp with time zone", nullable = false),
        ColumnDef("expires_at", "timestamp with time zone", nullable = false),
      ),
      primaryKey = List("instance_id"),
    ),
    "leases" -> TableDefinition(
      columns = List(
        ColumnDef("key", "text", nullable = false),
        ColumnDef("holder", "text", nullable = false),
        ColumnDef("expires_at", "timestamp with time zone", nullable = false),
        ColumnDef("acquired_at", "timestamp with time zone", nullable = false),
      ),
      primaryKey = List("key"),
    ),
    "commands" -> TableDefinition(
      columns = List(
        ColumnDef("id", "bigint", nullable = false),
        ColumnDef("instance_id", "text", nullable = false),
        ColumnDef("command_data", "jsonb", nullable = false),
        ColumnDef("idempotency_key", "text", nullable = false),
        ColumnDef("enqueued_at", "timestamp with time zone", nullable = false),
        ColumnDef("status", "text", nullable = false),
        ColumnDef("attempts", "integer", nullable = false),
        ColumnDef("last_attempt_at", "timestamp with time zone", nullable = true),
        ColumnDef("last_error", "text", nullable = true),
        ColumnDef("next_retry_at", "timestamp with time zone", nullable = true),
        ColumnDef("claimed_by", "text", nullable = true),
        ColumnDef("claimed_until", "timestamp with time zone", nullable = true),
      ),
      primaryKey = List("id"),
    ),
  )

  /** Internal model for expected table structure. */
  private case class TableDefinition(
      columns: List[ColumnDef],
      primaryKey: List[String],
  )

  /** Internal model for expected column structure. */
  private case class ColumnDef(
      name: String,
      pgType: String,
      nullable: Boolean,
  )

  /** Initialize the schema: creates tables if they don't exist, verifies if they do.
    *
    * @return
    *   `InitResult.Created` if tables were created, `InitResult.Verified` if existing tables passed validation
    */
  def initialize: ZIO[Transactor, SchemaResourceError | SchemaValidationError, InitResult] =
    for
      xa             <- ZIO.service[Transactor]
      existingTables <- getExistingTables(xa).mapError(SchemaResourceError(_))
      result         <-
        if existingTables.isEmpty then createSchema(xa).as(InitResult.Created)
        else verifySchema(xa).as(InitResult.Verified)
    yield result

  /** Create the schema if tables don't exist.
    *
    * @return
    *   true if tables were created, false if they already existed
    */
  def createIfNotExists: ZIO[Transactor, SchemaResourceError, Boolean] =
    for
      xa             <- ZIO.service[Transactor]
      existingTables <- getExistingTables(xa).mapError(SchemaResourceError(_))
      created        <-
        if existingTables.isEmpty then createSchema(xa).as(true)
        else ZIO.succeed(false)
    yield created

  /** Verify the existing schema matches expectations.
    *
    * Does not create tables - only validates existing structure.
    */
  def verify: ZIO[Transactor, SchemaValidationError, Unit] =
    ZIO.serviceWithZIO[Transactor](verifySchema)

  // ==================== Private Implementation ====================

  private def getExistingTables(xa: Transactor): ZIO[Any, Throwable, Set[String]] =
    val managedSet = ManagedTables.toSet
    xa.run {
      sql"""
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_type = 'BASE TABLE'
      """.query[TableInfo]
    }.map(_.map(_.tableName).filter(managedSet.contains).toSet)
  end getExistingTables

  /** Create all tables using SQL. Until Saferis DDL supports NOT NULL and JSONB properly, we use raw SQL. */
  private def createSchema(xa: Transactor): ZIO[Any, SchemaResourceError, Unit] =
    (for
      _ <- createTables(xa)
      _ <- createIndexes(xa)
    yield ()).mapError(SchemaResourceError(_))

  /** Create all tables with proper types and constraints. */
  private def createTables(xa: Transactor): ZIO[Any, Throwable, Unit] =
    for
      _ <- xa.run(sql"""CREATE TABLE fsm_events (
             id BIGSERIAL PRIMARY KEY,
             instance_id TEXT NOT NULL,
             sequence_nr BIGINT NOT NULL,
             event_data JSONB NOT NULL,
             created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
             UNIQUE (instance_id, sequence_nr)
           )""".dml)

      _ <- xa.run(sql"""CREATE TABLE fsm_snapshots (
             instance_id TEXT PRIMARY KEY,
             state_data JSONB NOT NULL,
             sequence_nr BIGINT NOT NULL,
             created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
           )""".dml)

      _ <- xa.run(sql"""CREATE TABLE scheduled_timeouts (
             instance_id TEXT PRIMARY KEY,
             state TEXT NOT NULL,
             deadline TIMESTAMPTZ NOT NULL,
             created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
             claimed_by TEXT,
             claimed_until TIMESTAMPTZ
           )""".dml)

      _ <- xa.run(sql"""CREATE TABLE fsm_instance_locks (
             instance_id TEXT PRIMARY KEY,
             node_id TEXT NOT NULL,
             acquired_at TIMESTAMPTZ NOT NULL,
             expires_at TIMESTAMPTZ NOT NULL
           )""".dml)

      _ <- xa.run(sql"""CREATE TABLE leases (
             key TEXT PRIMARY KEY,
             holder TEXT NOT NULL,
             expires_at TIMESTAMPTZ NOT NULL,
             acquired_at TIMESTAMPTZ NOT NULL
           )""".dml)

      _ <- xa.run(sql"""CREATE TABLE commands (
             id BIGSERIAL PRIMARY KEY,
             instance_id TEXT NOT NULL,
             command_data JSONB NOT NULL,
             idempotency_key TEXT NOT NULL UNIQUE,
             enqueued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
             status TEXT NOT NULL DEFAULT 'pending',
             attempts INT NOT NULL DEFAULT 0,
             last_attempt_at TIMESTAMPTZ,
             last_error TEXT,
             next_retry_at TIMESTAMPTZ,
             claimed_by TEXT,
             claimed_until TIMESTAMPTZ
           )""".dml)
    yield ()

  /** Create all indexes. */
  private def createIndexes(xa: Transactor): ZIO[Any, Throwable, Unit] =
    for
      // fsm_events: compound index on (instance_id, sequence_nr)
      _ <- xa.run(sql"CREATE INDEX idx_fsm_events_instance ON fsm_events (instance_id, sequence_nr)".dml)

      // scheduled_timeouts: index on deadline for timeout sweeper queries
      _ <- xa.run(sql"CREATE INDEX idx_timeouts_deadline ON scheduled_timeouts (deadline)".dml)

      // fsm_instance_locks: index on expires_at for lock cleanup
      _ <- xa.run(sql"CREATE INDEX idx_locks_expired ON fsm_instance_locks (expires_at)".dml)

      // leases: index on expires_at for lease expiry queries
      _ <- xa.run(sql"CREATE INDEX idx_leases_expires ON leases (expires_at)".dml)

      // commands: partial index for pending commands
      _ <- xa.run(sql"CREATE INDEX idx_commands_pending ON commands (next_retry_at) WHERE status = 'pending'".dml)

      // commands: index on instance_id for lookups
      _ <- xa.run(sql"CREATE INDEX idx_commands_instance ON commands (instance_id)".dml)

      // commands: index on status for filtering
      _ <- xa.run(sql"CREATE INDEX idx_commands_status ON commands (status)".dml)
    yield ()

  private def verifySchema(xa: Transactor): ZIO[Any, SchemaValidationError, Unit] =
    for
      issues <- collectSchemaIssues(xa)
      _      <- ZIO.when(issues.nonEmpty)(ZIO.fail(SchemaValidationError(issues)))
    yield ()

  private def collectSchemaIssues(xa: Transactor): ZIO[Any, SchemaValidationError, List[SchemaIssue]] =
    (for
      existingTables <- getExistingTables(xa)
      missingTables = ManagedTables.filterNot(existingTables.contains).map(SchemaIssue.MissingTable.apply)

      columnIssues <- ZIO.foreach(existingTables.toList)(table =>
        verifyTableColumns(xa, table, ExpectedSchema.get(table))
      )

      pkIssues <- ZIO.foreach(existingTables.toList)(table => verifyPrimaryKey(xa, table, ExpectedSchema.get(table)))
    yield missingTables ++ columnIssues.flatten ++ pkIssues.flatten)
      .mapError(e => SchemaValidationError(List(SchemaIssue.MissingTable(s"Error querying schema: ${e.getMessage}"))))

  private def verifyTableColumns(
      xa: Transactor,
      tableName: String,
      expected: Option[TableDefinition],
  ): ZIO[Any, Throwable, List[SchemaIssue]] =
    expected match
      case None           => ZIO.succeed(Nil) // Unknown table, skip
      case Some(tableDef) =>
        for
          actualColumns <- xa.run {
            sql"""
              SELECT column_name, data_type, is_nullable
              FROM information_schema.columns
              WHERE table_schema = 'public' AND table_name = $tableName
            """.query[ColumnInfo]
          }
          actualMap = actualColumns.map { col =>
            col.columnName -> (col.dataType, col.isNullable == "YES")
          }.toMap
        yield
          val issues = scala.collection.mutable.ListBuffer[SchemaIssue]()

          // Check for missing columns
          for col <- tableDef.columns do
            actualMap.get(col.name) match
              case None =>
                issues += SchemaIssue.MissingColumn(tableName, col.name)
              case Some((actualType, actualNullable)) =>
                // Check type (normalize for comparison)
                if !typesMatch(col.pgType, actualType) then
                  issues += SchemaIssue.TypeMismatch(tableName, col.name, col.pgType, actualType)
                // Check nullability
                if col.nullable != actualNullable then
                  issues += SchemaIssue.NullabilityMismatch(tableName, col.name, col.nullable, actualNullable)
          end for

          // Check for extra columns (informational, not blocking)
          val expectedNames = tableDef.columns.map(_.name).toSet
          for (colName, _) <- actualMap if !expectedNames.contains(colName) do
            issues += SchemaIssue.ExtraColumn(tableName, colName)

          issues.toList

  private def verifyPrimaryKey(
      xa: Transactor,
      tableName: String,
      expected: Option[TableDefinition],
  ): ZIO[Any, Throwable, List[SchemaIssue]] =
    expected match
      case None           => ZIO.succeed(Nil)
      case Some(tableDef) =>
        for
          actualPkColumns <- xa.run {
            sql"""
              SELECT kcu.column_name
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
                AND tc.table_schema = kcu.table_schema
              WHERE tc.table_schema = 'public'
                AND tc.table_name = $tableName
                AND tc.constraint_type = 'PRIMARY KEY'
              ORDER BY kcu.ordinal_position
            """.query[PrimaryKeyInfo]
          }
          pkColumnNames = actualPkColumns.map(_.columnName).toList
        yield
          if pkColumnNames.toSet != tableDef.primaryKey.toSet then
            List(SchemaIssue.MissingPrimaryKey(tableName, tableDef.primaryKey, pkColumnNames))
          else Nil

  /** Normalize PostgreSQL type names for comparison. */
  private def typesMatch(expected: String, actual: String): Boolean =
    val normalizedExpected = normalizeType(expected)
    val normalizedActual   = normalizeType(actual)
    normalizedExpected == normalizedActual

  private def normalizeType(t: String): String =
    t.toLowerCase match
      case "bigint"                      => "bigint"
      case "int8"                        => "bigint"
      case "integer"                     => "integer"
      case "int4"                        => "integer"
      case "int"                         => "integer"
      case "text"                        => "text"
      case "jsonb"                       => "jsonb"
      case "timestamp with time zone"    => "timestamptz"
      case "timestamptz"                 => "timestamptz"
      case "timestamp without time zone" => "timestamp"
      case other                         => other
end PostgresSchema
