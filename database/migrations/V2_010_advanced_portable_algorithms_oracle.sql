-- Adds portable advanced algorithms to the Oracle repository.
-- SQL Server HMAC and regex implementations require the corresponding
-- dbo.PRIVONYM_HMAC_SHA256 and dbo.PRIVONYM_REGEX_REPLACE target functions.
SET DEFINE OFF

DECLARE
  v_version NUMBER;
  v_dataset NUMBER;
  PROCEDURE ensure_algorithm(
      p_code VARCHAR2,p_name VARCHAR2,p_category VARCHAR2,p_description VARCHAR2,
      p_deterministic CHAR,p_preserve_format CHAR,p_version_id OUT NUMBER) IS
    v_algorithm_id NUMBER;
  BEGIN
    INSERT INTO algorithm(code,name,category,description,deterministic,reversible,
      preserve_null,preserve_format,preserve_length,preserve_uniqueness,status)
    SELECT p_code,p_name,p_category,p_description,p_deterministic,'N','Y',
      p_preserve_format,'N','N','ACTIVE' FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm WHERE code=p_code);
    SELECT algorithm_id INTO v_algorithm_id FROM algorithm WHERE code=p_code;
    INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at)
    SELECT v_algorithm_id,1,'PUBLISHED','V2_010',SYSTIMESTAMP FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm_version
                       WHERE algorithm_id=v_algorithm_id AND version_number=1);
    SELECT algorithm_version_id INTO p_version_id FROM algorithm_version
     WHERE algorithm_id=v_algorithm_id AND version_number=1;
  END;

  PROCEDURE ensure_parameter(p_version_id NUMBER,p_name VARCHAR2,p_type VARCHAR2,
      p_required CHAR,p_default VARCHAR2,p_validation VARCHAR2,p_description VARCHAR2) IS
  BEGIN
    INSERT INTO algorithm_parameter_def(algorithm_version_id,parameter_name,data_type,
      required,default_value,validation_expression,description)
    SELECT p_version_id,p_name,p_type,p_required,p_default,p_validation,p_description FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm_parameter_def
                       WHERE algorithm_version_id=p_version_id AND parameter_name=p_name);
  END;

  PROCEDURE ensure_implementation(p_version_id NUMBER,p_technology VARCHAR2,
      p_type VARCHAR2,p_sql VARCHAR2) IS
  BEGIN
    INSERT INTO algorithm_implementation(algorithm_version_id,technology_code,
      implementation_type,implementation_code,code_checksum,status,published_at)
    SELECT p_version_id,p_technology,p_type,TO_CLOB(p_sql),
      LOWER(STANDARD_HASH(p_sql,'SHA256')),'PUBLISHED',SYSTIMESTAMP FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm_implementation
                       WHERE algorithm_version_id=p_version_id
                         AND technology_code=p_technology);
  END;

