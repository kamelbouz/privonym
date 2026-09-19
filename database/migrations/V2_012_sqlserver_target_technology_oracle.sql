-- Enables Microsoft SQL Server as a Privonym target while the repository remains Oracle.
SET DEFINE OFF

UPDATE database_technology
   SET enabled='Y',adapter_key='sqlserver'
 WHERE technology_code='SQLSERVER';

DECLARE
  PROCEDURE ensure_implementation(p_code VARCHAR2,p_type VARCHAR2,p_template VARCHAR2) IS
  BEGIN
    INSERT INTO algorithm_implementation(algorithm_version_id,technology_code,
      implementation_type,implementation_code,code_checksum,status,published_at)
    SELECT av.algorithm_version_id,'SQLSERVER',p_type,TO_CLOB(p_template),
      LOWER(STANDARD_HASH(p_template,'SHA256')),'PUBLISHED',SYSTIMESTAMP
      FROM algorithm a JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id
     WHERE a.code=p_code AND av.status='PUBLISHED'
       AND av.version_number=(SELECT MAX(x.version_number) FROM algorithm_version x
                               WHERE x.algorithm_id=a.algorithm_id AND x.status='PUBLISHED')
       AND NOT EXISTS(SELECT 1 FROM algorithm_implementation i
                       WHERE i.algorithm_version_id=av.algorithm_version_id
                         AND i.technology_code='SQLSERVER');
  END;
BEGIN
  ensure_implementation('NULLIFY','SQL_EXPRESSION','NULL');
  ensure_implementation('ZERO_NUMBER','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE 0 END~');
  ensure_implementation('MASK_STRING','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE REPLICATE(${param.mask_character},LEN(CONVERT(nvarchar(max),${column}))) END~');
  ensure_implementation('MASK_PAYMENT_CARD','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL WHEN ${param.visible_suffix_length}=0 THEN REPLICATE('*',LEN(CONVERT(nvarchar(max),${column}))) ELSE REPLICATE('*',CASE WHEN LEN(CONVERT(nvarchar(max),${column}))>${param.visible_suffix_length} THEN LEN(CONVERT(nvarchar(max),${column}))-${param.visible_suffix_length} ELSE 0 END)+RIGHT(CONVERT(nvarchar(max),${column}),${param.visible_suffix_length}) END~');
  ensure_implementation('GENERALIZE_DATE_YEAR','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE DATEFROMPARTS(YEAR(${column}),${param.month_number},${param.day_number}) END~');
  ensure_implementation('GENERALIZE_DATE_MONTH','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE DATEFROMPARTS(YEAR(${column}),MONTH(${column}),${param.day_number}) END~');
  ensure_implementation('HASH_SHA256','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE LOWER(CONVERT(varchar(64),HASHBYTES('SHA2_256',CONVERT(nvarchar(max),${column})),2)) END~');
  ensure_implementation('RANDOM_LOWER_STRING','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE LOWER(LEFT(REPLICATE(REPLACE(CONVERT(varchar(36),NEWID()),'-',''),CEILING(LEN(CONVERT(nvarchar(max),${column}))/32.0)),LEN(CONVERT(nvarchar(max),${column})))) END~');
  ensure_implementation('RANDOM_UPPER_STRING','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE UPPER(LEFT(REPLICATE(REPLACE(CONVERT(varchar(36),NEWID()),'-',''),CEILING(LEN(CONVERT(nvarchar(max),${column}))/32.0)),LEN(CONVERT(nvarchar(max),${column})))) END~');
  ensure_implementation('RANDOM_EMAIL_LOCAL_PART','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE LEFT(REPLACE(CONVERT(varchar(36),NEWID()),'-',''),${param.local_part_length})+CASE WHEN CHARINDEX('@',CONVERT(nvarchar(max),${column}))>1 THEN SUBSTRING(CONVERT(nvarchar(max),${column}),CHARINDEX('@',CONVERT(nvarchar(max),${column})),LEN(CONVERT(nvarchar(max),${column}))) ELSE '@invalid.example' END END~');
  ensure_implementation('RANDOM_PHONE_FR','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE ${param.prefix}+RIGHT('00000000'+CONVERT(varchar(8),ABS(CONVERT(bigint,CHECKSUM(NEWID())))%100000000),8) END~');
  ensure_implementation('PERTURB_NUMBER_PERCENT','SQL_EXPRESSION',q'~CASE WHEN ${column} IS NULL THEN NULL ELSE ROUND(${column}*(1+((RAND(CHECKSUM(NEWID()))*2-1)*${param.max_ratio})),${param.scale}) END~');
  ensure_implementation('TRUNCATE_TABLE','PLSQL_BLOCK',q'~TRUNCATE TABLE ${qualified_table}~');
  ensure_implementation('DELETE_TABLE_ROWS','PLSQL_BLOCK',q'~DELETE FROM ${qualified_table}~');
  ensure_implementation('LOOKUP_FROM_DATASET','LOOKUP_MAPPING','LOOKUP_DATASET');
  ensure_implementation('LOOKUP_LAST_NAME','LOOKUP_MAPPING','LOOKUP_DATASET');
  ensure_implementation('LOOKUP_FIRST_NAME','LOOKUP_MAPPING','LOOKUP_DATASET');
  ensure_implementation('LOOKUP_CITY','LOOKUP_MAPPING','LOOKUP_DATASET');
  ensure_implementation('LOOKUP_ADDRESS','LOOKUP_MAPPING','LOOKUP_DATASET');
END;
/

COMMIT;
