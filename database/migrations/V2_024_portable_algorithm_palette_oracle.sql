-- First palette expansion. New definitions only; existing rules and versions are unchanged.
SET DEFINE OFF

DECLARE
  v_algorithm NUMBER;
  v_version NUMBER;

  PROCEDURE algorithm(p_code VARCHAR2,p_name VARCHAR2,p_category VARCHAR2,
      p_description VARCHAR2,p_length CHAR DEFAULT 'N') IS
  BEGIN
    INSERT INTO algorithm(code,name,category,description,deterministic,reversible,
      preserve_null,preserve_format,preserve_length,preserve_uniqueness,status)
    SELECT p_code,p_name,p_category,p_description,'Y','N','Y','N',p_length,'N','ACTIVE'
      FROM dual WHERE NOT EXISTS(SELECT 1 FROM algorithm WHERE code=p_code);
    SELECT algorithm_id INTO v_algorithm FROM algorithm WHERE code=p_code;
    INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at)
    SELECT v_algorithm,1,'PUBLISHED','V2_024',SYSTIMESTAMP FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm_version WHERE algorithm_id=v_algorithm AND version_number=1);
    SELECT algorithm_version_id INTO v_version FROM algorithm_version
     WHERE algorithm_id=v_algorithm AND version_number=1 AND status='PUBLISHED';
  END;

  PROCEDURE parameter(p_name VARCHAR2,p_type VARCHAR2,p_default VARCHAR2,
      p_validation VARCHAR2,p_description VARCHAR2) IS
  BEGIN
    INSERT INTO algorithm_parameter_def(algorithm_version_id,parameter_name,data_type,
      required,default_value,validation_expression,description)
    SELECT v_version,p_name,p_type,'N',p_default,p_validation,p_description FROM dual
     WHERE NOT EXISTS(SELECT 1 FROM algorithm_parameter_def
                       WHERE algorithm_version_id=v_version AND parameter_name=p_name);
  END;

  PROCEDURE implementation(p_technology VARCHAR2,p_sql VARCHAR2) IS
    v_existing VARCHAR2(64);
    v_expected VARCHAR2(64);
  BEGIN
    SELECT LOWER(STANDARD_HASH(p_sql,'SHA256')) INTO v_expected FROM dual;
    SELECT MAX(code_checksum) INTO v_existing FROM algorithm_implementation
     WHERE algorithm_version_id=v_version AND technology_code=p_technology;
    IF v_existing IS NOT NULL AND v_existing<>v_expected THEN
      RAISE_APPLICATION_ERROR(-20024,'Existing palette implementation differs; create a new version.');
    END IF;
    INSERT INTO algorithm_implementation(algorithm_version_id,technology_code,
      implementation_type,implementation_code,code_checksum,status,published_at)
    SELECT v_version,p_technology,'SQL_EXPRESSION',TO_CLOB(p_sql),v_expected,'PUBLISHED',SYSTIMESTAMP
      FROM dual WHERE NOT EXISTS(SELECT 1 FROM algorithm_implementation
                                 WHERE algorithm_version_id=v_version AND technology_code=p_technology);
  END;

  FUNCTION common_text(p_technology VARCHAR2) RETURN VARCHAR2 IS
    v_expression VARCHAR2(32767) := '${column}';
    v_body VARCHAR2(4000);
    v_prefix CONSTANT VARCHAR2(100) := 'CASE WHEN ${column} IS NULL THEN NULL ELSE ';
  BEGIN
    FOR profile IN (SELECT column_value code FROM TABLE(SYS.ODCIVARCHAR2LIST(
      'TEXT_REDACT_EMAIL','TEXT_REDACT_PHONE_FR','TEXT_REDACT_PHONE_CH','TEXT_REDACT_IBAN_FR_CH'))
      ORDER BY CASE column_value WHEN 'TEXT_REDACT_EMAIL' THEN 1 WHEN 'TEXT_REDACT_PHONE_FR' THEN 2
        WHEN 'TEXT_REDACT_PHONE_CH' THEN 3 ELSE 4 END) LOOP
      SELECT DBMS_LOB.SUBSTR(ai.implementation_code,4000,1) INTO v_body
        FROM algorithm a JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id
        JOIN algorithm_implementation ai ON ai.algorithm_version_id=av.algorithm_version_id
       WHERE a.code=profile.code AND av.status='PUBLISHED' AND ai.status='PUBLISHED'
         AND ai.technology_code=p_technology
       ORDER BY av.version_number DESC FETCH FIRST 1 ROW ONLY;
      IF SUBSTR(v_body,1,LENGTH(v_prefix))<>v_prefix OR SUBSTR(v_body,-4)<>' END' THEN
        RAISE_APPLICATION_ERROR(-20024,'Unexpected source text profile; composition requires qualification.');
      END IF;
      v_body := SUBSTR(v_body,LENGTH(v_prefix)+1,LENGTH(v_body)-LENGTH(v_prefix)-4);
      IF (LENGTH(v_body)-LENGTH(REPLACE(v_body,'${column}','')))/LENGTH('${column}')<>1 THEN
        RAISE_APPLICATION_ERROR(-20024,'Text profile must reference its source once.');
      END IF;
      v_expression := REPLACE(v_body,'${column}',v_expression);
    END LOOP;
    RETURN v_prefix||v_expression||' END';
  END;
