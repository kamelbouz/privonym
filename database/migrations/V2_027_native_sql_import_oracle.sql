-- Additive catalog entries for exact SQL Server and PostgreSQL scalar expressions.
SET DEFINE OFF
DECLARE a NUMBER;v NUMBER;n NUMBER;algorithm_code VARCHAR2(100);
BEGIN
 FOR engine IN (SELECT 'SQLSERVER' technology FROM dual UNION ALL SELECT 'POSTGRESQL' FROM dual) LOOP
  algorithm_code:=engine.technology||'_EXPRESSION_EXACT';
  INSERT INTO algorithm(code,name,category,description,deterministic,reversible,preserve_null,preserve_format,preserve_length,preserve_uniqueness,status)
  SELECT algorithm_code,'Imported '||engine.technology||' expression','MASKING','Original scalar SQL expression; validated and pinned to source dialect','N','N','N','N','N','N','ACTIVE' FROM dual
  WHERE NOT EXISTS(SELECT 1 FROM algorithm WHERE code=algorithm_code);
  SELECT algorithm_id INTO a FROM algorithm WHERE code=algorithm_code;
  INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at)
  SELECT a,1,'PUBLISHED','V2_027',SYSTIMESTAMP FROM dual WHERE NOT EXISTS(SELECT 1 FROM algorithm_version WHERE algorithm_id=a AND version_number=1);
  SELECT algorithm_version_id INTO v FROM algorithm_version WHERE algorithm_id=a AND version_number=1;
  INSERT INTO algorithm_parameter_def(algorithm_version_id,parameter_name,data_type,required,description)
  SELECT v,'expression','STRING','Y','Original scalar SQL expression, validated on import and generation' FROM dual WHERE NOT EXISTS(SELECT 1 FROM algorithm_parameter_def WHERE algorithm_version_id=v AND parameter_name='expression');
  INSERT INTO algorithm_implementation(algorithm_version_id,technology_code,implementation_type,implementation_code,code_checksum,status,published_at)
  SELECT v,engine.technology,'SQL_EXPRESSION',TO_CLOB('PRIVONYM_IMPORT_RUNTIME_V27_REQUIRED(${column})'),LOWER(STANDARD_HASH('PRIVONYM_IMPORT_RUNTIME_V27_REQUIRED(${column})','SHA256')),'PUBLISHED',SYSTIMESTAMP FROM dual
  WHERE NOT EXISTS(SELECT 1 FROM algorithm_implementation WHERE algorithm_version_id=v AND technology_code=engine.technology);
  SELECT COUNT(*) INTO n FROM algorithm_implementation WHERE algorithm_version_id=v AND technology_code=engine.technology AND status='PUBLISHED' AND code_checksum=LOWER(STANDARD_HASH('PRIVONYM_IMPORT_RUNTIME_V27_REQUIRED(${column})','SHA256'));
  IF n<>1 THEN RAISE_APPLICATION_ERROR(-20027,'Existing native import implementation differs');END IF;
  SELECT COUNT(*) INTO n FROM algorithm_version WHERE algorithm_version_id=v AND status='PUBLISHED';
  IF n<>1 THEN RAISE_APPLICATION_ERROR(-20027,'Native import algorithm version is not published');END IF;
 END LOOP;
END;
/
