-- Privonym V2.020 - portable banking, JSON and coherent lookup algorithms.
SET DEFINE OFF

DECLARE
  v_version_id NUMBER;
  v_dataset_id NUMBER;

  PROCEDURE select_version(p_code VARCHAR2,p_name VARCHAR2,p_category VARCHAR2,
      p_description VARCHAR2,p_preserve_format CHAR) IS
    v_algorithm_id NUMBER;
  BEGIN
    INSERT INTO algorithm(code,name,category,description,deterministic,reversible,
      preserve_null,preserve_format,preserve_length,preserve_uniqueness,status)
    SELECT p_code,p_name,p_category,p_description,'Y','N','Y',p_preserve_format,
      'N','N','ACTIVE' FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm WHERE code=p_code);
    UPDATE algorithm SET name=p_name,category=p_category,description=p_description,
      deterministic='Y',preserve_null='Y',preserve_format=p_preserve_format,status='ACTIVE'
     WHERE code=p_code;
    SELECT algorithm_id INTO v_algorithm_id FROM algorithm WHERE code=p_code;
    INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at)
    SELECT v_algorithm_id,1,'PUBLISHED','V2_020',SYSTIMESTAMP FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm_version
       WHERE algorithm_id=v_algorithm_id AND version_number=1);
    SELECT algorithm_version_id INTO v_version_id FROM algorithm_version
     WHERE algorithm_id=v_algorithm_id AND version_number=1;
  END;

  PROCEDURE put_parameter(p_name VARCHAR2,p_required CHAR,p_default VARCHAR2,
      p_validation VARCHAR2,p_description VARCHAR2) IS
  BEGIN
    MERGE INTO algorithm_parameter_def t
    USING(SELECT v_version_id algorithm_version_id,p_name parameter_name FROM dual) s
       ON(t.algorithm_version_id=s.algorithm_version_id AND t.parameter_name=s.parameter_name)
    WHEN MATCHED THEN UPDATE SET t.data_type='STRING',t.required=p_required,
      t.default_value=p_default,t.validation_expression=p_validation,t.description=p_description
    WHEN NOT MATCHED THEN INSERT(algorithm_version_id,parameter_name,data_type,required,
      default_value,validation_expression,description)
      VALUES(v_version_id,p_name,'STRING',p_required,p_default,p_validation,p_description);
  END;

  PROCEDURE put_implementation(p_technology VARCHAR2,p_type VARCHAR2,p_sql VARCHAR2) IS
  BEGIN
    MERGE INTO algorithm_implementation t
    USING(SELECT v_version_id algorithm_version_id,p_technology technology_code FROM dual) s
       ON(t.algorithm_version_id=s.algorithm_version_id AND t.technology_code=s.technology_code)
    WHEN MATCHED THEN UPDATE SET t.implementation_type=p_type,t.implementation_code=TO_CLOB(p_sql),
      t.code_checksum=LOWER(STANDARD_HASH(p_sql,'SHA256')),t.status='PUBLISHED',
      t.published_at=SYSTIMESTAMP
    WHEN NOT MATCHED THEN INSERT(algorithm_version_id,technology_code,implementation_type,
      implementation_code,code_checksum,status,published_at)
      VALUES(v_version_id,p_technology,p_type,TO_CLOB(p_sql),
        LOWER(STANDARD_HASH(p_sql,'SHA256')),'PUBLISHED',SYSTIMESTAMP);
  END;

  PROCEDURE select_dataset(p_code VARCHAR2,p_name VARCHAR2,p_category VARCHAR2) IS
  BEGIN
    INSERT INTO lookup_dataset(code,name,category,version_number,status,published_at)
    SELECT p_code,p_name,p_category,1,'PUBLISHED',SYSTIMESTAMP FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM lookup_dataset WHERE code=p_code AND version_number=1);
    SELECT lookup_dataset_id INTO v_dataset_id FROM lookup_dataset
     WHERE code=p_code AND version_number=1;
  END;

  PROCEDURE put_value(p_value VARCHAR2) IS
  BEGIN
    MERGE INTO lookup_dataset_value t
    USING(SELECT v_dataset_id lookup_dataset_id,p_value value_text FROM dual) s
       ON(t.lookup_dataset_id=s.lookup_dataset_id AND UPPER(t.value_text)=UPPER(s.value_text))
    WHEN MATCHED THEN UPDATE SET t.enabled='Y'
    WHEN NOT MATCHED THEN INSERT(lookup_dataset_id,value_text,weight_value,enabled)
      VALUES(v_dataset_id,p_value,1,'Y');
  END;

  PROCEDURE link_dataset IS
  BEGIN
    MERGE INTO algorithm_lookup_config t
    USING(SELECT v_version_id algorithm_version_id,v_dataset_id lookup_dataset_id FROM dual) s
       ON(t.algorithm_version_id=s.algorithm_version_id)
    WHEN MATCHED THEN UPDATE SET t.lookup_dataset_id=s.lookup_dataset_id
    WHEN NOT MATCHED THEN INSERT(algorithm_version_id,lookup_dataset_id)
      VALUES(s.algorithm_version_id,s.lookup_dataset_id);
  END;
