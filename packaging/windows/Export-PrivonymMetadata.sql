SET SERVEROUTPUT ON SIZE UNLIMITED FORMAT WRAPPED
SET FEEDBACK OFF HEADING OFF PAGESIZE 0 LINESIZE 32767 TRIMSPOOL ON VERIFY OFF
SPOOL &1
PROMPT -- Privonym system metadata seed
PROMPT -- Contains product-owned reference data only. No environment, connection, catalog, ruleset or job data.
PROMPT SET DEFINE OFF
PROMPT WHENEVER SQLERROR EXIT SQL.SQLCODE

DECLARE
  FUNCTION lit(p_value VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    IF p_value IS NULL THEN RETURN 'NULL'; END IF;
    RETURN '''' || REPLACE(REPLACE(REPLACE(p_value, '''', ''''''), CHR(13), ''), CHR(10), '''||CHR(10)||''') || '''';
  END;
  FUNCTION clob_lit(p_value CLOB) RETURN VARCHAR2 IS
  BEGIN
    IF p_value IS NULL THEN RETURN 'NULL'; END IF;
    RETURN 'TO_CLOB(' || lit(DBMS_LOB.SUBSTR(p_value, 30000, 1)) || ')';
  END;
BEGIN
  FOR r IN (SELECT * FROM database_technology ORDER BY technology_code) LOOP
    DBMS_OUTPUT.PUT_LINE('INSERT INTO database_technology(technology_code,name,adapter_key,enabled) VALUES ('||lit(r.technology_code)||','||lit(r.name)||','||lit(r.adapter_key)||','||lit(r.enabled)||');');
  END LOOP;

  FOR r IN (SELECT * FROM sensitive_data_type ORDER BY sensitive_type_id) LOOP
    DBMS_OUTPUT.PUT_LINE('INSERT INTO sensitive_data_type(sensitive_type_id,code,name,description,risk_level,enabled) VALUES ('||r.sensitive_type_id||','||lit(r.code)||','||lit(r.name)||','||lit(r.description)||','||lit(r.risk_level)||','||lit(r.enabled)||');');
  END LOOP;

  FOR r IN (SELECT * FROM algorithm WHERE code IN (
      'DELETE_TABLE_ROWS','GENERALIZE_DATE_MONTH','GENERALIZE_DATE_YEAR','HASH_SHA256',
      'LOOKUP_ADDRESS','LOOKUP_CITY','LOOKUP_FIRST_NAME','LOOKUP_FROM_DATASET','LOOKUP_LAST_NAME',
      'MASK_PAYMENT_CARD','MASK_STRING','NULLIFY','PERTURB_NUMBER_PERCENT','RANDOM_EMAIL_LOCAL_PART',
      'RANDOM_LOWER_STRING','RANDOM_PHONE_FR','RANDOM_UPPER_STRING','TRUNCATE_TABLE','ZERO_NUMBER') ORDER BY algorithm_id) LOOP
    DBMS_OUTPUT.PUT_LINE('INSERT INTO algorithm(algorithm_id,code,name,category,description,deterministic,reversible,preserve_null,preserve_format,preserve_length,preserve_uniqueness,status) VALUES ('||r.algorithm_id||','||lit(r.code)||','||lit(r.name)||','||lit(r.category)||','||lit(r.description)||','||lit(r.deterministic)||','||lit(r.reversible)||','||lit(r.preserve_null)||','||lit(r.preserve_format)||','||lit(r.preserve_length)||','||lit(r.preserve_uniqueness)||','||lit(r.status)||');');
  END LOOP;

  FOR r IN (SELECT av.* FROM algorithm_version av JOIN algorithm a ON a.algorithm_id=av.algorithm_id WHERE a.code IN (
      'DELETE_TABLE_ROWS','GENERALIZE_DATE_MONTH','GENERALIZE_DATE_YEAR','HASH_SHA256','LOOKUP_ADDRESS','LOOKUP_CITY',
      'LOOKUP_FIRST_NAME','LOOKUP_FROM_DATASET','LOOKUP_LAST_NAME','MASK_PAYMENT_CARD','MASK_STRING','NULLIFY',
      'PERTURB_NUMBER_PERCENT','RANDOM_EMAIL_LOCAL_PART','RANDOM_LOWER_STRING','RANDOM_PHONE_FR','RANDOM_UPPER_STRING',
      'TRUNCATE_TABLE','ZERO_NUMBER') ORDER BY av.algorithm_version_id) LOOP
    DBMS_OUTPUT.PUT_LINE('INSERT INTO algorithm_version(algorithm_version_id,algorithm_id,version_number,status,created_by,published_at) VALUES ('||r.algorithm_version_id||','||r.algorithm_id||','||r.version_number||','||lit(r.status)||','||lit(NVL(r.created_by,'PRIVONYM_INSTALLER'))||','||CASE WHEN r.published_at IS NULL THEN 'NULL' ELSE 'SYSTIMESTAMP' END||');');
  END LOOP;

  FOR r IN (SELECT ai.* FROM algorithm_implementation ai JOIN algorithm_version av ON av.algorithm_version_id=ai.algorithm_version_id JOIN algorithm a ON a.algorithm_id=av.algorithm_id WHERE a.code IN (
      'DELETE_TABLE_ROWS','GENERALIZE_DATE_MONTH','GENERALIZE_DATE_YEAR','HASH_SHA256','LOOKUP_ADDRESS','LOOKUP_CITY',
      'LOOKUP_FIRST_NAME','LOOKUP_FROM_DATASET','LOOKUP_LAST_NAME','MASK_PAYMENT_CARD','MASK_STRING','NULLIFY',
      'PERTURB_NUMBER_PERCENT','RANDOM_EMAIL_LOCAL_PART','RANDOM_LOWER_STRING','RANDOM_PHONE_FR','RANDOM_UPPER_STRING',
      'TRUNCATE_TABLE','ZERO_NUMBER') ORDER BY ai.implementation_id) LOOP
    DBMS_OUTPUT.PUT_LINE('INSERT INTO algorithm_implementation(implementation_id,algorithm_version_id,technology_code,implementation_type,implementation_code,code_checksum,status,published_at) VALUES ('||r.implementation_id||','||r.algorithm_version_id||','||lit(r.technology_code)||','||lit(r.implementation_type)||','||clob_lit(r.implementation_code)||','||lit(r.code_checksum)||','||lit(r.status)||','||CASE WHEN r.published_at IS NULL THEN 'NULL' ELSE 'SYSTIMESTAMP' END||');');
  END LOOP;

  FOR r IN (SELECT p.* FROM algorithm_parameter_def p JOIN algorithm_version av ON av.algorithm_version_id=p.algorithm_version_id JOIN algorithm a ON a.algorithm_id=av.algorithm_id WHERE a.code IN (
      'DELETE_TABLE_ROWS','GENERALIZE_DATE_MONTH','GENERALIZE_DATE_YEAR','HASH_SHA256','LOOKUP_ADDRESS','LOOKUP_CITY',
      'LOOKUP_FIRST_NAME','LOOKUP_FROM_DATASET','LOOKUP_LAST_NAME','MASK_PAYMENT_CARD','MASK_STRING','NULLIFY',
      'PERTURB_NUMBER_PERCENT','RANDOM_EMAIL_LOCAL_PART','RANDOM_LOWER_STRING','RANDOM_PHONE_FR','RANDOM_UPPER_STRING',
      'TRUNCATE_TABLE','ZERO_NUMBER') ORDER BY p.parameter_definition_id) LOOP
    DBMS_OUTPUT.PUT_LINE('INSERT INTO algorithm_parameter_def(parameter_definition_id,algorithm_version_id,parameter_name,data_type,required,default_value,validation_expression,description) VALUES ('||r.parameter_definition_id||','||r.algorithm_version_id||','||lit(r.parameter_name)||','||lit(r.data_type)||','||lit(r.required)||','||lit(r.default_value)||','||lit(r.validation_expression)||','||lit(r.description)||');');
  END LOOP;

  FOR r IN (SELECT DISTINCT d.* FROM lookup_dataset d JOIN algorithm_lookup_config c ON c.lookup_dataset_id=d.lookup_dataset_id JOIN algorithm_version av ON av.algorithm_version_id=c.algorithm_version_id JOIN algorithm a ON a.algorithm_id=av.algorithm_id WHERE a.code IN ('LOOKUP_ADDRESS','LOOKUP_CITY','LOOKUP_FIRST_NAME','LOOKUP_LAST_NAME') ORDER BY d.lookup_dataset_id) LOOP
    DBMS_OUTPUT.PUT_LINE('INSERT INTO lookup_dataset(lookup_dataset_id,code,name,category,locale_code,version_number,status,published_at) VALUES ('||r.lookup_dataset_id||','||lit(r.code)||','||lit(r.name)||','||lit(r.category)||','||lit(r.locale_code)||','||r.version_number||','||lit(r.status)||','||CASE WHEN r.published_at IS NULL THEN 'NULL' ELSE 'SYSTIMESTAMP' END||');');
  END LOOP;

  FOR r IN (SELECT v.* FROM lookup_dataset_value v WHERE EXISTS (SELECT 1 FROM algorithm_lookup_config c JOIN algorithm_version av ON av.algorithm_version_id=c.algorithm_version_id JOIN algorithm a ON a.algorithm_id=av.algorithm_id WHERE c.lookup_dataset_id=v.lookup_dataset_id AND a.code IN ('LOOKUP_ADDRESS','LOOKUP_CITY','LOOKUP_FIRST_NAME','LOOKUP_LAST_NAME')) ORDER BY v.lookup_value_id) LOOP
    DBMS_OUTPUT.PUT_LINE('INSERT INTO lookup_dataset_value(lookup_value_id,lookup_dataset_id,value_text,weight_value,attributes_json,enabled) VALUES ('||r.lookup_value_id||','||r.lookup_dataset_id||','||lit(r.value_text)||','||TO_CHAR(r.weight_value,'TM9','NLS_NUMERIC_CHARACTERS=''.,''')||','||clob_lit(r.attributes_json)||','||lit(r.enabled)||');');
  END LOOP;

  FOR r IN (SELECT c.* FROM algorithm_lookup_config c JOIN algorithm_version av ON av.algorithm_version_id=c.algorithm_version_id JOIN algorithm a ON a.algorithm_id=av.algorithm_id WHERE a.code IN ('LOOKUP_ADDRESS','LOOKUP_CITY','LOOKUP_FIRST_NAME','LOOKUP_LAST_NAME') ORDER BY c.algorithm_version_id) LOOP
    DBMS_OUTPUT.PUT_LINE('INSERT INTO algorithm_lookup_config(algorithm_version_id,lookup_dataset_id) VALUES ('||r.algorithm_version_id||','||r.lookup_dataset_id||');');
  END LOOP;
  DBMS_OUTPUT.PUT_LINE('COMMIT;');
END;
/
SPOOL OFF
