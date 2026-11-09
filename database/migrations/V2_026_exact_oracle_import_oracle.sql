-- Exact scalar Oracle expressions imported from reviewed SQL. No source script is executed.
SET DEFINE OFF
DECLARE a NUMBER;v NUMBER;n NUMBER;
BEGIN
 INSERT INTO algorithm(code,name,category,description,deterministic,reversible,preserve_null,preserve_format,preserve_length,preserve_uniqueness,status)
 SELECT 'ORACLE_EXPRESSION_EXACT','Imported Oracle expression','MASKING','Restricted original scalar SQL expression; Oracle only, no heuristic replacement','N','N','N','N','N','N','ACTIVE' FROM dual
 WHERE NOT EXISTS(SELECT 1 FROM algorithm WHERE code='ORACLE_EXPRESSION_EXACT');
 SELECT algorithm_id INTO a FROM algorithm WHERE code='ORACLE_EXPRESSION_EXACT';
 INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at)
 SELECT a,1,'PUBLISHED','V2_026',SYSTIMESTAMP FROM dual WHERE NOT EXISTS(SELECT 1 FROM algorithm_version WHERE algorithm_id=a AND version_number=1);
 SELECT algorithm_version_id INTO v FROM algorithm_version WHERE algorithm_id=a AND version_number=1;
 INSERT INTO algorithm_parameter_def(algorithm_version_id,parameter_name,data_type,required,description)
 SELECT v,'expression','STRING','Y','Original scalar Oracle expression, validated on import and plan generation' FROM dual WHERE NOT EXISTS(SELECT 1 FROM algorithm_parameter_def WHERE algorithm_version_id=v AND parameter_name='expression');
 INSERT INTO algorithm_implementation(algorithm_version_id,technology_code,implementation_type,implementation_code,code_checksum,status,published_at)
 SELECT v,'ORACLE','SQL_EXPRESSION',TO_CLOB('PRIVONYM_IMPORT_RUNTIME_V26_REQUIRED(${column})'),LOWER(STANDARD_HASH('PRIVONYM_IMPORT_RUNTIME_V26_REQUIRED(${column})','SHA256')),'PUBLISHED',SYSTIMESTAMP FROM dual
 WHERE NOT EXISTS(SELECT 1 FROM algorithm_implementation WHERE algorithm_version_id=v AND technology_code='ORACLE');
 SELECT COUNT(*) INTO n FROM algorithm_implementation WHERE algorithm_version_id=v AND technology_code='ORACLE' AND status='PUBLISHED' AND code_checksum=LOWER(STANDARD_HASH('PRIVONYM_IMPORT_RUNTIME_V26_REQUIRED(${column})','SHA256'));
 IF n<>1 THEN RAISE_APPLICATION_ERROR(-20026,'Existing exact import implementation differs; migration refused');END IF;
 SELECT COUNT(*) INTO n FROM algorithm_version WHERE algorithm_version_id=v AND status='PUBLISHED';
 IF n<>1 THEN RAISE_APPLICATION_ERROR(-20026,'Exact import algorithm version is not published');END IF;
END;
/
