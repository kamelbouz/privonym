-- Retains historical column metadata when a renamed column reuses its ordinal position.
SET DEFINE OFF

BEGIN
  EXECUTE IMMEDIATE
    'ALTER TABLE catalog_column DROP CONSTRAINT catalog_column_position_uk';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE <> -2443 THEN RAISE; END IF;
END;
/

BEGIN
  EXECUTE IMMEDIATE
    'CREATE INDEX catalog_column_position_ix ON catalog_column(catalog_table_id,ordinal_position)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE <> -955 THEN RAISE; END IF;
END;
/

COMMIT;
