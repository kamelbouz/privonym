-- Install once in the PostgreSQL target database used by Privonym.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE OR REPLACE FUNCTION privonym_iban_generate(p_value text, p_key text)
RETURNS text
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    v_clean text := upper(regexp_replace(p_value, '[^A-Za-z0-9]', '', 'g'));
    v_country text;
    v_hash text;
    v_bban text := '';
    v_numeric text;
    v_remainder integer := 0;
    i integer;
BEGIN
    IF p_key IS NULL OR length(p_key) = 0 THEN
        RAISE EXCEPTION 'PRIVONYM IBAN key must not be empty';
    END IF;
    IF length(v_clean) NOT BETWEEN 15 AND 34
       OR substring(v_clean FROM 1 FOR 2) !~ '^[A-Z]{2}$' THEN
        RAISE EXCEPTION 'Source value is not a compact IBAN';
    END IF;

    v_country := substring(v_clean FROM 1 FOR 2);
    v_hash := encode(digest(convert_to(p_key || ':' || v_clean, 'UTF8'), 'sha256'), 'hex');
    FOR i IN 1..length(v_clean) - 4 LOOP
        v_bban := v_bban || ((strpos('0123456789abcdef',
            substring(v_hash FROM ((i - 1) % length(v_hash)) + 1 FOR 1)) - 1) % 10)::text;
    END LOOP;
    v_numeric := v_bban
        || (ascii(substring(v_country FROM 1 FOR 1)) - 55)::text
        || (ascii(substring(v_country FROM 2 FOR 1)) - 55)::text || '00';
    FOR i IN 1..length(v_numeric) LOOP
        v_remainder := (v_remainder * 10 + substring(v_numeric FROM i FOR 1)::integer) % 97;
    END LOOP;
    RETURN v_country || lpad((98 - v_remainder)::text, 2, '0') || v_bban;
END;
$$;

