-- Business audit log, copied from canonical ERD 16-foundation.md (Audit §17).

-- Append-only; ghi CÙNG transaction với mutation cần audit (§17.8)
CREATE TABLE aud_audit_log (
  id             BINARY(16)   NOT NULL,
  actor_type     VARCHAR(32)  NOT NULL,               -- USER | SYSTEM | JOB
  actor_user_id  BINARY(16)   NULL,
  actor_persona  VARCHAR(32)  NULL,
  actor_staff_id BINARY(16)   NULL,
  actor_job_name VARCHAR(64)  NULL,
  action         VARCHAR(64)  NOT NULL,               -- ví dụ ORDER_CANCEL_PRIVILEGED, PAYMENT_VERIFY, ROLE_GRANT
  entity_type    VARCHAR(64)  NOT NULL,
  entity_id      BINARY(16)   NOT NULL,
  branch_id      BINARY(16)   NULL,
  source         VARCHAR(32)  NOT NULL,               -- CUSTOMER_WEB | BACKOFFICE_WEB | JOB | SYSTEM
  old_value      JSON         NULL,                   -- purpose-specific diff, đã redact
  new_value      JSON         NULL,
  reason         VARCHAR(1000) NULL,
  correlation_id VARCHAR(64)  NULL,
  trace_id       VARCHAR(64)  NULL,
  ip_address     VARCHAR(45)  NULL,
  user_agent     VARCHAR(512) NULL,
  occurred_at    DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  KEY ix_aud_audit_log__entity (entity_type, entity_id, occurred_at),
  KEY ix_aud_audit_log__branch_occurred (branch_id, occurred_at),
  KEY ix_aud_audit_log__actor_occurred (actor_user_id, occurred_at),
  KEY ix_aud_audit_log__action_occurred (action, occurred_at)
);
