package com.privonym.repository.oracle;

import com.privonym.http.ApplicationSecurity;
import com.privonym.http.PrivonymUser;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Oracle-backed Privonym application users, passwords, privileges and ownership. */
public final class OracleApplicationSecurityAdapter implements ApplicationSecurity {
    private static final int ITERATIONS = 210_000;
    private static final int HASH_BITS = 256;
    private static final int MAX_FAILURES = 5;
    private static final byte[] UNKNOWN_SALT = "PrivonymUnknownUserSalt".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final String jdbcUrl;
    private final String repositoryUser;
    private final char[] repositoryPassword;
    private final SecureRandom random = new SecureRandom();

    public OracleApplicationSecurityAdapter(String jdbcUrl, String repositoryUser,
            char[] repositoryPassword) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.repositoryUser = Objects.requireNonNull(repositoryUser, "repositoryUser");
        this.repositoryPassword = Objects.requireNonNull(repositoryPassword, "repositoryPassword").clone();
        verifySecuritySchema();
    }

    @Override
    public boolean isInitialized() {
        try (Connection connection = open(); Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM privonym_user")) {
            row.next();
            return row.getLong(1) > 0;
        } catch (SQLException error) {
            throw failure("check application security", error);
        }
    }

    @Override
    public PrivonymUser createInitialAdministrator(String username, String displayName,
            char[] password) {
        String canonical = canonicalUsername(username);
        validatePassword(password);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (Statement lock = connection.createStatement()) {
                lock.execute("LOCK TABLE privonym_user IN EXCLUSIVE MODE");
            }
            try (Statement statement = connection.createStatement();
                    ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM privonym_user")) {
                row.next();
                if (row.getLong(1) != 0) {
                    throw new IllegalStateException("The initial administrator already exists");
                }
            }
            long id = insertUser(connection, canonical, displayName, password, true, canonical);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ruleset SET owner_user_id=? WHERE owner_user_id IS NULL")) {
                statement.setLong(1, id); statement.executeUpdate();
            }
            insertEvent(connection, canonical, "INITIAL_ADMIN_CREATED", "USER", canonical, "SUCCESS");
            connection.commit();
            return new PrivonymUser(id, canonical, normalizedDisplayName(displayName), true, true,
                    AVAILABLE_PRIVILEGES);
        } catch (SQLException error) {
            throw failure("create initial administrator", error);
        }
    }

    @Override
    public PrivonymUser authenticate(String username, char[] password) {
        String canonical;
        try {
            canonical = canonicalUsername(username);
        } catch (IllegalArgumentException error) {
            derive(password, UNKNOWN_SALT, ITERATIONS);
            throw new IllegalArgumentException("Invalid username or password");
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            String sql = """
                    SELECT user_id,display_name,password_hash,password_salt,password_iterations,
                           administrator,status,failed_login_count,locked_until
                      FROM privonym_user WHERE username=? FOR UPDATE
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonical);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        derive(password, UNKNOWN_SALT, ITERATIONS);
                        insertEvent(connection, canonical, "LOGIN", "USER", canonical, "DENIED");
                        connection.commit();
                        throw new IllegalArgumentException("Invalid username or password");
                    }
                    long userId = row.getLong("user_id");
                    boolean enabled = "ENABLED".equals(row.getString("status"));
                    java.sql.Timestamp locked = row.getTimestamp("locked_until");
                    boolean currentlyLocked = locked != null
                            && locked.toInstant().isAfter(java.time.Instant.now());
                    byte[] salt = Base64.getDecoder().decode(row.getString("password_salt"));
                    byte[] expected = Base64.getDecoder().decode(row.getString("password_hash"));
                    byte[] actual = derive(password, salt, row.getInt("password_iterations"));
                    boolean valid = MessageDigest.isEqual(expected, actual);
                    Arrays.fill(actual, (byte) 0);
                    if (!enabled || currentlyLocked || !valid) {
                        int failures = row.getInt("failed_login_count") + 1;
                        try (PreparedStatement update = connection.prepareStatement("""
                                UPDATE privonym_user SET failed_login_count=?,
                                  locked_until=CASE WHEN ?>=? THEN SYSTIMESTAMP+NUMTODSINTERVAL(15,'MINUTE') ELSE locked_until END,
                                  updated_at=SYSTIMESTAMP WHERE user_id=?
                                """)) {
                            update.setInt(1, failures); update.setInt(2, failures);
                            update.setInt(3, MAX_FAILURES); update.setLong(4, userId); update.executeUpdate();
                        }
                        insertEvent(connection, canonical, "LOGIN", "USER", canonical, "DENIED");
                        connection.commit();
                        throw new IllegalArgumentException("Invalid username or password");
                    }
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE privonym_user SET failed_login_count=0,locked_until=NULL,
                                   last_login_at=SYSTIMESTAMP,updated_at=SYSTIMESTAMP WHERE user_id=?
                            """)) {
                        update.setLong(1, userId); update.executeUpdate();
                    }
                    insertEvent(connection, canonical, "LOGIN", "USER", canonical, "SUCCESS");
                    connection.commit();
                    return new PrivonymUser(userId, canonical, row.getString("display_name"),
                            "Y".equals(row.getString("administrator")), true,
                            loadPrivileges(connection, userId));
                }
            }
        } catch (SQLException error) {
            throw failure("authenticate application user", error);
        }
    }

    @Override
    public List<PrivonymUser> listUsers() {
        String sql = """
                SELECT user_id,username,display_name,administrator,status
                  FROM privonym_user ORDER BY username
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            List<PrivonymUser> users = new ArrayList<>();
            while (row.next()) {
                long id = row.getLong("user_id");
                users.add(new PrivonymUser(id, row.getString("username"),
                        row.getString("display_name"), "Y".equals(row.getString("administrator")),
                        "ENABLED".equals(row.getString("status")), loadPrivileges(connection, id)));
            }
            return List.copyOf(users);
        } catch (SQLException error) {
            throw failure("list application users", error);
        }
    }

    @Override
    public PrivonymUser createUser(String actor, String username, String displayName,
            char[] password, boolean administrator, Set<String> privileges) {
        String canonical = canonicalUsername(username);
        String canonicalActor = canonicalUsername(actor);
        validatePassword(password);
        Set<String> grants = validatedPrivileges(privileges);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            long id = insertUser(connection, canonical, displayName, password, administrator,
                    canonicalActor);
            if (!administrator) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO privonym_user_privilege(user_id,privilege_code,granted_by)
                        VALUES(?,?,?)
                        """)) {
                    for (String privilege : grants) {
                        statement.setLong(1, id); statement.setString(2, privilege);
                        statement.setString(3, canonicalActor); statement.addBatch();
                    }
                    statement.executeBatch();
                }
            }
            insertEvent(connection, canonicalActor, "USER_CREATED", "USER", canonical, "SUCCESS");
            connection.commit();
            return new PrivonymUser(id, canonical, normalizedDisplayName(displayName), administrator,
                    true, administrator ? AVAILABLE_PRIVILEGES : grants);
        } catch (SQLException error) {
            if (error.getErrorCode() == 1) throw new IllegalArgumentException("Username already exists");
            throw failure("create application user", error);
        }
    }

    @Override
    public PrivonymUser updateUserPrivileges(String actor, long userId,
            boolean administrator, Set<String> privileges) {
        if (userId <= 0) throw new IllegalArgumentException("Invalid user identifier");
        String canonicalActor = canonicalUsername(actor);
        Set<String> grants = validatedPrivileges(privileges);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            long actorId = findUserId(connection, canonicalActor);
            authorizeUserManagement(connection, actorId, userId, administrator, grants);
            if (actorId == userId && !administrator) {
                throw new IllegalStateException("An administrator cannot remove their own administrator role");
            }
            String username;
            String displayName;
            boolean enabled;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT username,display_name,status FROM privonym_user WHERE user_id=? FOR UPDATE")) {
                statement.setLong(1, userId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("User not found");
                    username = row.getString("username"); displayName = row.getString("display_name");
                    enabled = "ENABLED".equals(row.getString("status"));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE privonym_user SET administrator=?,updated_at=SYSTIMESTAMP WHERE user_id=?")) {
                statement.setString(1, administrator ? "Y" : "N"); statement.setLong(2, userId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM privonym_user_privilege WHERE user_id=?")) {
                statement.setLong(1, userId); statement.executeUpdate();
            }
            if (!administrator) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO privonym_user_privilege(user_id,privilege_code,granted_by)
                        VALUES(?,?,?)
                        """)) {
                    for (String privilege : grants) {
                        statement.setLong(1, userId); statement.setString(2, privilege);
                        statement.setString(3, canonicalActor); statement.addBatch();
                    }
                    statement.executeBatch();
                }
            }
            insertEvent(connection, canonicalActor, "USER_PRIVILEGES_UPDATED", "USER",
                    username, "SUCCESS");
            connection.commit();
            return new PrivonymUser(userId, username, displayName, administrator, enabled,
                    administrator ? AVAILABLE_PRIVILEGES : grants);
        } catch (SQLException error) {
            throw failure("update user privileges", error);
        }
    }

    @Override
    public void setUserEnabled(String actor, long userId, boolean enabled) {
        if (userId <= 0) throw new IllegalArgumentException("Invalid user identifier");
        String canonicalActor = canonicalUsername(actor);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            long actorId = findUserId(connection, canonicalActor);
            authorizeUserManagement(connection, actorId, userId, false, Set.of());
            if (!enabled && actorId == userId) {
                throw new IllegalStateException("An administrator cannot disable their own account");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE privonym_user SET status=?,failed_login_count=0,locked_until=NULL,
                           updated_at=SYSTIMESTAMP WHERE user_id=?
                    """)) {
                statement.setString(1, enabled ? "ENABLED" : "DISABLED");
                statement.setLong(2, userId);
                if (statement.executeUpdate() != 1) throw new IllegalArgumentException("User not found");
            }
            insertEvent(connection, canonicalActor, enabled ? "USER_ENABLED" : "USER_DISABLED",
                    "USER", Long.toString(userId), "SUCCESS");
            connection.commit();
        } catch (SQLException error) {
            throw failure("update application user", error);
        }
    }

    @Override
    public boolean ownsRuleset(long userId, long rulesetId) {
        return exists("SELECT 1 FROM ruleset WHERE ruleset_id=? AND owner_user_id=?", rulesetId, userId);
    }

    @Override
    public boolean ownsRulesetVersion(long userId, long rulesetVersionId) {
        return exists("""
                SELECT 1 FROM ruleset r JOIN ruleset_version rv ON rv.ruleset_id=r.ruleset_id
                 WHERE rv.ruleset_version_id=? AND r.owner_user_id=?
                """, rulesetVersionId, userId);
    }

    @Override
    public void assignRulesetOwner(long rulesetId, long userId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE ruleset SET owner_user_id=? WHERE ruleset_id=?")) {
            statement.setLong(1, userId); statement.setLong(2, rulesetId);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Rule Set not found");
        } catch (SQLException error) {
            throw failure("assign Rule Set owner", error);
        }
    }

    @Override
    public void assignRulesetVersionOwner(long rulesetVersionId, long userId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE ruleset SET owner_user_id=? WHERE ruleset_id=(
                  SELECT ruleset_id FROM ruleset_version WHERE ruleset_version_id=?)
                """)) {
            statement.setLong(1, userId); statement.setLong(2, rulesetVersionId);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Rule Set version not found");
        } catch (SQLException error) {
            throw failure("assign Rule Set owner", error);
        }
    }

    @Override
    public void record(String username, String action, String objectType,
            String objectReference, String outcome) {
        try (Connection connection = open()) {
            insertEvent(connection, username == null ? "SYSTEM" : username, action,
                    objectType, objectReference, outcome);
        } catch (SQLException error) {
            System.err.println("Unable to record security event: " + error.getMessage());
        }
    }

    private boolean exists(String sql, long first, long second) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, first); statement.setLong(2, second);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        } catch (SQLException error) {
            throw failure("check Rule Set ownership", error);
        }
    }

    private long insertUser(Connection connection, String username, String displayName,
            char[] password, boolean administrator, String actor) throws SQLException {
        byte[] salt = new byte[24]; random.nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS);
        String sql = """
                INSERT INTO privonym_user(username,display_name,password_hash,password_salt,
                  password_iterations,administrator,status,created_by)
                VALUES(?,?,?,?,?,?,'ENABLED',?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql,
                new String[]{"USER_ID"})) {
            statement.setString(1, username); statement.setString(2, normalizedDisplayName(displayName));
            statement.setString(3, Base64.getEncoder().encodeToString(hash));
            statement.setString(4, Base64.getEncoder().encodeToString(salt));
            statement.setInt(5, ITERATIONS); statement.setString(6, administrator ? "Y" : "N");
            statement.setString(7, actor); statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("User identifier was not generated");
                return keys.getLong(1);
            }
        } finally {
            Arrays.fill(salt, (byte) 0); Arrays.fill(hash, (byte) 0);
        }
    }

    private Set<String> loadPrivileges(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT privilege_code FROM privonym_user_privilege WHERE user_id=?")) {
            statement.setLong(1, userId);
            try (ResultSet row = statement.executeQuery()) {
                Set<String> result = new HashSet<>();
                while (row.next()) result.add(row.getString(1));
                return Set.copyOf(result);
            }
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec specification = new PBEKeySpec(password == null ? new char[0] : password,
                salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Password hashing is unavailable", error);
        } finally {
            specification.clearPassword();
        }
    }

    private static String canonicalUsername(String value) {
        String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z0-9._-]{3,100}")) {
            throw new IllegalArgumentException("Username must contain 3 to 100 letters, digits, dots, underscores or hyphens");
        }
        return result;
    }

    private static String normalizedDisplayName(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() < 2 || result.length() > 200) {
            throw new IllegalArgumentException("Display name must contain 2 to 200 characters");
        }
        return result;
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < 12 || password.length > 256) {
            throw new IllegalArgumentException("Password must contain 12 to 256 characters");
        }
        boolean upper = false, lower = false, digit = false, special = false;
        for (char value : password) {
            upper |= Character.isUpperCase(value); lower |= Character.isLowerCase(value);
            digit |= Character.isDigit(value); special |= !Character.isLetterOrDigit(value);
        }
        if (!(upper && lower && digit && special)) {
            throw new IllegalArgumentException("Password must contain upper-case, lower-case, digit and special characters");
        }
    }

    private static Set<String> validatedPrivileges(Set<String> privileges) {
        Set<String> result = new HashSet<>();
        if (privileges != null) for (String value : privileges) {
            String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (!AVAILABLE_PRIVILEGES.contains(code)) throw new IllegalArgumentException("Unknown privilege: " + code);
            result.add(code);
        }
        return Set.copyOf(result);
    }

    private static long findUserId(Connection connection, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT user_id FROM privonym_user WHERE username=?")) {
            statement.setString(1, username);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("User not found");
                return row.getLong(1);
            }
        }
    }

    private static void insertEvent(Connection connection, String username, String action,
            String objectType, String reference, String outcome) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO privonym_security_event(username,action_code,object_type,
                  object_reference,outcome) VALUES(?,?,?,?,?)
                """)) {
            statement.setString(1, username); statement.setString(2, action);
            statement.setString(3, objectType); statement.setString(4, reference);
            statement.setString(5, outcome); statement.executeUpdate();
        }
    }

    private void verifySecuritySchema() {
        try (Connection connection = open()) {
            for (String table : List.of("PRIVONYM_USER", "PRIVONYM_PRIVILEGE",
                    "PRIVONYM_USER_PRIVILEGE", "PRIVONYM_SECURITY_EVENT")) {
                if (!tableExists(connection, table)) throw new IllegalStateException(
                        "Application security schema is missing. Apply repository migrations with the installer.");
            }
            if (!columnExists(connection, "RULESET", "OWNER_USER_ID")) {
                throw new IllegalStateException("Repository security migration is required before login");
            }
        } catch (SQLException error) {
            throw failure("verify application security schema", error);
        }
    }

    private static void authorizeUserManagement(Connection connection, long actorId,
            long targetId, boolean grantingAdministrator, Set<String> grants) throws SQLException {
        // Serialize privilege changes so a concurrent promotion cannot bypass the target check.
        try (Statement lock = connection.createStatement()) {
            lock.execute("LOCK TABLE privonym_user IN EXCLUSIVE MODE");
        }
        boolean actorAdministrator;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT administrator,status FROM privonym_user WHERE user_id=?")) {
            statement.setLong(1, actorId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !"ENABLED".equals(row.getString("status"))) {
                    throw new IllegalStateException("An enabled actor is required");
                }
                actorAdministrator = "Y".equals(row.getString("administrator"));
            }
        }
        if (actorAdministrator) return;
        Set<String> owned = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT privilege_code FROM privonym_user_privilege WHERE user_id=?")) {
            statement.setLong(1, actorId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) owned.add(row.getString(1));
            }
        }
        if (!owned.contains(USER_MANAGE) || grantingAdministrator || !owned.containsAll(grants)) {
            throw new IllegalStateException("User management privileges are insufficient");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT administrator FROM privonym_user WHERE user_id=?")) {
            statement.setLong(1, targetId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("User not found");
                if ("Y".equals(row.getString(1))) {
                    throw new IllegalStateException("Only an administrator can modify an administrator");
                }
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM user_tables WHERE table_name=?")) {
            statement.setString(1, table);
            try (ResultSet row = statement.executeQuery()) { row.next(); return row.getLong(1) > 0; }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM user_tab_columns WHERE table_name=? AND column_name=?
                """)) {
            statement.setString(1, table); statement.setString(2, column);
            try (ResultSet row = statement.executeQuery()) { row.next(); return row.getLong(1) > 0; }
        }
    }

    private Connection open() throws SQLException {
        return com.privonym.repository.RepositoryConnections.open(jdbcUrl, repositoryUser, new String(repositoryPassword));
    }

    private static IllegalStateException failure(String operation, SQLException error) {
        return new IllegalStateException("Unable to " + operation + ": " + error.getMessage(), error);
    }

    @Override
    public void close() {
        Arrays.fill(repositoryPassword, '\0');
    }
}
