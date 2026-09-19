-- Adds the portable department lookup used by fine-grained Discovery classification.
SET DEFINE OFF

DECLARE
  v_algorithm_id algorithm.algorithm_id%TYPE;
  v_version_id algorithm_version.algorithm_version_id%TYPE;
  v_dataset_id lookup_dataset.lookup_dataset_id%TYPE;

  PROCEDURE ensure_implementation(p_technology VARCHAR2) IS
  BEGIN
    INSERT INTO algorithm_implementation(algorithm_version_id,technology_code,
      implementation_type,implementation_code,code_checksum,status,published_at)
    SELECT v_version_id,p_technology,'LOOKUP_MAPPING','LOOKUP_DATASET',
      LOWER(STANDARD_HASH('LOOKUP_DATASET','SHA256')),'PUBLISHED',SYSTIMESTAMP
      FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm_implementation
                       WHERE algorithm_version_id=v_version_id
                         AND technology_code=p_technology);
  END;

  PROCEDURE ensure_value(p_value VARCHAR2) IS
  BEGIN
    INSERT INTO lookup_dataset_value(lookup_dataset_id,value_text,weight_value,enabled)
    SELECT v_dataset_id,p_value,1,'Y' FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM lookup_dataset_value
                       WHERE lookup_dataset_id=v_dataset_id AND value_text=p_value);
  END;
BEGIN
  BEGIN
    SELECT algorithm_id INTO v_algorithm_id FROM algorithm
     WHERE code='LOOKUP_DEPARTMENT';
  EXCEPTION WHEN NO_DATA_FOUND THEN
    INSERT INTO algorithm(code,name,category,description,deterministic,reversible,
      preserve_null,preserve_format,preserve_length,preserve_uniqueness,status)
    VALUES('LOOKUP_DEPARTMENT','Lookup · Departments','SUBSTITUTION',
      'Replaces organization department names with published neutral department values.',
      'Y','N','Y','N','N','N','ACTIVE')
    RETURNING algorithm_id INTO v_algorithm_id;
  END;

  BEGIN
    SELECT algorithm_version_id INTO v_version_id FROM algorithm_version
     WHERE algorithm_id=v_algorithm_id AND version_number=1;
  EXCEPTION WHEN NO_DATA_FOUND THEN
    INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at)
    VALUES(v_algorithm_id,1,'PUBLISHED','MIGRATION',SYSTIMESTAMP)
    RETURNING algorithm_version_id INTO v_version_id;
  END;

  ensure_implementation('ORACLE');
  ensure_implementation('POSTGRESQL');
  ensure_implementation('SQLSERVER');

  BEGIN
    SELECT lookup_dataset_id INTO v_dataset_id FROM lookup_dataset
     WHERE code='LOOKUP_DEPARTMENT_VALUES' AND version_number=1;
  EXCEPTION WHEN NO_DATA_FOUND THEN
    INSERT INTO lookup_dataset(code,name,category,version_number,status,published_at)
    VALUES('LOOKUP_DEPARTMENT_VALUES','Department values','ORGANIZATION',1,
      'PUBLISHED',SYSTIMESTAMP)
    RETURNING lookup_dataset_id INTO v_dataset_id;
  END;

  ensure_value('FINANCE');
  ensure_value('INFORMATION TECHNOLOGY');
  ensure_value('RESEARCH AND DEVELOPMENT');
  ensure_value('HUMAN RESOURCES');
  ensure_value('OPERATIONS');
  ensure_value('RISK MANAGEMENT');
  ensure_value('COMPLIANCE');
  ensure_value('LEGAL');
  ensure_value('MARKETING');
  ensure_value('SALES');
  ensure_value('CUSTOMER SERVICE');
  ensure_value('INTERNAL AUDIT');
  ensure_value('PROCUREMENT');

  MERGE INTO algorithm_lookup_config target
  USING(SELECT v_version_id algorithm_version_id,v_dataset_id lookup_dataset_id FROM dual) source
     ON(target.algorithm_version_id=source.algorithm_version_id)
  WHEN MATCHED THEN UPDATE SET target.lookup_dataset_id=source.lookup_dataset_id
  WHEN NOT MATCHED THEN INSERT(algorithm_version_id,lookup_dataset_id)
       VALUES(source.algorithm_version_id,source.lookup_dataset_id);
END;
/

COMMIT;