BEGIN
  algorithm('MASK_PARTIAL','Configurable partial text mask','MASKING',
    'Preserves configured prefix/suffix; short values are fully masked to prevent overlap. Text columns only.','Y');
  parameter('visible_prefix_length','INTEGER','0','integer 0 to 100','Visible prefix characters.');
  parameter('visible_suffix_length','INTEGER','4','integer 0 to 100','Visible suffix characters.');
  parameter('mask_character','ENUM','*','one of * # X x ?','ASCII character filling the hidden section.');
  implementation('ORACLE',q'~SUBSTR(${column},1,CASE WHEN LENGTH(${column})>${param.visible_prefix_length}+${param.visible_suffix_length} THEN ${param.visible_prefix_length} ELSE 0 END)||RPAD(TO_CLOB(${param.mask_character}),CASE WHEN LENGTH(${column})<=${param.visible_prefix_length}+${param.visible_suffix_length} THEN LENGTH(${column}) ELSE LENGTH(${column})-${param.visible_prefix_length}-${param.visible_suffix_length} END,${param.mask_character})||SUBSTR(${column},CASE WHEN LENGTH(${column})>${param.visible_prefix_length}+${param.visible_suffix_length} AND ${param.visible_suffix_length}>0 THEN -${param.visible_suffix_length} ELSE LENGTH(${column})+1 END)~');
  implementation('POSTGRESQL',q'~CASE WHEN ${column} IS NULL THEN NULL WHEN CHAR_LENGTH(CAST(${column} AS TEXT))<=${param.visible_prefix_length}+${param.visible_suffix_length} THEN REPEAT(${param.mask_character},CHAR_LENGTH(CAST(${column} AS TEXT))) ELSE LEFT(CAST(${column} AS TEXT),${param.visible_prefix_length})||REPEAT(${param.mask_character},CHAR_LENGTH(CAST(${column} AS TEXT))-${param.visible_prefix_length}-${param.visible_suffix_length})||RIGHT(CAST(${column} AS TEXT),${param.visible_suffix_length}) END~');
  implementation('SQLSERVER',q'~CASE WHEN ${column} IS NULL THEN NULL WHEN LEN(CONVERT(nvarchar(max),${column})+N'#')-1<=${param.visible_prefix_length}+${param.visible_suffix_length} THEN REPLICATE(CONVERT(nvarchar(max),N${param.mask_character}),LEN(CONVERT(nvarchar(max),${column})+N'#')-1) ELSE LEFT(CONVERT(nvarchar(max),${column}),${param.visible_prefix_length})+REPLICATE(CONVERT(nvarchar(max),N${param.mask_character}),LEN(CONVERT(nvarchar(max),${column})+N'#')-1-${param.visible_prefix_length}-${param.visible_suffix_length})+RIGHT(CONVERT(nvarchar(max),${column}),${param.visible_suffix_length}) END~');

  algorithm('ROUND_NUMBER','Round numeric precision','GENERALIZATION',
    'Rounds an exact numeric value. Destination precision and constraints must accept the result.');
  parameter('scale','INTEGER','0','integer -6 to 6','Decimal places; negative values round tens/hundreds.');
  implementation('ORACLE',q'~ROUND(${column},${param.scale})~');
  implementation('POSTGRESQL',q'~ROUND(CAST(${column} AS NUMERIC),${param.scale})~');
  implementation('SQLSERVER',q'~ROUND(${column},${param.scale})~');

  algorithm('NUMBER_BUCKET','Numeric interval lower bound','GENERALIZATION',
    'Maps an exact numeric value to its interval lower bound, including negatives. May change destination precision.');
  parameter('bucket_size','DECIMAL','10','decimal 0.000001 to 1000000000000','Strictly positive interval width.');
  implementation('ORACLE',q'~${column}-MOD(${column},${param.bucket_size})-CASE WHEN MOD(${column},${param.bucket_size})<0 THEN ${param.bucket_size} ELSE 0 END~');
  implementation('POSTGRESQL',q'~CAST(${column} AS NUMERIC)-MOD(CAST(${column} AS NUMERIC),${param.bucket_size})-CASE WHEN MOD(CAST(${column} AS NUMERIC),${param.bucket_size})<0 THEN ${param.bucket_size} ELSE 0 END~');
  implementation('SQLSERVER',q'~${column}-(${column}%${param.bucket_size})-CASE WHEN (${column}%${param.bucket_size})<0 THEN ${param.bucket_size} ELSE 0 END~');

  algorithm('TEXT_REDACT_COMMON','Text: compose common FR/CH redaction profiles','MASKING',
    'Composes email, French/Swiss phone and French/Swiss IBAN-format profiles. Does not recognize names or validate IBAN checksums.');
  parameter('replacement','STRING','ANONYMIZED','literal string up to 100 characters','Literal replacement shared by the four profiles.');
  implementation('ORACLE',common_text('ORACLE'));
  implementation('POSTGRESQL',common_text('POSTGRESQL'));
  implementation('SQLSERVER',common_text('SQLSERVER'));
END;
/

COMMIT;