BEGIN
  ensure_algorithm('HMAC_SHA256','Keyed HMAC-SHA256 pseudonym','HASHING',
    'Produces a deterministic keyed SHA-256 message authentication code.','Y','N',v_version);
  ensure_parameter(v_version,'key_material','STRING','Y',NULL,
    'non-empty secret up to 512 characters',
    'Key material. Restrict repository access until external secret references are supported.');
  ensure_implementation(v_version,'ORACLE','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE LOWER(RAWTOHEX(DBMS_CRYPTO.MAC(UTL_I18N.STRING_TO_RAW(TO_CHAR(${column}),'AL32UTF8'),DBMS_CRYPTO.HMAC_SH256,UTL_I18N.STRING_TO_RAW(${param.key_material},'AL32UTF8')))) END~');
  ensure_implementation(v_version,'POSTGRESQL','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE ENCODE(HMAC(CONVERT_TO(CAST(${column} AS TEXT),'UTF8'),CONVERT_TO(${param.key_material},'UTF8'),'sha256'),'hex') END~');
  ensure_implementation(v_version,'SQLSERVER','DATABASE_FUNCTION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE dbo.PRIVONYM_HMAC_SHA256(CONVERT(nvarchar(max),${column}),${param.key_material}) END~');

  ensure_algorithm('CONSISTENT_LOOKUP','Consistent lookup substitution','SUBSTITUTION',
    'Maps identical input values to identical neutral lookup values.','Y','N',v_version);
  ensure_implementation(v_version,'ORACLE','LOOKUP_MAPPING','LOOKUP_DATASET');
  ensure_implementation(v_version,'POSTGRESQL','LOOKUP_MAPPING','LOOKUP_DATASET');
  ensure_implementation(v_version,'SQLSERVER','LOOKUP_MAPPING','LOOKUP_DATASET');
  INSERT INTO lookup_dataset(code,name,category,version_number,status,published_at)
  SELECT 'CONSISTENT_NEUTRAL_VALUES','Consistent neutral values','GENERIC',1,'PUBLISHED',SYSTIMESTAMP FROM dual
   WHERE NOT EXISTS(SELECT 1 FROM lookup_dataset
                     WHERE code='CONSISTENT_NEUTRAL_VALUES' AND version_number=1);
  SELECT lookup_dataset_id INTO v_dataset FROM lookup_dataset
   WHERE code='CONSISTENT_NEUTRAL_VALUES' AND version_number=1;
  FOR i IN 1..20 LOOP
    INSERT INTO lookup_dataset_value(lookup_dataset_id,value_text,weight_value,enabled)
    SELECT v_dataset,'ANON_'||LPAD(i,3,'0'),1,'Y' FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM lookup_dataset_value
                       WHERE lookup_dataset_id=v_dataset
                         AND value_text='ANON_'||LPAD(i,3,'0'));
  END LOOP;
  INSERT INTO algorithm_lookup_config(algorithm_version_id,lookup_dataset_id)
  SELECT v_version,v_dataset FROM dual
   WHERE NOT EXISTS(SELECT 1 FROM algorithm_lookup_config
                     WHERE algorithm_version_id=v_version);

  ensure_algorithm('DATE_SHIFT_DETERMINISTIC','Deterministic date shift','GENERALIZATION',
    'Shifts every date by a configured fixed number of days while preserving NULL.','Y','Y',v_version);
  ensure_parameter(v_version,'shift_days','INTEGER','N','30',
    'integer between -36500 and 36500','Fixed number of days added to the source date.');
  ensure_implementation(v_version,'ORACLE','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE ${column}+${param.shift_days} END~');
  ensure_implementation(v_version,'POSTGRESQL','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE ${column}+(${param.shift_days}*INTERVAL '1 day') END~');
  ensure_implementation(v_version,'SQLSERVER','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE DATEADD(day,${param.shift_days},${column}) END~');

  ensure_algorithm('REGEX_MASK','Regular-expression mask','MASKING',
    'Replaces every substring matching a configured regular expression.','Y','Y',v_version);
  ensure_parameter(v_version,'pattern','STRING','Y',NULL,
    'non-empty expression up to 500 characters','Regular expression evaluated by the target database.');
  ensure_parameter(v_version,'replacement','STRING','N','X',
    'string up to 100 characters','Replacement text.');
  ensure_implementation(v_version,'ORACLE','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE REGEXP_REPLACE(TO_CHAR(${column}),${param.pattern},${param.replacement}) END~');
  ensure_implementation(v_version,'POSTGRESQL','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE REGEXP_REPLACE(CAST(${column} AS TEXT),${param.pattern},${param.replacement},'g') END~');
  ensure_implementation(v_version,'SQLSERVER','DATABASE_FUNCTION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE dbo.PRIVONYM_REGEX_REPLACE(CONVERT(nvarchar(max),${column}),${param.pattern},${param.replacement}) END~');

  ensure_algorithm('CONDITIONAL_MASK','Conditional string replacement','MASKING',
    'Replaces a text value only when it equals a configured comparison value.','Y','Y',v_version);
  ensure_parameter(v_version,'condition_value','STRING','Y',NULL,
    'string up to 1000 characters','Value that activates masking.');
  ensure_parameter(v_version,'replacement_value','STRING','N','MASKED',
    'string up to 1000 characters','Replacement returned when the condition matches.');
  ensure_implementation(v_version,'ORACLE','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL WHEN ${column}=${param.condition_value} THEN ${param.replacement_value} ELSE ${column} END~');
  ensure_implementation(v_version,'POSTGRESQL','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL WHEN ${column}=${param.condition_value} THEN ${param.replacement_value} ELSE ${column} END~');
  ensure_implementation(v_version,'SQLSERVER','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL WHEN ${column}=${param.condition_value} THEN ${param.replacement_value} ELSE ${column} END~');
END;
/

COMMIT;
