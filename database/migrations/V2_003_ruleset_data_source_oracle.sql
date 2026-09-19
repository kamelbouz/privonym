SET DEFINE OFF
SET SERVEROUTPUT ON

PROMPT Privonym V2 - bind RULESET to DATA_SOURCE

DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count
      FROM user_tab_columns
     WHERE table_name = 'RULESET'
       AND column_name = 'DATA_SOURCE_ID';

    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE ruleset ADD (data_source_id NUMBER)';
        DBMS_OUTPUT.PUT_LINE('[OK] RULESET.DATA_SOURCE_ID added');
    ELSE
        DBMS_OUTPUT.PUT_LINE('[SKIP] RULESET.DATA_SOURCE_ID already exists');
    END IF;
END;
/

DECLARE
    v_conflicts NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_conflicts
      FROM (
            SELECT rv.ruleset_id
              FROM ruleset_version rv
              JOIN anonymization_job source_link
                ON source_link.ruleset_version_id = rv.ruleset_version_id
             GROUP BY rv.ruleset_id
            HAVING COUNT(DISTINCT source_link.data_source_id) > 1
           );

    IF v_conflicts > 0 THEN
        RAISE_APPLICATION_ERROR(-20041,
            'Migration stopped: a ruleset is currently used by more than one data source');
    END IF;
END;
/

MERGE INTO ruleset target
USING (
    SELECT rv.ruleset_id, MIN(source_link.data_source_id) data_source_id
      FROM ruleset_version rv
      JOIN anonymization_job source_link
        ON source_link.ruleset_version_id = rv.ruleset_version_id
     GROUP BY rv.ruleset_id
) source
ON (target.ruleset_id = source.ruleset_id)
WHEN MATCHED THEN UPDATE
 SET target.data_source_id = source.data_source_id
 WHERE target.data_source_id IS NULL;

COMMIT;

DECLARE
    v_orphans NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_orphans
      FROM ruleset
     WHERE data_source_id IS NULL;

    IF v_orphans > 0 THEN
        RAISE_APPLICATION_ERROR(-20042,
            'Migration stopped: ' || v_orphans ||
            ' ruleset(s) have no data source. Assign RULESET.DATA_SOURCE_ID and rerun.');
    END IF;
END;
/

DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count
      FROM user_constraints
     WHERE table_name = 'RULESET'
       AND constraint_name = 'RULESET_DATA_SOURCE_FK';

    IF v_count = 0 THEN
        EXECUTE IMMEDIATE
            'ALTER TABLE ruleset ADD CONSTRAINT ruleset_data_source_fk ' ||
            'FOREIGN KEY (data_source_id) REFERENCES data_source (data_source_id)';
        DBMS_OUTPUT.PUT_LINE('[OK] RULESET_DATA_SOURCE_FK created');
    END IF;

    SELECT COUNT(*) INTO v_count
      FROM user_indexes
     WHERE index_name = 'RULESET_DATA_SOURCE_IX';

    IF v_count = 0 THEN
        EXECUTE IMMEDIATE
            'CREATE INDEX ruleset_data_source_ix ON ruleset (data_source_id)';
        DBMS_OUTPUT.PUT_LINE('[OK] RULESET_DATA_SOURCE_IX created');
    END IF;

    SELECT COUNT(*) INTO v_count
      FROM user_tab_columns
     WHERE table_name = 'RULESET'
       AND column_name = 'DATA_SOURCE_ID'
       AND nullable = 'Y';

    IF v_count > 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE ruleset MODIFY (data_source_id NOT NULL)';
        DBMS_OUTPUT.PUT_LINE('[OK] RULESET.DATA_SOURCE_ID made mandatory');
    ELSE
        DBMS_OUTPUT.PUT_LINE('[SKIP] RULESET.DATA_SOURCE_ID is already mandatory');
    END IF;
END;
/

DECLARE
    v_mismatches NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_mismatches
      FROM anonymization_job j
      JOIN ruleset_version rv ON rv.ruleset_version_id = j.ruleset_version_id
      JOIN ruleset r ON r.ruleset_id = rv.ruleset_id
     WHERE j.data_source_id <> r.data_source_id;

    IF v_mismatches > 0 THEN
        RAISE_APPLICATION_ERROR(-20043,
            'Migration stopped: existing jobs do not use the data source of their ruleset');
    END IF;
END;
/

DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count
      FROM session_privs
     WHERE privilege = 'CREATE TRIGGER';

    IF v_count = 0 THEN
        DBMS_OUTPUT.PUT_LINE(
            '[WARN] Job consistency trigger not installed: grant CREATE TRIGGER to PRIVONYM');
    ELSE
        EXECUTE IMMEDIATE q'~
            CREATE OR REPLACE TRIGGER anonymization_job_ruleset_ds_ck
            BEFORE INSERT OR UPDATE OF ruleset_version_id, data_source_id
            ON anonymization_job
            FOR EACH ROW
            DECLARE
                v_ruleset_data_source_id ruleset.data_source_id%TYPE;
            BEGIN
                SELECT r.data_source_id
                  INTO v_ruleset_data_source_id
                  FROM ruleset r
                  JOIN ruleset_version rv ON rv.ruleset_id = r.ruleset_id
                 WHERE rv.ruleset_version_id = :NEW.ruleset_version_id;

                IF v_ruleset_data_source_id <> :NEW.data_source_id THEN
                    RAISE_APPLICATION_ERROR(-20044,
                        'Job data source must match the ruleset data source');
                END IF;
            END;
        ~';
        DBMS_OUTPUT.PUT_LINE('[OK] ANONYMIZATION_JOB_RULESET_DS_CK installed');
    END IF;
END;
/

PROMPT [OK] Core Environment -> Data Source -> Ruleset -> Inventory -> Job relationship installed.
