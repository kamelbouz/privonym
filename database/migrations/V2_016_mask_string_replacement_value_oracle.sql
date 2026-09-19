-- Publishes MASK_STRING v2: replace the complete non-null source string with
-- a value selected by the Rule Set creator. Existing rules keep their v1 link.
SET DEFINE OFF

DECLARE
  v_algorithm_id NUMBER;
  v_version_id NUMBER;
  v_version_number NUMBER;

  PROCEDURE upsert_implementation(
      p_technology VARCHAR2,
      p_sql VARCHAR2) IS
  BEGIN
    UPDATE algorithm_implementation
       SET implementation_type='SQL_EXPRESSION',
           implementation_code=TO_CLOB(p_sql),
           code_checksum=LOWER(STANDARD_HASH(p_sql,'SHA256')),
           status='PUBLISHED',
           published_at=NVL(published_at,SYSTIMESTAMP)
     WHERE algorithm_version_id=v_version_id
       AND technology_code=p_technology;

    IF SQL%ROWCOUNT=0 THEN
      INSERT INTO algorithm_implementation(
          algorithm_version_id,technology_code,implementation_type,
          implementation_code,code_checksum,status,published_at)
      VALUES (v_version_id,p_technology,'SQL_EXPRESSION',TO_CLOB(p_sql),
          LOWER(STANDARD_HASH(p_sql,'SHA256')),'PUBLISHED',SYSTIMESTAMP);
    END IF;
  END;
BEGIN
  SELECT algorithm_id INTO v_algorithm_id
    FROM algorithm WHERE code='MASK_STRING';

  UPDATE algorithm
     SET name='Replace with configured string',
         description='Replaces the complete non-null source string with a value configured in the Rule Set.',
         deterministic='Y',reversible='N',preserve_null='Y',
         preserve_format='N',preserve_length='N',preserve_uniqueness='N',
         status='ACTIVE'
   WHERE algorithm_id=v_algorithm_id;

  BEGIN
    SELECT av.algorithm_version_id INTO v_version_id
      FROM algorithm_version av
      JOIN algorithm_parameter_def apd
        ON apd.algorithm_version_id=av.algorithm_version_id
     WHERE av.algorithm_id=v_algorithm_id
       AND apd.parameter_name='replacement_value'
     ORDER BY av.version_number DESC
     FETCH FIRST 1 ROW ONLY;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      SELECT NVL(MAX(version_number),0)+1 INTO v_version_number
        FROM algorithm_version WHERE algorithm_id=v_algorithm_id;
      INSERT INTO algorithm_version(
          algorithm_id,version_number,status,created_by,published_at)
      VALUES (v_algorithm_id,v_version_number,'PUBLISHED','V2_016',SYSTIMESTAMP)
      RETURNING algorithm_version_id INTO v_version_id;
  END;

  INSERT INTO algorithm_parameter_def(
      algorithm_version_id,parameter_name,data_type,required,default_value,
      validation_expression,description)
  SELECT v_version_id,'replacement_value','STRING','Y',NULL,
      'non-empty string up to 4000 characters',
      'Complete replacement value selected by the Rule Set creator.'
    FROM dual
   WHERE NOT EXISTS (
      SELECT 1 FROM algorithm_parameter_def
       WHERE algorithm_version_id=v_version_id
         AND parameter_name='replacement_value');

  upsert_implementation('ORACLE',
      q'~CASE WHEN ${column} IS NULL THEN NULL ELSE ${param.replacement_value} END~');
  upsert_implementation('POSTGRESQL',
      q'~CASE WHEN ${column} IS NULL THEN NULL ELSE ${param.replacement_value} END~');
  upsert_implementation('SQLSERVER',
      q'~CASE WHEN ${column} IS NULL THEN NULL ELSE ${param.replacement_value} END~');
END;
/

COMMIT;
