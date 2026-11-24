package com.privonym.repository.oracle;

import com.privonym.application.service.PostgresqlSqlInventoryPreviewService;
import com.privonym.repository.RepositoryConnections;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.Set;

/** Optional integration check, restricted to an explicitly named disposable database. */
public final class PostgresqlRepositoryIntegrationCheck {
    private PostgresqlRepositoryIntegrationCheck() { }
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) { p.load(input); }
        String url = p.getProperty("repository.url"), user = p.getProperty("repository.user");
        String password = System.getenv("PRIVONYM_REPOSITORY_PASSWORD");
        if (url == null || !url.matches("jdbc:postgresql://localhost:5432/privonym_migration_test_[0-9]+_fresh")) {
            throw new IllegalArgumentException("Only the disposable PostgreSQL migration test database is permitted");
        }
        Path secrets = Files.createTempDirectory("privonym-pg-fixture-");
        String previous = System.getProperty("privonym.secret.key.file");
        System.setProperty("privonym.secret.key.file", secrets.resolve("key").toString());
        char[] chars = password.toCharArray();
        try (Connection c = RepositoryConnections.open(url, user, password);
                Connection target = java.sql.DriverManager.getConnection(url, user, password);
                var security = new OracleApplicationSecurityAdapter(url, user, chars);
                var environments = new OracleEnvironmentQueryAdapter(url, user, chars);
                var discovery = new OracleDiscoveryAdapter(url, user, chars);
                var importer = new OracleDraftInventoryAdapter(url, user, chars);
                var rulesets = new OracleRulesetAdministrationAdapter(url, user, chars);
                var management = new OracleMaskingInventoryManagementAdapter(url, user, chars)) {
            require(!security.isInitialized(), "Unexpected pre-existing test users");
            var administrator = security.createInitialAdministrator("fixtureadmin", "Fixture administrator", "FixturePassword-42!".toCharArray());
            require(security.authenticate("fixtureadmin", "FixturePassword-42!".toCharArray()).id() == administrator.id(), "Authentication failed");
            var ordinary = security.createUser("fixtureadmin", "fixtureuser", "Fixture user", "FixturePassword-42!".toCharArray(), false, Set.of());
            require(ordinary.id() != administrator.id(), "Generated user identifiers collide");
            try (Statement statement = target.createStatement()) {
                statement.execute("CREATE SCHEMA privonym_fixture");
                statement.execute("CREATE TABLE privonym_fixture.people(id bigint PRIMARY KEY,secret text)");
                statement.execute("INSERT INTO privonym_fixture.people SELECT i,'SYNTHETIC_'||i FROM generate_series(1,2500) i");
            }
            long env = environments.create("PG_FIXTURE", "PostgreSQL fixture", "Synthetic integration only").id();
            String database = url.substring(url.lastIndexOf('/') + 1);
            long source = discovery.createConnection(env, "PG_FIXTURE", "POSTGRESQL", "privonym_fixture", "localhost", 5432,
                    "", database, user, "ENCRYPTED_LOCAL", "", chars).dataSourceId();
            discovery.scan(source);
            require(!discovery.listConnections().isEmpty(), "Connection listing failed");
            String sql = "UPDATE privonym_fixture.people SET secret=NULL;";
            var preview = new PostgresqlSqlInventoryPreviewService().parse(sql);
            var draft = importer.create(source, "PG_FIXTURE", "PostgreSQL fixture", sql, preview, "fixtureadmin");
            require(draft.reviewItems() == 0 && draft.columnRulesCreated() == 1, "Import requires unexpected review");
            management.publish(draft.rulesetVersionId());
            long ruleset;
            try (PreparedStatement query = c.prepareStatement("SELECT ruleset_id FROM ruleset_version WHERE ruleset_version_id=?")) {
                query.setLong(1, draft.rulesetVersionId());
                try (ResultSet row = query.executeQuery()) { row.next(); ruleset = row.getLong(1); }
            }
            require(rulesets.list().size() == 1, "Rule Set summary failed");
            var job = rulesets.createJob(ruleset, draft.rulesetVersionId(), "PG_FIXTURE_JOB", 2, "CHUNKED", 1000, 1000, "fixtureadmin");
            try (Statement statement = target.createStatement()) {
                statement.execute("""
                        CREATE FUNCTION privonym_fixture.fail_last_chunk() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN IF NEW.id>2000 THEN RAISE EXCEPTION 'Synthetic rollback probe'; END IF; RETURN NEW; END $$
                        """);
                statement.execute("CREATE TRIGGER fail_last_chunk BEFORE UPDATE ON privonym_fixture.people FOR EACH ROW EXECUTE FUNCTION privonym_fixture.fail_last_chunk()");
            }
            boolean interrupted = false;
            try { rulesets.executeJobToCompletion(job.jobId()); }
            catch (RuntimeException expected) { interrupted = true; }
            require(interrupted, "The injected last-chunk failure was not observed");
            try (Statement query = c.createStatement(); ResultSet row = query.executeQuery("SELECT COUNT(*) FROM job_chunk WHERE status='SUCCEEDED'")) {
                row.next(); require(row.getLong(1) > 0, "No checkpoint survived the failed execution");
            }
            try (Statement statement = target.createStatement()) {
                statement.execute("DROP TRIGGER fail_last_chunk ON privonym_fixture.people");
                statement.execute("""
                        CREATE FUNCTION privonym_fixture.reject_replay() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN IF OLD.secret IS NULL THEN RAISE EXCEPTION 'A committed chunk was replayed'; END IF; RETURN NEW; END $$
                        """);
                statement.execute("CREATE TRIGGER reject_replay BEFORE UPDATE ON privonym_fixture.people FOR EACH ROW EXECUTE FUNCTION privonym_fixture.reject_replay()");
            }
            var report = rulesets.executeJobToCompletion(job.jobId());
            require(report.rowsProcessed() == 2500, "Unexpected processed row count: " + report.rowsProcessed());
            try (Statement query = c.createStatement(); ResultSet row = query.executeQuery("SELECT COUNT(*) FROM privonym_fixture.people WHERE secret IS NOT NULL")) {
                row.next(); require(row.getLong(1) == 0, "Some synthetic target values were not anonymized");
            }
            rulesets.recovery(job.jobId());
            try (var jobs = new OracleJobQueryAdapter(url, user, chars);
                    var inventory = new OracleMaskingInventoryQueryAdapter(url, user, chars)) {
                require(!jobs.list(10).isEmpty(), "Job listing failed");
                require(!inventory.list().isEmpty(), "Inventory listing failed");
            }
            verifyCommitFence(c, url, user, password, job.jobId());
            System.out.println("PostgreSQL repository integration passed: users, login, discovery, import, publication, parallel chunks, injected rollback, checkpoint-preserving resume and commit fencing (2500 synthetic rows).");
        } finally {
            java.util.Arrays.fill(chars, '\0');
            if (previous == null) System.clearProperty("privonym.secret.key.file");
            else System.setProperty("privonym.secret.key.file", previous);
            Files.deleteIfExists(secrets.resolve("key")); Files.deleteIfExists(secrets);
        }
    }
    private static void verifyCommitFence(Connection c, String url, String user, String password, long fixtureJob) throws Exception {
        long probe;
        try (PreparedStatement statement = c.prepareStatement("""
                INSERT INTO anonymization_job(job_reference,ruleset_version_id,data_source_id,status,started_at,heartbeat_at)
                SELECT 'PG_CONTROL_PROBE',ruleset_version_id,data_source_id,'RUNNING',clock_timestamp(),clock_timestamp()
                FROM anonymization_job WHERE job_id=? RETURNING job_id
                """)) {
            statement.setLong(1, fixtureJob);
            try (ResultSet row = statement.executeQuery()) { row.next(); probe = row.getLong(1); }
        }
        c.setAutoCommit(false);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var control = new JobExecutionControl(probe, c, () -> RepositoryConnections.open(url,user,password), null);
                Connection waiting = RepositoryConnections.open(url,user,password)) {
            waiting.setAutoCommit(false);
            control.beforeCommit(c);
            var future = pool.submit(() -> { control.beforeCommit(waiting); waiting.rollback(); return true; });
            Thread.sleep(6500);
            require(!future.isDone(), "A competing worker bypassed the commit fence");
            c.rollback();
            require(future.get(10, java.util.concurrent.TimeUnit.SECONDS), "The competing worker could not continue");
            control.check();
        } finally {
            pool.shutdownNow(); c.rollback(); c.setAutoCommit(true);
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM anonymization_job WHERE job_id=?")) {
                delete.setLong(1,probe); delete.executeUpdate();
            }
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
