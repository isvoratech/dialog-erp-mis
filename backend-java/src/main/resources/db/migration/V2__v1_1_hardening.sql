ALTER TABLE connection_source
  ADD COLUMN IF NOT EXISTS import_batch_id bigint REFERENCES import_batch(id);

CREATE INDEX IF NOT EXISTS idx_connection_source_import_mobile
  ON connection_source(import_batch_id, mobile_norm);

CREATE INDEX IF NOT EXISTS idx_import_batch_period
  ON import_batch(period_end DESC, created_at DESC);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'chk_recovery_approval_status'
  ) THEN
    ALTER TABLE recovery_row
      ADD CONSTRAINT chk_recovery_approval_status
      CHECK (approval_status IN ('DRAFT','NEEDS_REVIEW','APPROVED','REJECTED'));
  END IF;
END $$;
