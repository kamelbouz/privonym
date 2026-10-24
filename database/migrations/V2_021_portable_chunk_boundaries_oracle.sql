-- Chunk boundaries also store PostgreSQL/SQL Server primary keys, not only ROWIDs.
-- Existing migration checksums and historical boundary values are preserved.
SET DEFINE OFF

DECLARE
  v_length NUMBER;
BEGIN
  SELECT char_length INTO v_length FROM user_tab_columns
   WHERE table_name='JOB_CHUNK' AND column_name='START_ROWID';
  IF v_length < 2000 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE job_chunk MODIFY (start_rowid VARCHAR2(2000 CHAR))';
  END IF;
  SELECT char_length INTO v_length FROM user_tab_columns
   WHERE table_name='JOB_CHUNK' AND column_name='END_ROWID';
  IF v_length < 2000 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE job_chunk MODIFY (end_rowid VARCHAR2(2000 CHAR))';
  END IF;
END;
/
