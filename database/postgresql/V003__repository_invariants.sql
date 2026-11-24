CREATE UNIQUE INDEX ruleset_one_draft_uk ON ruleset_version(ruleset_id) WHERE status='DRAFT';

CREATE FUNCTION privonym_ruleset_source_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM data_source WHERE data_source_id=NEW.data_source_id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM ruleset WHERE data_source_id=NEW.data_source_id
           AND ruleset_id<>NEW.ruleset_id) THEN
  RAISE EXCEPTION 'Connection already has a Rule Set; create a new version of its current Rule Set';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ruleset_source_guard BEFORE INSERT OR UPDATE OF data_source_id ON ruleset
 FOR EACH ROW EXECUTE FUNCTION privonym_ruleset_source_guard();

CREATE FUNCTION privonym_lookup_value_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE ids bigint[]; dataset bigint;
BEGIN
 IF TG_OP='INSERT' THEN ids:=ARRAY[NEW.lookup_dataset_id];
 ELSIF TG_OP='DELETE' THEN ids:=ARRAY[OLD.lookup_dataset_id];
 ELSE ids:=ARRAY[OLD.lookup_dataset_id,NEW.lookup_dataset_id]; END IF;
 -- Consistent lock order for a value moved between datasets.
 FOR dataset IN SELECT DISTINCT x FROM unnest(ids) x ORDER BY x LOOP
  PERFORM 1 FROM lookup_dataset WHERE lookup_dataset_id=dataset FOR UPDATE;
  IF EXISTS(SELECT 1 FROM algorithm_lookup_config c JOIN algorithm_version v
            ON v.algorithm_version_id=c.algorithm_version_id
            WHERE c.lookup_dataset_id=dataset AND v.status IN('PUBLISHED','RETIRED')) THEN
   RAISE EXCEPTION 'Published lookup values are immutable; create a new dataset and algorithm version';
  END IF;
 END LOOP;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER lookup_values_frozen_guard BEFORE INSERT OR UPDATE OR DELETE ON lookup_dataset_value
 FOR EACH ROW EXECUTE FUNCTION privonym_lookup_value_guard();

CREATE FUNCTION privonym_lookup_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE ids bigint[]; dataset bigint;
BEGIN
 IF TG_OP='INSERT' THEN ids:=ARRAY[NEW.lookup_dataset_id];
 ELSIF TG_OP='DELETE' THEN ids:=ARRAY[OLD.lookup_dataset_id];
 ELSE ids:=ARRAY[OLD.lookup_dataset_id,NEW.lookup_dataset_id]; END IF;
 FOR dataset IN SELECT DISTINCT x FROM unnest(ids) x ORDER BY x LOOP
  PERFORM 1 FROM lookup_dataset WHERE lookup_dataset_id=dataset FOR UPDATE;
 END LOOP;
 IF TG_OP IN('UPDATE','DELETE') AND EXISTS(SELECT 1 FROM algorithm_version
    WHERE algorithm_version_id=OLD.algorithm_version_id AND status IN('PUBLISHED','RETIRED')) THEN
  RAISE EXCEPTION 'Published lookup binding is immutable; create a new algorithm version';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER lookup_binding_frozen_guard BEFORE INSERT OR UPDATE OR DELETE ON algorithm_lookup_config
 FOR EACH ROW EXECUTE FUNCTION privonym_lookup_binding_guard();
