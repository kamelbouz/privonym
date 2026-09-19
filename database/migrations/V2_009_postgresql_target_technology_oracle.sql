-- Enables PostgreSQL as a target technology and publishes PostgreSQL SQL templates.
-- The Privonym repository remains Oracle.
UPDATE database_technology
   SET enabled='Y',adapter_key='postgresql'
 WHERE technology_code='POSTGRESQL';

DECLARE
  PROCEDURE publish(p_code VARCHAR2,p_type VARCHAR2,p_template VARCHAR2) IS
  BEGIN
    INSERT INTO algorithm_implementation
      (algorithm_version_id,technology_code,implementation_type,
       implementation_code,code_checksum,status,published_at)
    SELECT av.algorithm_version_id,'POSTGRESQL',p_type,TO_CLOB(p_template),
           LOWER(STANDARD_HASH(p_template,'SHA256')),'PUBLISHED',SYSTIMESTAMP
      FROM algorithm a JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id
     WHERE a.code=p_code AND av.status='PUBLISHED'
       AND av.version_number=(SELECT MAX(x.version_number) FROM algorithm_version x
                              WHERE x.algorithm_id=a.algorithm_id AND x.status='PUBLISHED')
       AND NOT EXISTS (SELECT 1 FROM algorithm_implementation i
                        WHERE i.algorithm_version_id=av.algorithm_version_id
                          AND i.technology_code='POSTGRESQL');
  END;
BEGIN
  publish('NULLIFY','SQL_EXPRESSION','NULL');
  publish('ZERO_NUMBER','SQL_EXPRESSION','CASE WHEN ${column} IS NULL THEN NULL ELSE 0 END');
  publish('MASK_STRING','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE REGEXP_REPLACE(CAST(${column} AS TEXT), ''[[:alnum:]]'', ${param.mask_character}, ''g'') END~');
  publish('MASK_PAYMENT_CARD','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL WHEN ${param.visible_suffix_length}=0 THEN REGEXP_REPLACE(CAST(${column} AS TEXT), ''[[:digit:]]'', ''*'', ''g'') ELSE REGEXP_REPLACE(LEFT(CAST(${column} AS TEXT),GREATEST(LENGTH(CAST(${column} AS TEXT))-${param.visible_suffix_length},0)),''[[:digit:]]'',''*'',''g'')||RIGHT(CAST(${column} AS TEXT),LEAST(${param.visible_suffix_length},LENGTH(CAST(${column} AS TEXT)))) END~');
  publish('GENERALIZE_DATE_YEAR','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE MAKE_DATE(EXTRACT(YEAR FROM ${column})::INT,${param.month_number},${param.day_number}) END~');
  publish('GENERALIZE_DATE_MONTH','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE (DATE_TRUNC(''month'',${column})+(${param.day_number}-1)*INTERVAL ''1 day'')::DATE END~');
  publish('HASH_SHA256','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE ENCODE(SHA256(CONVERT_TO(CAST(${column} AS TEXT),''UTF8'')),''hex'') END~');
  publish('RANDOM_LOWER_STRING','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE LOWER(SUBSTRING(REPEAT(MD5(RANDOM()::TEXT),CEIL(LENGTH(CAST(${column} AS TEXT))/32.0)::INT),1,LENGTH(CAST(${column} AS TEXT)))) END~');
  publish('RANDOM_UPPER_STRING','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE UPPER(SUBSTRING(REPEAT(MD5(RANDOM()::TEXT),CEIL(LENGTH(CAST(${column} AS TEXT))/32.0)::INT),1,LENGTH(CAST(${column} AS TEXT)))) END~');
  publish('RANDOM_EMAIL_LOCAL_PART','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE SUBSTRING(MD5(RANDOM()::TEXT),1,${param.local_part_length})||CASE WHEN STRPOS(CAST(${column} AS TEXT),''@'')>1 THEN SUBSTRING(CAST(${column} AS TEXT) FROM STRPOS(CAST(${column} AS TEXT),''@'')) ELSE ''@invalid.example'' END END~');
  publish('RANDOM_PHONE_FR','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE ${param.prefix}||LPAD(FLOOR(RANDOM()*100000000)::BIGINT::TEXT,8,''0'') END~');
  publish('PERTURB_NUMBER_PERCENT','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE ROUND((${column}*(1+(RANDOM()*2-1)*${param.max_ratio}))::NUMERIC,${param.scale}) END~');
  publish('TRUNCATE_TABLE','PLSQL_BLOCK','TRUNCATE TABLE ${qualified_table}');
  publish('DELETE_TABLE_ROWS','PLSQL_BLOCK','DELETE FROM ${qualified_table}');
  publish('LOOKUP_FROM_DATASET','LOOKUP_MAPPING','LOOKUP_DATASET');
  publish('LOOKUP_LAST_NAME','LOOKUP_MAPPING','LOOKUP_DATASET');
  publish('LOOKUP_FIRST_NAME','LOOKUP_MAPPING','LOOKUP_DATASET');
  publish('LOOKUP_CITY','LOOKUP_MAPPING','LOOKUP_DATASET');
  publish('LOOKUP_ADDRESS','LOOKUP_MAPPING','LOOKUP_DATASET');
END;
/

COMMIT;
