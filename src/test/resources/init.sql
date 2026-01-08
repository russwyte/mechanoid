-- Mechanoid PostgreSQL Schema
-- This file is used by Testcontainers to initialize the test database

-- ============================================
-- Event Sourcing Tables
-- ============================================

CREATE TABLE fsm_events (
  id            BIGSERIAL PRIMARY KEY,
  instance_id   TEXT NOT NULL,
  sequence_nr   BIGINT NOT NULL,
  event_data    JSONB NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  UNIQUE (instance_id, sequence_nr)
);

CREATE INDEX idx_fsm_events_instance ON fsm_events (instance_id, sequence_nr);

CREATE TABLE fsm_snapshots (
  instance_id   TEXT PRIMARY KEY,
  state_data    JSONB NOT NULL,
  sequence_nr   BIGINT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================
-- Durable Timeout Table
-- ============================================

CREATE TABLE scheduled_timeouts (
  instance_id   TEXT PRIMARY KEY,
  state         TEXT NOT NULL,
  deadline      TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  claimed_by    TEXT,
  claimed_until TIMESTAMPTZ
);

CREATE INDEX idx_timeouts_deadline ON scheduled_timeouts (deadline);

-- ============================================
-- Distributed Lock Table
-- ============================================

CREATE TABLE fsm_instance_locks (
  instance_id   TEXT PRIMARY KEY,
  node_id       TEXT NOT NULL,
  acquired_at   TIMESTAMPTZ NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_locks_expired ON fsm_instance_locks (expires_at);

-- ============================================
-- Leader Election Leases Table
-- ============================================

CREATE TABLE leases (
  key         TEXT PRIMARY KEY,
  holder      TEXT NOT NULL,
  expires_at  TIMESTAMPTZ NOT NULL,
  acquired_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_leases_expires ON leases (expires_at);

-- ============================================
-- Command Queue Table
-- ============================================

CREATE TABLE commands (
  id              BIGSERIAL PRIMARY KEY,
  instance_id     TEXT NOT NULL,
  command_data    JSONB NOT NULL,
  idempotency_key TEXT NOT NULL UNIQUE,
  enqueued_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  status          TEXT NOT NULL DEFAULT 'pending',
  attempts        INT NOT NULL DEFAULT 0,
  last_attempt_at TIMESTAMPTZ,
  last_error      TEXT,
  next_retry_at   TIMESTAMPTZ,
  claimed_by      TEXT,
  claimed_until   TIMESTAMPTZ
);

CREATE INDEX idx_commands_pending ON commands (next_retry_at)
  WHERE status = 'pending';
CREATE INDEX idx_commands_instance ON commands (instance_id);
CREATE INDEX idx_commands_status ON commands (status);