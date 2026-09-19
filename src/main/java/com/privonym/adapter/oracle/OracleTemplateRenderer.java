package com.privonym.adapter.oracle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OracleTemplateRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    public String render(
            String template,
            String columnName,
            OracleQualifiedTable qualifiedTable,
            List<AlgorithmParameter> definitions,
            Map<String, String> suppliedValues) {

        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(columnName, "columnName");
        Objects.requireNonNull(qualifiedTable, "qualifiedTable");
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(suppliedValues, "suppliedValues");

        Map<String, AlgorithmParameter> byName = new HashMap<>();
        for (AlgorithmParameter definition : definitions) {
            if (byName.put(definition.name(), definition) != null) {
                throw new OracleTemplateException(
                        "Duplicate parameter definition: " + definition.name());
            }
        }

        Set<String> unknownInputs = new HashSet<>(suppliedValues.keySet());
        unknownInputs.removeAll(byName.keySet());
        if (!unknownInputs.isEmpty()) {
            throw new OracleTemplateException("Unknown supplied parameters: " + unknownInputs);
        }

        String quotedColumn = OracleIdentifier.quote(columnName);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer result = new StringBuffer();
        Set<String> referencedParameters = new HashSet<>();

        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement;
            if (key.equals("column")) {
                replacement = quotedColumn;
            } else if (key.equals("qualified_table")) {
                replacement = qualifiedTable.toSql();
            } else if (key.startsWith("param.")) {
                String parameterName = key.substring("param.".length());
                AlgorithmParameter definition = byName.get(parameterName);
                if (definition == null) {
                    throw new OracleTemplateException(
                            "Template references undefined parameter: " + parameterName);
                }
                referencedParameters.add(parameterName);
                replacement = OracleLiteral.render(
                        definition, suppliedValues.get(parameterName));
            } else {
                throw new OracleTemplateException("Unsupported placeholder: " + key);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        if (result.indexOf("${") >= 0) {
            throw new OracleTemplateException("Malformed or unclosed placeholder in template");
        }

        Set<String> unusedInputs = new HashSet<>(suppliedValues.keySet());
        unusedInputs.removeAll(referencedParameters);
        if (!unusedInputs.isEmpty()) {
            throw new OracleTemplateException("Supplied parameters are unused: " + unusedInputs);
        }

        return result.toString();
    }
}
