SET DEFINE OFF
SET SERVEROUTPUT ON

PROMPT Privonym V2 - algorithm-owned lookup datasets

DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM user_tables
     WHERE table_name = 'ALGORITHM_LOOKUP_CONFIG';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE q'~
            CREATE TABLE algorithm_lookup_config (
                algorithm_version_id NUMBER NOT NULL,
                lookup_dataset_id NUMBER NOT NULL,
                created_at TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
                CONSTRAINT algorithm_lookup_config_pk PRIMARY KEY (algorithm_version_id),
                CONSTRAINT algorithm_lookup_config_version_fk FOREIGN KEY (algorithm_version_id)
                    REFERENCES algorithm_version (algorithm_version_id) ON DELETE CASCADE,
                CONSTRAINT algorithm_lookup_config_dataset_fk FOREIGN KEY (lookup_dataset_id)
                    REFERENCES lookup_dataset (lookup_dataset_id)
            )~';
        DBMS_OUTPUT.PUT_LINE('[OK] ALGORITHM_LOOKUP_CONFIG created');
    END IF;
END;
/

-- Preserve unambiguous legacy bindings only when the legacy column exists.
DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count
      FROM user_tab_columns
     WHERE table_name = 'MASKING_RULE'
       AND column_name = 'LOOKUP_DATASET_ID';
    IF v_count > 0 THEN
        EXECUTE IMMEDIATE q'~
            INSERT INTO algorithm_lookup_config (algorithm_version_id, lookup_dataset_id)
            SELECT mr.algorithm_version_id, MIN(mr.lookup_dataset_id)
              FROM masking_rule mr
             WHERE mr.lookup_dataset_id IS NOT NULL
               AND NOT EXISTS (
                   SELECT 1 FROM algorithm_lookup_config c
                    WHERE c.algorithm_version_id = mr.algorithm_version_id)
             GROUP BY mr.algorithm_version_id
            HAVING COUNT(DISTINCT mr.lookup_dataset_id) = 1
        ~';
        DBMS_OUTPUT.PUT_LINE('[OK] Legacy lookup bindings migrated');
    ELSE
        DBMS_OUTPUT.PUT_LINE('[SKIP] No legacy MASKING_RULE.LOOKUP_DATASET_ID column');
    END IF;
END;
/

DECLARE
    PROCEDURE seed_lookup(
        p_code VARCHAR2,
        p_name VARCHAR2,
        p_dataset_name VARCHAR2,
        p_category VARCHAR2,
        p_values SYS.ODCIVARCHAR2LIST) IS
        v_algorithm_id NUMBER;
        v_version_id NUMBER;
        v_dataset_id NUMBER;
    BEGIN
        BEGIN
            SELECT algorithm_id INTO v_algorithm_id FROM algorithm WHERE code = p_code;
        EXCEPTION WHEN NO_DATA_FOUND THEN
            INSERT INTO algorithm(code,name,category,description,deterministic,reversible,
                                  preserve_null,preserve_format,preserve_length,
                                  preserve_uniqueness,status)
            VALUES(p_code,p_name,'SUBSTITUTION','Built-in lookup algorithm','Y','N',
                   'Y','N','N','N','ACTIVE')
            RETURNING algorithm_id INTO v_algorithm_id;
        END;

        BEGIN
            SELECT algorithm_version_id INTO v_version_id
              FROM algorithm_version
             WHERE algorithm_id=v_algorithm_id AND version_number=1;
        EXCEPTION WHEN NO_DATA_FOUND THEN
            INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at)
            VALUES(v_algorithm_id,1,'PUBLISHED','MIGRATION',SYSTIMESTAMP)
            RETURNING algorithm_version_id INTO v_version_id;
        END;

        INSERT INTO algorithm_implementation(algorithm_version_id,technology_code,
                   implementation_type,implementation_code,code_checksum,status,published_at)
        SELECT v_version_id,'ORACLE','LOOKUP_MAPPING','LOOKUP_DATASET',
               STANDARD_HASH('LOOKUP_DATASET','SHA256'),'PUBLISHED',SYSTIMESTAMP
          FROM dual
         WHERE NOT EXISTS (SELECT 1 FROM algorithm_implementation
                            WHERE algorithm_version_id=v_version_id
                              AND technology_code='ORACLE');

        BEGIN
            SELECT lookup_dataset_id INTO v_dataset_id FROM lookup_dataset
             WHERE code=p_code||'_VALUES' AND version_number=1;
        EXCEPTION WHEN NO_DATA_FOUND THEN
            INSERT INTO lookup_dataset(code,name,category,version_number,status,published_at)
            VALUES(p_code||'_VALUES',p_dataset_name,p_category,1,'PUBLISHED',SYSTIMESTAMP)
            RETURNING lookup_dataset_id INTO v_dataset_id;
        END;

        FOR i IN 1..p_values.COUNT LOOP
            INSERT INTO lookup_dataset_value(lookup_dataset_id,value_text,weight_value,enabled)
            SELECT v_dataset_id,p_values(i),1,'Y' FROM dual
             WHERE NOT EXISTS (SELECT 1 FROM lookup_dataset_value
                                WHERE lookup_dataset_id=v_dataset_id
                                  AND value_text=p_values(i));
        END LOOP;

        MERGE INTO algorithm_lookup_config target
        USING (SELECT v_version_id algorithm_version_id, v_dataset_id lookup_dataset_id FROM dual) source
           ON (target.algorithm_version_id=source.algorithm_version_id)
        WHEN MATCHED THEN UPDATE SET target.lookup_dataset_id=source.lookup_dataset_id
        WHEN NOT MATCHED THEN INSERT(algorithm_version_id,lookup_dataset_id)
             VALUES(source.algorithm_version_id,source.lookup_dataset_id);
    END;
BEGIN
    seed_lookup('LOOKUP_LAST_NAME','Lookup · Last names','Last-name values','PERSON_NAME',
        SYS.ODCIVARCHAR2LIST('MARTIN','BERNARD','DUBOIS','THOMAS','ROBERT'));
    seed_lookup('LOOKUP_FIRST_NAME','Lookup · First names','First-name values','PERSON_NAME',
        SYS.ODCIVARCHAR2LIST('EMMA','LUCAS','LEA','HUGO','JADE'));
    seed_lookup('LOOKUP_CITY','Lookup · Cities','City values','ADDRESS',
        SYS.ODCIVARCHAR2LIST('PARIS','LYON','GENEVE','LAUSANNE','ZURICH'));
    seed_lookup('LOOKUP_ADDRESS','Lookup · Addresses','Address values','ADDRESS',
        SYS.ODCIVARCHAR2LIST('1 RUE DES LILAS','10 AVENUE CENTRALE','25 RUE DU LAC',
                            '8 CHEMIN DES FLEURS','42 RUE DE LA GARE'));
END;
/

COMMIT;
PROMPT [OK] Algorithm-owned lookup model and four built-in algorithms installed.
