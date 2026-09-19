package com.privonym.adapter.oracle;

import java.util.Objects;
import java.util.regex.Pattern;

/** Validates an Oracle simple SQL identifier before quoting it. */
public final class OracleIdentifier {
    private static final Pattern SIMPLE_IDENTIFIER =
            Pattern.compile("[A-Za-z][A-Za-z0-9_$#]{0,127}");

    private OracleIdentifier() {
    }

    public static String quote(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (!SIMPLE_IDENTIFIER.matcher(identifier).matches()) {
            throw new OracleTemplateException("Unsafe Oracle identifier: " + identifier);
        }
        return '"' + identifier + '"';
    }
}
