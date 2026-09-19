-- Realign Oracle identity generators after product metadata is seeded with explicit keys.
SET DEFINE OFF

DECLARE
  v_sql VARCHAR2(4000);
BEGIN
  FOR r IN (
    SELECT table_name, column_name, generation_type
      FROM user_tab_identity_cols
     ORDER BY table_name, column_name
  ) LOOP
    v_sql := 'ALTER TABLE "' || REPLACE(r.table_name, '"', '""')
          || '" MODIFY "' || REPLACE(r.column_name, '"', '""')
          || '" GENERATED ' || r.generation_type
          || ' AS IDENTITY (START WITH LIMIT VALUE)';
    EXECUTE IMMEDIATE v_sql;
  END LOOP;
END;
/
