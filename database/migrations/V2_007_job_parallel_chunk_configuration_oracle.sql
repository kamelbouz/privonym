-- Adds administrator-controlled parallel and chunk execution settings.
-- Safe to run repeatedly.
DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM user_tab_columns
     WHERE table_name='ANONYMIZATION_JOB' AND column_name='EXECUTION_MODE';
    IF v_count=0 THEN
        EXECUTE IMMEDIATE q'~ALTER TABLE anonymization_job ADD (
            execution_mode VARCHAR2(12 CHAR) DEFAULT 'AUTO' NOT NULL)~';
    END IF;

    SELECT COUNT(*) INTO v_count FROM user_tab_columns
     WHERE table_name='ANONYMIZATION_JOB' AND column_name='CHUNK_SIZE';
    IF v_count=0 THEN
        EXECUTE IMMEDIATE q'~ALTER TABLE anonymization_job ADD (
            chunk_size NUMBER(10) DEFAULT 100000 NOT NULL)~';
    END IF;

    SELECT COUNT(*) INTO v_count FROM user_tab_columns
     WHERE table_name='ANONYMIZATION_JOB' AND column_name='CHUNK_THRESHOLD';
    IF v_count=0 THEN
        EXECUTE IMMEDIATE q'~ALTER TABLE anonymization_job ADD (
            chunk_threshold NUMBER(19) DEFAULT 1000000 NOT NULL)~';
    END IF;

    SELECT COUNT(*) INTO v_count FROM user_constraints
     WHERE table_name='ANONYMIZATION_JOB'
       AND constraint_name='ANONYMIZATION_JOB_MODE_CK';
    IF v_count=0 THEN
        EXECUTE IMMEDIATE q'~ALTER TABLE anonymization_job ADD CONSTRAINT
            anonymization_job_mode_ck CHECK (execution_mode IN ('AUTO','DIRECT','CHUNKED'))~';
    END IF;

    SELECT COUNT(*) INTO v_count FROM user_constraints
     WHERE table_name='ANONYMIZATION_JOB'
       AND constraint_name='ANONYMIZATION_JOB_CHUNK_CK';
    IF v_count=0 THEN
        EXECUTE IMMEDIATE q'~ALTER TABLE anonymization_job ADD CONSTRAINT
            anonymization_job_chunk_ck CHECK (
                chunk_size BETWEEN 1000 AND 1000000 AND
                chunk_threshold BETWEEN chunk_size AND 999999999999999999)~';
    END IF;
END;
/

COMMIT;
