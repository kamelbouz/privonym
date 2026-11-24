package com.privonym.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, explicit rendering boundary for the existing repository statements. Never for user SQL. */
public final class PostgresqlRepositorySql {
    private PostgresqlRepositorySql() { }

    public static String render(String sql) {
        List<String> protectedParts = new ArrayList<>();
        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < sql.length();) {
            int start = i;
            char ch = sql.charAt(i);
            if (ch == '\'' || ch == '"') {
                char quote = ch;
                i++;
                boolean closed = false;
                while (i < sql.length()) {
                    if (sql.charAt(i++) == quote) {
                        if (i < sql.length() && sql.charAt(i) == quote) { i++; continue; }
                        closed = true; break;
                    }
                }
                if (!closed) throw new IllegalArgumentException("Unterminated repository SQL literal");
            } else if (sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i); i = end < 0 ? sql.length() : end;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) throw new IllegalArgumentException("Unterminated repository SQL comment");
                i = end + 2;
            } else {
                masked.append(ch); i++; continue;
            }
            masked.append("__PRIVONYM_PROTECTED_").append(protectedParts.size()).append("__");
            protectedParts.add(sql.substring(start, i));
        }
        String result = masked.toString()
                .replaceAll("(?i)\\bSYSTIMESTAMP\\b", "statement_timestamp()")
                .replaceAll("(?i)\\bDBMS_LOB\\.SUBSTR\\s*\\(", "privonym_lob_substr(")
                .replaceAll("(?i)\\bTO_CHAR\\s*\\(", "privonym_to_char(")
                .replaceAll("(?i)\\b([a-z][a-z0-9_]*)\\.NEXTVAL\\b", "nextval('$1')")
                .replaceAll("(?i)\\bFOR UPDATE OF ([a-z][a-z0-9_]*)\\.[a-z][a-z0-9_]*", "FOR UPDATE OF $1");
        // PostgreSQL MERGE supports the same branches, but SET columns cannot be qualified.
        Matcher merge = Pattern.compile("(?is)^\\s*MERGE INTO\\s+\\w+\\s+(\\w+)\\s+USING\\b").matcher(result);
        if (merge.find()) {
            String alias = merge.group(1);
            Matcher update = Pattern.compile("(?is)(WHEN MATCHED THEN UPDATE SET)(.*?)(?=WHEN NOT MATCHED|$)").matcher(result);
            if (update.find()) {
                String assignments = update.group(2).replaceAll("(?i)\\b" + Pattern.quote(alias)
                        + "\\.([a-z][a-z0-9_]*)\\s*=", "$1=");
                result = result.substring(0, update.start(2)) + assignments + result.substring(update.end(2));
            }
        }
        Matcher restore = Pattern.compile("__PRIVONYM_PROTECTED_([0-9]+)__").matcher(result);
        StringBuilder restored = new StringBuilder();
        while (restore.find()) restore.appendReplacement(restored,
                Matcher.quoteReplacement(protectedParts.get(Integer.parseInt(restore.group(1)))));
        restore.appendTail(restored);
        return restored.toString();
    }
}