BEGIN
  select_version('IBAN_GENERATOR','Deterministic checksum-valid IBAN generator','SYNTHETIC',
    'Generates a deterministic IBAN with a recalculated MOD-97 checksum while preserving country and compact length.','N');
  put_parameter('key_material','Y',NULL,'non-empty secret up to 512 characters',
    'Secret used to derive the replacement BBAN.');
  put_implementation('ORACLE','DATABASE_FUNCTION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE PRIVONYM_IBAN_GENERATE(TO_CHAR(${column}),${param.key_material}) END~');
  put_implementation('POSTGRESQL','DATABASE_FUNCTION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE privonym_iban_generate(CAST(${column} AS TEXT),${param.key_material}) END~');
  put_implementation('SQLSERVER','DATABASE_FUNCTION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE dbo.PRIVONYM_IBAN_GENERATE(CONVERT(nvarchar(128),${column}),${param.key_material}) END~');

  select_version('PERSISTENT_MAPPING','Stable persistent mapping token','TOKENIZATION',
    'Maps identical values to a stable published neutral token set across Rule Sets and Jobs.','N');
  put_implementation('ORACLE','LOOKUP_MAPPING','LOOKUP_DATASET');
  put_implementation('POSTGRESQL','LOOKUP_MAPPING','LOOKUP_DATASET');
  put_implementation('SQLSERVER','LOOKUP_MAPPING','LOOKUP_DATASET');
  select_dataset('PERSISTENT_MAPPING_VALUES','Persistent neutral tokens','GENERIC');
  FOR i IN 1..100 LOOP put_value('TOKEN_' || LPAD(TO_CHAR(i),6,'0')); END LOOP;
  link_dataset;

  select_version('JSON_PATH_MASK','JSON path value replacement','MASKING',
    'Replaces one configured JSON path with a fixed text value while preserving the remaining document.','Y');
  put_parameter('json_path','Y',NULL,'JSON path up to 500 characters','Path such as $.customer.name.');
  put_parameter('replacement_value','N','MASKED','string up to 4000 characters',
    'Text written at the selected JSON path.');
  put_implementation('ORACLE','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE JSON_TRANSFORM(${column},SET ${param.json_path}=${param.replacement_value}) END~');
  put_implementation('POSTGRESQL','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE JSONB_SET(CAST(${column} AS JSONB),STRING_TO_ARRAY(REGEXP_REPLACE(${param.json_path},'^\$\.?',''),'.'),TO_JSONB(CAST(${param.replacement_value} AS TEXT)),true) END~');
  put_implementation('SQLSERVER','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE JSON_MODIFY(${column},${param.json_path},${param.replacement_value}) END~');

  select_version('GEOGRAPHIC_COHERENT_ADDRESS','Coherent US postal address','SYNTHETIC',
    'Replaces a complete address with a coherent US street, city, state and ZIP tuple.','Y');
  put_implementation('ORACLE','LOOKUP_MAPPING','LOOKUP_DATASET');
  put_implementation('POSTGRESQL','LOOKUP_MAPPING','LOOKUP_DATASET');
  put_implementation('SQLSERVER','LOOKUP_MAPPING','LOOKUP_DATASET');
  select_dataset('GEOGRAPHIC_US_ADDRESS_VALUES','Coherent US postal addresses','ADDRESS');
  FOR r IN (SELECT column_value value_text FROM TABLE(SYS.ODCIVARCHAR2LIST(
    '101 Main Street, New York, NY 10001','225 Market Street, San Francisco, CA 94105',
    '310 Congress Avenue, Austin, TX 78701','455 Pine Street, Seattle, WA 98101',
    '820 Lakeview Drive, Chicago, IL 60601','1420 Brickell Avenue, Miami, FL 33131',
    '75 State Street, Boston, MA 02109','600 Peachtree Street, Atlanta, GA 30308',
    '1700 Lincoln Street, Denver, CO 80203','900 SW Fifth Avenue, Portland, OR 97204',
    '1200 McKinney Street, Houston, TX 77010','400 South Hope Street, Los Angeles, CA 90071',
    '200 North Central Avenue, Phoenix, AZ 85004','1601 Market Street, Philadelphia, PA 19103',
    '100 North Tryon Street, Charlotte, NC 28202','500 Woodward Avenue, Detroit, MI 48226',
    '401 Union Street, Nashville, TN 37219','801 Grand Avenue, Des Moines, IA 50309',
    '250 East Wisconsin Avenue, Milwaukee, WI 53202','1 East Washington Street, Indianapolis, IN 46204'
  ))) LOOP put_value(r.value_text); END LOOP;
  link_dataset;
END;
/

COMMIT;
