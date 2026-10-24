-- Register SQL Server 2019-compatible profiles. Install the target helper separately.
SET DEFINE OFF

DECLARE
  v_version NUMBER;
  v_sql VARCHAR2(4000);
  PROCEDURE publish(p_code VARCHAR2,p_profile VARCHAR2) IS
  BEGIN
    SELECT av.algorithm_version_id INTO v_version FROM algorithm a
      JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id
     WHERE a.code=p_code AND av.version_number=1;
    v_sql := 'CASE WHEN ${column} IS NULL THEN NULL ELSE '
      || 'dbo.PRIVONYM_TEXT_REDACT(CONVERT(nvarchar(max),${column}),'
      || CHR(39)||p_profile||CHR(39)||',N${param.replacement}) END';
    MERGE INTO algorithm_implementation t
    USING (SELECT v_version version_id FROM dual) s
       ON (t.algorithm_version_id=s.version_id AND t.technology_code='SQLSERVER')
    WHEN MATCHED THEN UPDATE SET t.implementation_type='SQL_EXPRESSION',
      t.implementation_code=TO_CLOB(v_sql),t.code_checksum=LOWER(STANDARD_HASH(v_sql,'SHA256')),
      t.status='PUBLISHED'
    WHEN NOT MATCHED THEN INSERT(algorithm_version_id,technology_code,implementation_type,
      implementation_code,code_checksum,status,published_at)
      VALUES(v_version,'SQLSERVER','SQL_EXPRESSION',TO_CLOB(v_sql),
        LOWER(STANDARD_HASH(v_sql,'SHA256')),'PUBLISHED',SYSTIMESTAMP);
  END;
BEGIN
  publish('TEXT_REDACT_EMAIL','EMAIL');
  publish('TEXT_REDACT_PHONE_FR','PHONE_FR');
  publish('TEXT_REDACT_PHONE_CH','PHONE_CH');
  publish('TEXT_REDACT_IBAN_FR_CH','IBAN_FR_CH');
END;
/

COMMIT;
