package com.privonym.repository.oracle;

import java.util.Arrays;

/** Versioned encryption envelope for saved plans, including custom keyed algorithms. */
final class ProtectedSql {
    private static final String PREFIX = "PRIVONYM_SQL_AESGCM_V1:";
    private ProtectedSql() { }

    static String store(String sql) {
        if (sql == null || sql.startsWith(PREFIX)) return sql;
        return PREFIX + LocalCredentialStore.encrypt(sql);
    }

    static String read(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        char[] plain = LocalCredentialStore.decrypt(stored.substring(PREFIX.length()));
        try { return new String(plain); }
        finally { Arrays.fill(plain, '\0'); }
    }
}
