-- Privonym V2.019 - deterministic relationship-aware token for all targets.
SET DEFINE OFF

DECLARE
  v_algorithm_id NUMBER;
  v_version_id NUMBER;

  PROCEDURE ensure_parameter(p_name VARCHAR2,p_required CHAR,p_default VARCHAR2,
      p_validation VARCHAR2,p_description VARCHAR2) IS
  BEGIN
    INSERT INTO algorithm_parameter_def(algorithm_version_id,parameter_name,data_type,
      required,default_value,validation_expression,description)
    SELECT v_version_id,p_name,'STRING',p_required,p_default,p_validation,p_description
      FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm_parameter_def
       WHERE algorithm_version_id=v_version_id AND parameter_name=p_name);
  END;

  PROCEDURE ensure_implementation(p_technology VARCHAR2,p_type VARCHAR2,p_sql VARCHAR2) IS
  BEGIN
    INSERT INTO algorithm_implementation(algorithm_version_id,technology_code,
      implementation_type,implementation_code,code_checksum,status,published_at)
    SELECT v_version_id,p_technology,p_type,TO_CLOB(p_sql),
      LOWER(STANDARD_HASH(p_sql,'SHA256')),'PUBLISHED',SYSTIMESTAMP FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm_implementation
       WHERE algorithm_version_id=v_version_id AND technology_code=p_technology);
  END;
BEGIN
  INSERT INTO algorithm(code,name,category,description,deterministic,reversible,
    preserve_null,preserve_format,preserve_length,preserve_uniqueness,status)
  SELECT 'RELATIONSHIP_AWARE_TOKEN','Relationship-aware deterministic token',
    'TOKENIZATION','Produces the same namespaced token for identical parent and child values across tables.',
    'Y','N','Y','N','N','N','ACTIVE' FROM dual
   WHERE NOT EXISTS(SELECT 1 FROM algorithm WHERE code='RELATIONSHIP_AWARE_TOKEN');

  SELECT algorithm_id INTO v_algorithm_id FROM algorithm
   WHERE code='RELATIONSHIP_AWARE_TOKEN';
  INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at)
  SELECT v_algorithm_id,1,'PUBLISHED','V2_019',SYSTIMESTAMP FROM dual
   WHERE NOT EXISTS(SELECT 1 FROM algorithm_version
     WHERE algorithm_id=v_algorithm_id AND version_number=1);
  SELECT algorithm_version_id INTO v_version_id FROM algorithm_version
   WHERE algorithm_id=v_algorithm_id AND version_number=1;

  ensure_parameter('key_material','Y',NULL,'non-empty secret up to 512 characters',
    'Secret key shared by every rule in the relationship domain.');
  ensure_parameter('token_namespace','N','GLOBAL','non-empty namespace up to 128 characters',
    'Domain name separating unrelated identifiers with the same source value.');

  ensure_implementation('ORACLE','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE LOWER(RAWTOHEX(DBMS_CRYPTO.MAC(UTL_I18N.STRING_TO_RAW(${param.token_namespace}||':'||TO_CHAR(${column}),'AL32UTF8'),DBMS_CRYPTO.HMAC_SH256,UTL_I18N.STRING_TO_RAW(${param.key_material},'AL32UTF8')))) END~');
  ensure_implementation('POSTGRESQL','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE ENCODE(HMAC(CONVERT_TO(${param.token_namespace}||':'||CAST(${column} AS TEXT),'UTF8'),CONVERT_TO(${param.key_material},'UTF8'),'sha256'),'hex') END~');
  ensure_implementation('SQLSERVER','DATABASE_FUNCTION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE dbo.PRIVONYM_HMAC_SHA256(CONCAT(${param.token_namespace},N':',CONVERT(nvarchar(max),${column})),${param.key_material}) END~');
END;
/

COMMIT;
