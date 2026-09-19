package com.privonym.application.service;

import com.privonym.application.port.in.PreviewSqlInventoryUseCase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Routes Transformer requests to a parser registered for the selected connection technology. */
public final class MultiDialectSqlInventoryPreviewService implements PreviewSqlInventoryUseCase {
    private final Map<String, SqlDialectParser> parsers;

    public MultiDialectSqlInventoryPreviewService(List<? extends SqlDialectParser> parsers) {
        Map<String, SqlDialectParser> registered = new LinkedHashMap<>();
        for (SqlDialectParser parser : parsers) {
            String technology = normalize(parser.technology());
            if (technology.isEmpty()) throw new IllegalArgumentException("Parser technology is required");
            if (registered.putIfAbsent(technology, parser) != null) {
                throw new IllegalArgumentException("Duplicate Transformer parser: " + technology);
            }
        }
        this.parsers = Map.copyOf(registered);
    }

    public MultiDialectSqlInventoryPreviewService(SqlDialectParser... parsers) {
        this(List.of(parsers));
    }

    @Override
    public SqlInventoryPreview preview(String technology, String sql) {
        String normalized = normalize(technology);
        SqlDialectParser parser = parsers.get(normalized);
        if (parser == null) {
            throw new IllegalArgumentException(
                    "No Transformer parser is registered for " + normalized);
        }
        return parser.parse(sql);
    }

    private static String normalize(String technology) {
        return technology == null ? "" : technology.trim().toUpperCase(Locale.ROOT);
    }
}
