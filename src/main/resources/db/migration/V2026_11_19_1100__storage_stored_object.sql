-- File / Object Storage: metadata of objects whose bytes live in object storage (Storage §18.2, Decision G8, AF).
-- DDL from ERD 16-foundation.
CREATE TABLE sto_stored_object (
  id                   BINARY(16)    NOT NULL,
  provider             VARCHAR(32)   NOT NULL,        -- MINIO | S3 | ...
  bucket               VARCHAR(128)  NOT NULL,
  object_key           VARCHAR(512)  NOT NULL,
  purpose              VARCHAR(64)   NOT NULL,        -- PRODUCT_IMAGE | PAYMENT_EVIDENCE | REFUND_EVIDENCE | REPORT_EXPORT | ...
  original_file_name   VARCHAR(255)  NULL,
  detected_mime_type   VARCHAR(128)  NULL,
  size_bytes           BIGINT        NULL,
  checksum_sha256      CHAR(64)      NULL,
  width                INT           NULL,
  height               INT           NULL,
  status               VARCHAR(32)   NOT NULL,        -- PENDING_UPLOAD | UPLOADED | ACTIVE | QUARANTINED | ARCHIVED | DELETION_PENDING | DELETED | FAILED
  visibility           VARCHAR(32)   NOT NULL,        -- PUBLIC | PRIVATE | RESTRICTED
  uploaded_by_user_id  BINARY(16)    NULL,
  upload_expires_at    DATETIME(6)   NULL,            -- orphan cleanup cho upload chưa finalize
  finalized_at         DATETIME(6)   NULL,
  deletion_requested_at DATETIME(6)  NULL,
  deleted_at           DATETIME(6)   NULL,
  created_at           DATETIME(6)   NOT NULL,
  updated_at           DATETIME(6)   NOT NULL,
  version              BIGINT        NOT NULL DEFAULT 0,
  object_key_hash      CHAR(64) AS (SHA2(CONCAT(bucket, '/', object_key), 256)) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sto_stored_object__object_key_hash (object_key_hash),
  KEY ix_sto_stored_object__status_upload_expires (status, upload_expires_at),
  KEY ix_sto_stored_object__status_deletion (status, deletion_requested_at),
  CONSTRAINT ck_sto_stored_object__size CHECK (size_bytes IS NULL OR size_bytes >= 0)
);
