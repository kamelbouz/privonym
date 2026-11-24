-- Compatibility functions for repository statements only, not target masking SQL.
CREATE VIEW dual AS SELECT 'X'::text AS dummy;
CREATE VIEW user_tables AS
 SELECT upper(table_name) AS table_name
 FROM information_schema.tables WHERE table_schema=current_schema() AND table_type='BASE TABLE';
CREATE VIEW user_tab_columns AS
 SELECT upper(table_name) AS table_name,upper(column_name) AS column_name
 FROM information_schema.columns WHERE table_schema=current_schema();
CREATE VIEW user_objects AS
 SELECT upper(table_name) AS object_name,
 CASE table_type WHEN 'BASE TABLE' THEN 'TABLE' ELSE 'VIEW' END AS object_type
 FROM information_schema.tables WHERE table_schema=current_schema();
CREATE FUNCTION nvl(anycompatible,anycompatible) RETURNS anycompatible
 LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT coalesce($1,$2) $$;
CREATE FUNCTION to_clob(text) RETURNS text
 LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1 $$;
CREATE FUNCTION numtodsinterval(numeric,text) RETURNS interval
 LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
 SELECT CASE upper($2)
 WHEN 'SECOND' THEN $1*interval '1 second'
 WHEN 'MINUTE' THEN $1*interval '1 minute'
 WHEN 'HOUR' THEN $1*interval '1 hour'
 WHEN 'DAY' THEN $1*interval '1 day'
 ELSE NULL END $$;
CREATE FUNCTION privonym_lob_substr(text,integer,integer) RETURNS text
 LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$ SELECT substr($1,$3,$2) $$;
CREATE FUNCTION privonym_to_char(timestamptz,text) RETURNS text
 LANGUAGE sql STABLE STRICT PARALLEL SAFE AS $$
 SELECT to_char($1,replace(replace(replace($2,'FF9','US'),'FF6','US'),'FF3','MS')) $$;
CREATE FUNCTION privonym_to_char(timestamp,text) RETURNS text
 LANGUAGE sql STABLE STRICT PARALLEL SAFE AS $$
 SELECT to_char($1,replace(replace(replace($2,'FF9','US'),'FF6','US'),'FF3','MS')) $$;
