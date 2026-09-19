-- Install once in the Oracle target schema used by Privonym.
CREATE OR REPLACE FUNCTION PRIVONYM_IBAN_GENERATE(
    p_value IN VARCHAR2,
    p_key   IN VARCHAR2
) RETURN VARCHAR2 DETERMINISTIC AUTHID DEFINER IS
    v_clean    VARCHAR2(128) := REGEXP_REPLACE(UPPER(p_value), '[^A-Z0-9]', '');
    v_country  VARCHAR2(2);
    v_hash     VARCHAR2(64);
    v_bban     VARCHAR2(128) := '';
    v_numeric  VARCHAR2(256);
    v_result   VARCHAR2(128);
    v_remainder PLS_INTEGER := 0;
    v_digit    PLS_INTEGER;
BEGIN
    IF p_key IS NULL OR LENGTH(p_key) = 0 THEN
        RAISE_APPLICATION_ERROR(-20001, 'PRIVONYM IBAN key must not be empty');
    END IF;
    IF LENGTH(v_clean) NOT BETWEEN 15 AND 34
       OR NOT REGEXP_LIKE(SUBSTR(v_clean, 1, 2), '^[A-Z]{2}$') THEN
        RAISE_APPLICATION_ERROR(-20002, 'Source value is not a compact IBAN');
    END IF;

    v_country := SUBSTR(v_clean, 1, 2);
    SELECT LOWER(RAWTOHEX(STANDARD_HASH(p_key || ':' || v_clean, 'SHA256')))
      INTO v_hash FROM dual;
    FOR i IN 1 .. LENGTH(v_clean) - 4 LOOP
        v_digit := MOD(INSTR('0123456789abcdef',
            SUBSTR(v_hash, MOD(i - 1, LENGTH(v_hash)) + 1, 1)) - 1, 10);
        v_bban := v_bban || TO_CHAR(v_digit);
    END LOOP;

    v_numeric := v_bban
        || TO_CHAR(ASCII(SUBSTR(v_country, 1, 1)) - 55)
        || TO_CHAR(ASCII(SUBSTR(v_country, 2, 1)) - 55) || '00';
    FOR i IN 1 .. LENGTH(v_numeric) LOOP
        v_remainder := MOD(v_remainder * 10 + TO_NUMBER(SUBSTR(v_numeric, i, 1)), 97);
    END LOOP;
    v_result := v_country || LPAD(TO_CHAR(98 - v_remainder), 2, '0') || v_bban;
    RETURN v_result;
END;
/

