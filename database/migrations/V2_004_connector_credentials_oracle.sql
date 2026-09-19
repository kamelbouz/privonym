SET DEFINE OFF
SET SERVEROUTPUT ON

PROMPT Privonym V2 - connector schema and encrypted local credential

DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM user_tab_columns
     WHERE table_name='CONNECTOR' AND column_name='SCHEMA_NAME';
    IF v_count=0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE connector ADD (schema_name VARCHAR2(128))';
    END IF;

    SELECT COUNT(*) INTO v_count FROM user_tab_columns
     WHERE table_name='CONNECTOR' AND column_name='ENCRYPTED_PASSWORD';
    IF v_count=0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE connector ADD (encrypted_password VARCHAR2(4000))';
    END IF;
END;
/

COMMIT;
PROMPT [OK] Connector schema and encrypted credential columns installed.
