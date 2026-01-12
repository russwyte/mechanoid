# Saferis DDL Wishlist

Features needed in Saferis to fully support Mechanoid's PostgreSQL schema management.

## High Priority

### 1. NOT NULL Constraints

**Current limitation:** `createTable[T]()` creates all columns as nullable by default.

**Needed:** Ability to specify NOT NULL constraints on columns.

**Proposed API:**
```scala
// Option A - Annotation
final case class EventRow(
    @notNull @label("instance_id") instanceId: String,
    ...
) derives Table

// Option B - Infer from non-Option types
// If field is String, make NOT NULL. If Option[String], make nullable.
```

### 2. JSONB Column Type

**Current limitation:** `String` fields map to `varchar(255)`, not `jsonb`.

**Needed:** Way to specify JSONB type for JSON data columns.

**Proposed API:**
```scala
// Option A - Annotation
final case class EventRow(
    @jsonb @label("event_data") eventData: String,
    ...
) derives Table

// Option B - Use zio-json JsonCodec types
final case class EventRow(
    @label("event_data") eventData: Json, // Maps to JSONB
    ...
) derives Table
```

### 3. TIMESTAMPTZ for Instant

**Current limitation:** `java.time.Instant` uses `Types.TIMESTAMP` → `timestamp` (no timezone).

**Needed:** Use `Types.TIMESTAMP_WITH_TIMEZONE` → `timestamptz` for proper timezone handling.

**Fix:** Update the `instant` encoder in `Encoder.scala`:
```scala
given instant: Encoder[java.time.Instant] with
  override val jdbcType: Int = java.sql.Types.TIMESTAMP_WITH_TIMEZONE // was TIMESTAMP
```

### 4. TEXT vs VARCHAR

**Current limitation:** `String` uses `Types.VARCHAR` → `varchar(255)`.

**Needed:** Way to specify TEXT type for unbounded string columns.

**Proposed API:**
```scala
// Option A - Annotation
final case class EventRow(
    @text @label("instance_id") instanceId: String,
    ...
) derives Table

// Option B - New type
type Text = String // with special encoder
```

## Medium Priority

### 5. Multi-Column UNIQUE Constraints

**Current limitation:** Only single-column unique constraints via `@unique` annotation.

**Needed:** Compound unique constraints across multiple columns.

**Use case:**
```sql
UNIQUE (instance_id, sequence_nr)
```

**Proposed API:**
```scala
@tableName("fsm_events")
@uniqueConstraint("instance_id", "sequence_nr")
final case class EventRow(...) derives Table
```

### 6. Partial Indexes

**Current limitation:** `createIndex` doesn't support WHERE clauses.

**Needed:** Partial indexes with conditions.

**Use case:**
```sql
CREATE INDEX idx_commands_pending ON commands (next_retry_at) WHERE status = 'pending';
```

**Proposed API:**
```scala
createIndex[CommandRow]("idx_commands_pending", Seq("next_retry_at"), where = "status = 'pending'")
```

### 7. DEFAULT Values

**Current limitation:** No way to specify DEFAULT values for columns.

**Needed:** Column default values for timestamps, status fields, etc.

**Use case:**
```sql
created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
status TEXT NOT NULL DEFAULT 'pending'
```

**Proposed API:**
```scala
final case class CommandRow(
    @default("NOW()") @label("created_at") createdAt: Instant,
    @default("'pending'") status: String,
    ...
) derives Table
```

## Nice to Have

### 8. Compound Index Annotations

**Current status:** Multi-column indexes work via `createIndex(name, Seq(col1, col2))` but not via annotations.

**Proposed API:**
```scala
@compoundIndex("instance_id", "sequence_nr")
final case class EventRow(...) derives Table
```

## Current Workaround

Until these features are added, Mechanoid uses the SQL interpolator directly for table creation:

```scala
xa.run(sql"""CREATE TABLE fsm_events (
  id BIGSERIAL PRIMARY KEY,
  instance_id TEXT NOT NULL,
  sequence_nr BIGINT NOT NULL,
  event_data JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (instance_id, sequence_nr)
)""".dml)
```

This approach:
- Gives full control over exact PostgreSQL types
- Supports all constraints and defaults
- Works with the sql"" interpolator for type safety on parameters
- Schema verification still works via information_schema queries

See `PostgresSchema.scala` for the full implementation.
