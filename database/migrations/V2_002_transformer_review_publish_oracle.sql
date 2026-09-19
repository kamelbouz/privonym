--------------------------------------------------------------------------------
-- Privonym V2 - Transformer review and publication status
-- Run after privonym_v2_migration_transformer_masking_inventory_oracle.sql
--------------------------------------------------------------------------------

DECLARE
    v_table_count NUMBER;
    v_constraint_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_table_count
      FROM user_tables
     WHERE table_name = 'TRANSFORMATION_IMPORT';

    IF v_table_count = 0 THEN
        DBMS_OUTPUT.PUT_LINE('[SKIP] TRANSFORMATION_IMPORT is not installed in this repository');
    ELSE
        SELECT COUNT(*) INTO v_constraint_count
          FROM user_constraints
         WHERE table_name = 'TRANSFORMATION_IMPORT'
           AND constraint_name = 'TRANSFORMATION_IMPORT_STATUS_CK';

        IF v_constraint_count > 0 THEN
            EXECUTE IMMEDIATE
                'ALTER TABLE transformation_import DROP CONSTRAINT transformation_import_status_ck';
        END IF;

        EXECUTE IMMEDIATE q'~
            ALTER TABLE transformation_import
            ADD CONSTRAINT transformation_import_status_ck CHECK (
                status IN (
                    'UPLOADED','ANALYZED','REVIEW_REQUIRED','DRAFT_CREATED',
                    'PUBLISHED','FAILED','CANCELLED'
                )
            )
        ~';
        DBMS_OUTPUT.PUT_LINE('[OK] TRANSFORMATION_IMPORT status constraint upgraded');
    END IF;
END;
/

PROMPT Privonym V2 Transformer review/publication migration installed successfully.
