package com.privonym.repository.oracle;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;

/** Synthetic checks: no business database is opened. */
final class AuditRemediationSmokeCheck {
    private AuditRemediationSmokeCheck() { }

    static void verify() throws Exception {
        String previous = System.getProperty("privonym.secret.key.file");
        Path key = Files.createTempFile("privonym-test-key-", ".key");
        try {
            byte[] bytes = new byte[32];
            new java.security.SecureRandom().nextBytes(bytes);
            Files.writeString(key, Base64.getEncoder().encodeToString(bytes));
            System.setProperty("privonym.secret.key.file", key.toString());
            for (String sql : new String[] {
                    "UPDATE t SET c=HMAC(c,'synthetic-secret-中文')",
                    "UPDATE t SET c=DBMS_CRYPTO.MAC(c,1,'synthetic-secret')",
                    "UPDATE t SET c=dbo.PRIVONYM_IBAN_GENERATE(c,'synthetic-secret')"}) {
                String stored = ProtectedSql.store(sql);
                require(!stored.contains("synthetic-secret"), "saved keyed SQL must hide the key");
                require(sql.equals(ProtectedSql.read(stored)), "SQL encryption must round-trip exactly");
                require(!stored.equals(ProtectedSql.store(sql)), "SQL encryption needs a fresh nonce");
                byte[] changed = Base64.getDecoder().decode(stored.substring(stored.indexOf(':') + 1));
                changed[changed.length - 1] ^= 1;
                String tampered = stored.substring(0, stored.indexOf(':') + 1)
                        + Base64.getEncoder().encodeToString(changed);
                boolean rejected = false;
                try { ProtectedSql.read(tampered); }
                catch (IllegalStateException expected) { rejected = true; }
                require(rejected, "tampered ciphertext must not execute");
            }
            require("UPDATE t SET c=1".equals(ProtectedSql.read(ProtectedSql.store("UPDATE t SET c=1"))),
                    "ordinary SQL encryption remains executable");
            require("UPDATE t SET c=1".equals(ProtectedSql.read("UPDATE t SET c=1")),
                    "legacy plaintext plans remain readable");
        } finally {
            if (previous == null) System.clearProperty("privonym.secret.key.file");
            else System.setProperty("privonym.secret.key.file", previous);
            Files.deleteIfExists(key);
        }
        OracleParallelPlanExecutor.requireReconciledChunks(repository(0), 1);
        boolean blocked = false;
        try { OracleParallelPlanExecutor.requireReconciledChunks(repository(1), 1); }
        catch (IllegalStateException expected) { blocked = true; }
        require(blocked, "uncertain chunks must block automatic retry");
        System.out.println("Audit remediation smoke check passed.");
    }

    private static Connection repository(long unresolved) {
        ResultSet rows = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> true;
                    case "getLong" -> unresolved;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PreparedStatement query = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setLong", "close" -> null;
                    case "executeQuery" -> rows;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        require(((String) args[0]).contains("status NOT IN ('SUCCEEDED','FAILED')"),
                                "known failed chunks must remain retryable");
                        yield query;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
