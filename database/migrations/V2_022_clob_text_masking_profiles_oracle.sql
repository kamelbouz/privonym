-- Publish REGEX_MASK v2 without changing existing Rule Set version links.
-- Ready-to-use profiles use database regex, not automatic person-name detection.
SET DEFINE OFF

DECLARE
  v_algorithm NUMBER;
  v_version NUMBER;
  v_number NUMBER;
  v_sql VARCHAR2(4000);

  PROCEDURE implementation(p_technology VARCHAR2,p_sql VARCHAR2) IS
  BEGIN
    MERGE INTO algorithm_implementation t
    USING (SELECT v_version version_id,p_technology technology FROM dual) s
       ON (t.algorithm_version_id=s.version_id AND t.technology_code=s.technology)
    WHEN MATCHED THEN UPDATE SET t.implementation_type='SQL_EXPRESSION',
      t.implementation_code=TO_CLOB(p_sql),t.code_checksum=LOWER(STANDARD_HASH(p_sql,'SHA256')),
      t.status='PUBLISHED'
    WHEN NOT MATCHED THEN INSERT (algorithm_version_id,technology_code,implementation_type,
      implementation_code,code_checksum,status,published_at)
      VALUES(v_version,p_technology,'SQL_EXPRESSION',TO_CLOB(p_sql),
        LOWER(STANDARD_HASH(p_sql,'SHA256')),'PUBLISHED',SYSTIMESTAMP);
  END;

  PROCEDURE profile(p_code VARCHAR2,p_name VARCHAR2,p_pattern VARCHAR2) IS
  BEGIN
    INSERT INTO algorithm(code,name,category,description,deterministic,reversible,
      preserve_null,preserve_format,preserve_length,preserve_uniqueness,status)
    SELECT p_code,p_name,'MASKING',
      'Replaces matching fragments of text; format detection only, not identity or checksum validation.',
      'Y','N','Y','N','N','N','ACTIVE' FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm WHERE code=p_code);
    SELECT algorithm_id INTO v_algorithm FROM algorithm WHERE code=p_code;
    INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at)
    SELECT v_algorithm,1,'PUBLISHED','V2_022',SYSTIMESTAMP FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm_version WHERE algorithm_id=v_algorithm AND version_number=1);
    SELECT algorithm_version_id INTO v_version FROM algorithm_version
     WHERE algorithm_id=v_algorithm AND version_number=1;
    INSERT INTO algorithm_parameter_def(algorithm_version_id,parameter_name,data_type,
      required,default_value,validation_expression,description)
    SELECT v_version,'replacement','STRING','N','ANONYMIZED','string up to 100 characters',
      'Literal text replacing each detected fragment. Default: ANONYMIZED.' FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm_parameter_def
       WHERE algorithm_version_id=v_version AND parameter_name='replacement');
    -- Escaping backslashes keeps profile replacement text literal on both engines.
    v_sql := 'CASE WHEN ${column} IS NULL THEN NULL ELSE REGEXP_REPLACE(${column},'
      || CHR(39) || REPLACE(p_pattern,CHR(39),CHR(39)||CHR(39)) || CHR(39)
      || ',REPLACE(${param.replacement},CHR(92),CHR(92)||CHR(92)),1,0,''i'') END';
    implementation('ORACLE',v_sql);
    v_sql := 'CASE WHEN ${column} IS NULL THEN NULL ELSE REGEXP_REPLACE(CAST(${column} AS TEXT),'
      || CHR(39) || REPLACE(p_pattern,CHR(39),CHR(39)||CHR(39)) || CHR(39)
      || ',REPLACE(${param.replacement},CHR(92),CHR(92)||CHR(92)),''gi'') END';
    implementation('POSTGRESQL',v_sql);
    -- No SQL Server profile is advertised without a qualified regex engine.
  END;
BEGIN
  SELECT algorithm_id INTO v_algorithm FROM algorithm WHERE code='REGEX_MASK';
  BEGIN
    SELECT algorithm_version_id INTO v_version FROM algorithm_version
     WHERE algorithm_id=v_algorithm AND created_by='V2_022';
  EXCEPTION WHEN NO_DATA_FOUND THEN
    SELECT NVL(MAX(version_number),0)+1 INTO v_number FROM algorithm_version
     WHERE algorithm_id=v_algorithm;
    INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at)
    VALUES(v_algorithm,v_number,'PUBLISHED','V2_022',SYSTIMESTAMP)
    RETURNING algorithm_version_id INTO v_version;
    INSERT INTO algorithm_parameter_def(algorithm_version_id,parameter_name,data_type,
      required,default_value,validation_expression,description)
    SELECT v_version,parameter_name,data_type,required,default_value,validation_expression,description
      FROM algorithm_parameter_def WHERE algorithm_version_id=(
        SELECT algorithm_version_id FROM algorithm_version WHERE algorithm_id=v_algorithm
          AND version_number=(SELECT MAX(version_number) FROM algorithm_version
            WHERE algorithm_id=v_algorithm AND algorithm_version_id<>v_version));
  END;
  implementation('ORACLE',
    q'~CASE WHEN ${column} IS NULL THEN NULL ELSE REGEXP_REPLACE(${column},${param.pattern},${param.replacement}) END~');
  implementation('POSTGRESQL',
    q'~CASE WHEN ${column} IS NULL THEN NULL ELSE REGEXP_REPLACE(CAST(${column} AS TEXT),${param.pattern},${param.replacement},'g') END~');

  profile('TEXT_REDACT_EMAIL','Text: replace email addresses',
    q'~[[:alnum:]_.%+-]+@[[:alnum:].-]+\.[[:alpha:]]{2,}~');
  profile('TEXT_REDACT_PHONE_FR','Text: replace French phone numbers',
    q'~(\+33|0033|0)[ .-]?[1-9]([ .-]?[0-9]{2}){4}~');
  profile('TEXT_REDACT_PHONE_CH','Text: replace Swiss phone numbers',
    q'~(\+41|0041|0)[ .-]?[1-9][0-9][ .-]?[0-9]{3}([ .-]?[0-9]{2}){2}~');
  profile('TEXT_REDACT_IBAN_FR_CH','Text: replace French and Swiss IBAN candidates',
    q'~(CH[0-9]{2}([ ]?[A-Z0-9]){17}|FR[0-9]{2}([ ]?[A-Z0-9]){23})~');
END;
/

COMMIT;
