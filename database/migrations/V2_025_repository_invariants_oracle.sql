-- Non-destructive invariants. Historical Rule Sets and their Jobs are retained.
SET DEFINE OFF
DECLARE n NUMBER;
BEGIN
 SELECT COUNT(*) INTO n FROM user_constraints WHERE constraint_name='RULESET_SOURCE_IDENTITY_UK';
 IF n=0 THEN EXECUTE IMMEDIATE 'ALTER TABLE ruleset ADD CONSTRAINT ruleset_source_identity_uk UNIQUE(ruleset_id,data_source_id)';END IF;
 SELECT COUNT(*) INTO n FROM user_tables WHERE table_name='CONNECTION_RULESET_BINDING';
 IF n=0 THEN
  EXECUTE IMMEDIATE 'CREATE TABLE connection_ruleset_binding(data_source_id NUMBER PRIMARY KEY,ruleset_id NUMBER NOT NULL UNIQUE,chosen_by VARCHAR2(128 CHAR) NOT NULL,chosen_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,CONSTRAINT connection_ruleset_source_fk FOREIGN KEY(ruleset_id,data_source_id) REFERENCES ruleset(ruleset_id,data_source_id))';
 END IF;
END;
/
-- Backfill only unambiguous sources. No automatic choice among historical duplicates.
INSERT INTO connection_ruleset_binding(data_source_id,ruleset_id,chosen_by)
SELECT data_source_id,MIN(ruleset_id),'V2_025_UNAMBIGUOUS' FROM ruleset r
 WHERE NOT EXISTS(SELECT 1 FROM connection_ruleset_binding b WHERE b.data_source_id=r.data_source_id)
 GROUP BY data_source_id HAVING COUNT(*)=1;
DECLARE n NUMBER;
BEGIN
 SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name='RULESET_ONE_DRAFT_UK';
 IF n=0 THEN EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX ruleset_one_draft_uk ON ruleset_version(CASE WHEN status=''DRAFT'' THEN ruleset_id END)';END IF;
END;
/
CREATE OR REPLACE TRIGGER ruleset_source_guard
FOR INSERT OR UPDATE OF data_source_id ON ruleset
COMPOUND TRIGGER
 sources SYS.ODCINUMBERLIST:=SYS.ODCINUMBERLIST();
 BEFORE EACH ROW IS n NUMBER;
 BEGIN
  SELECT data_source_id INTO n FROM data_source WHERE data_source_id=:NEW.data_source_id FOR UPDATE;
  sources.EXTEND;sources(sources.COUNT):=:NEW.data_source_id;
 END BEFORE EACH ROW;
 AFTER STATEMENT IS n NUMBER;
 BEGIN
  FOR i IN 1..sources.COUNT LOOP
   SELECT COUNT(*) INTO n FROM ruleset WHERE data_source_id=sources(i);
   IF n>1 THEN RAISE_APPLICATION_ERROR(-20071,'Connection already has a Rule Set; create a new version of its current Rule Set');END IF;
  END LOOP;
 END AFTER STATEMENT;
END;
/
CREATE OR REPLACE TRIGGER lookup_values_frozen_guard
BEFORE INSERT OR UPDATE OR DELETE ON lookup_dataset_value FOR EACH ROW
DECLARE n NUMBER;locked_id NUMBER;
 PROCEDURE guard(id NUMBER) IS
 BEGIN
  SELECT lookup_dataset_id INTO locked_id FROM lookup_dataset WHERE lookup_dataset_id=id FOR UPDATE;
  SELECT COUNT(*) INTO n FROM algorithm_lookup_config c JOIN algorithm_version v ON v.algorithm_version_id=c.algorithm_version_id
   WHERE c.lookup_dataset_id=id AND v.status IN('PUBLISHED','RETIRED');
  IF n>0 THEN RAISE_APPLICATION_ERROR(-20072,'Published lookup values are immutable; create a new dataset and algorithm version');END IF;
 END;
BEGIN
 IF UPDATING OR DELETING THEN guard(:OLD.lookup_dataset_id);END IF;
 IF INSERTING OR (UPDATING AND :NEW.lookup_dataset_id<>:OLD.lookup_dataset_id) THEN guard(:NEW.lookup_dataset_id);END IF;
END;
/
CREATE OR REPLACE TRIGGER lookup_binding_frozen_guard
BEFORE INSERT OR UPDATE OR DELETE ON algorithm_lookup_config FOR EACH ROW
DECLARE state VARCHAR2(20);locked_id NUMBER;
BEGIN
 IF UPDATING OR DELETING THEN
  SELECT lookup_dataset_id INTO locked_id FROM lookup_dataset WHERE lookup_dataset_id=:OLD.lookup_dataset_id FOR UPDATE;
  SELECT status INTO state FROM algorithm_version WHERE algorithm_version_id=:OLD.algorithm_version_id;
  IF state IN('PUBLISHED','RETIRED') THEN RAISE_APPLICATION_ERROR(-20073,'Published lookup binding is immutable; create a new algorithm version');END IF;
 END IF;
 IF INSERTING OR UPDATING THEN
  SELECT lookup_dataset_id INTO locked_id FROM lookup_dataset WHERE lookup_dataset_id=:NEW.lookup_dataset_id FOR UPDATE;
 END IF;
END;
/
