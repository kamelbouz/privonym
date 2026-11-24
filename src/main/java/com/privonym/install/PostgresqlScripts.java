package com.privonym.install;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SQL file splitting, including PostgreSQL dollar-quoted function bodies. */
public final class PostgresqlScripts {
    private PostgresqlScripts() { }
    public static List<String> statements(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length();) {
            int start = i;
            char ch = text.charAt(i);
            if (text.startsWith("--", i)) {
                int end = text.indexOf('\n', i); i = end < 0 ? text.length() : end;
                current.append('\n'); continue;
            }
            if (text.startsWith("/*", i)) {
                int depth = 1; i += 2;
                while (i < text.length() && depth > 0) {
                    if (text.startsWith("/*", i)) { depth++; i += 2; }
                    else if (text.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                if (depth != 0) throw new IllegalArgumentException("Unterminated PostgreSQL comment");
                current.append(' '); continue;
            }
            if (ch == '\'' || ch == '"') {
                i++; boolean closed = false;
                while (i < text.length()) {
                    if (text.charAt(i++) == ch) {
                        if (i < text.length() && text.charAt(i) == ch) { i++; continue; }
                        closed = true; break;
                    }
                }
                if (!closed) throw new IllegalArgumentException("Unterminated PostgreSQL string");
            } else if (ch == '$') {
                Matcher marker = Pattern.compile("\\$(?:[a-zA-Z_][a-zA-Z0-9_]*)?\\$").matcher(text);
                marker.region(i, text.length());
                if (marker.lookingAt()) {
                    String tag = marker.group();
                    int end = text.indexOf(tag, i + tag.length());
                    if (end < 0) throw new IllegalArgumentException("Unterminated PostgreSQL dollar string");
                    i = end + tag.length();
                } else i++;
            } else if (ch == ';') {
                if (!current.toString().isBlank()) result.add(current.toString().strip());
                current.setLength(0); i++; continue;
            } else i++;
            current.append(text, start, i);
        }
        if (!current.toString().isBlank()) result.add(current.toString().strip());
        return List.copyOf(result);
    }
}
