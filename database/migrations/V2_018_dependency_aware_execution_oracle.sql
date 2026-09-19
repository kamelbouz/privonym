-- Privonym V2.018 - dependency-aware table execution metadata.
-- Self-referencing foreign keys must be catalogued so the planner can report them.
BEGIN
    EXECUTE IMMEDIATE
        'ALTER TABLE catalog_relationship DROP CONSTRAINT catalog_relationship_self_ck';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE <> -2443 THEN
            RAISE;
        END IF;
END;
/
