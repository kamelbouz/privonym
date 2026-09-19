package com.privonym.application.service;

import com.privonym.application.port.in.PreviewSqlInventoryUseCase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reconstructs a masking inventory from Oracle UPDATE, DELETE and TRUNCATE SQL. */
public final class OracleSqlInventoryPreviewService
        implements PreviewSqlInventoryUseCase, SqlDialectParser {
    private static final String IDENTIFIER = "(?:\\\"(?:\\\"\\\"|[^\\\"])+\\\"|[A-Za-z][A-Za-z0-9_$#]*)";
    private static final String SQLPLUS_VARIABLE = "&&?[A-Za-z][A-Za-z0-9_$#]*";
    private static final String TABLE_REFERENCE = "(?:" + SQLPLUS_VARIABLE
            + "\\s*\\.\\s*\\.\\s*" + IDENTIFIER
            + "|" + IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?)";
    private static final String QUALIFIED_NAME = "(" + TABLE_REFERENCE + ")";
    private static final Pattern SQLPLUS_QUALIFIED_NAME = Pattern.compile(
            "(?is)^\\s*" + SQLPLUS_VARIABLE + "\\s*\\.\\s*\\.\\s*(" + IDENTIFIER + ")\\s*$");
    private static final Pattern UPDATE = Pattern.compile(
            "(?is)^\\s*UPDATE\\s+" + QUALIFIED_NAME + "(?:\\s+(?:AS\\s+)?[A-Za-z0-9_$#]+)?\\s+SET\\s+");
    private static final Pattern DELETE = Pattern.compile(
            "(?is)^\\s*DELETE\\s+FROM\\s+" + QUALIFIED_NAME + "(?:\\s+WHERE\\s+(.+))?\\s*$");
    private static final Pattern TRUNCATE = Pattern.compile(
            "(?is)^\\s*TRUNCATE\\s+TABLE\\s+" + QUALIFIED_NAME + "(?:\\s+.*)?$");
    private static final Pattern MERGE = Pattern.compile(
            "(?is)^\\s*MERGE\\s+INTO\\s+" + QUALIFIED_NAME
                    + "(?:\\s+(?:AS\\s+)?[A-Za-z0-9_$#]+)?\\s+USING\\b");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?is)^\\s*(?:[A-Za-z0-9_$#]+\\.)?(\\\"[^\\\"]+\\\"|[A-Za-z0-9_$#]+)\\s*=\\s*(.+)$");
    private static final Pattern STRING_LITERAL = Pattern.compile("(?is)^N?'(?:''|[^'])*'$");

    @Override
    public SqlInventoryPreview preview(String technology, String sql) {
        if (technology == null || !"ORACLE".equalsIgnoreCase(technology.trim())) {
            throw new IllegalArgumentException("Only ORACLE is supported by this Transformer version");
        }
        return parse(sql);
    }

    @Override
    public String technology() {
        return "ORACLE";
    }

    @Override
    public SqlInventoryPreview parse(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL content is required");
        if (sql.length() > 2_000_000) throw new IllegalArgumentException("SQL content exceeds the 2 MB preview limit");

        List<SqlInventoryTable> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        ScriptSegments script = segmentScript(stripComments(sql), warnings);
        for (ScriptStatement item : script.statements()) {
            String statement = item.sql();
            if (parseUpdate(statement, item.number(), entries, warnings)) continue;
            if (parseMerge(statement, item.number(), entries, warnings)) continue;
            if (parseDelete(statement, item.number(), entries, warnings)) continue;
            if (parseTruncate(statement, item.number(), entries)) continue;
            warnings.add("Statement " + item.number()
                    + " is not an UPDATE, MERGE, DELETE or TRUNCATE operation");
        }
        List<SqlInventoryTable> applicableEntries = retainApplicableAlgorithms(entries, warnings);
        if (applicableEntries.isEmpty()) warnings.add("No masking operation was detected");
        return new SqlInventoryPreview("ORACLE", sha256(sql), script.sourceStatementCount(),
                applicableEntries, warnings);
    }

    /**
     * Extracts static DML from SQL and PL/SQL fragments. Procedure declarations,
     * BEGIN/IF/LOOP prefixes and SQL*Plus slash separators are not masking rules.
     * Dynamic SQL is accepted only when its complete value is a string literal;
     * expressions depending on runtime variables remain explicit review warnings.
     */
    private static ScriptSegments segmentScript(String sql, List<String> warnings) {
        List<String> fragments = splitTopLevel(sql.replaceAll("(?m)^\\s*/\\s*$", " "), ';');
        List<ScriptStatement> statements = new ArrayList<>();
        Map<String, String> variables = new LinkedHashMap<>();
        int sourceNumber = 0;
        for (String raw : fragments) {
            String fragment = raw.trim();
            if (fragment.isEmpty()) continue;
            sourceNumber++;
            DynamicAssignment assignment = dynamicAssignment(fragment);
            if (assignment != null) {
                String value = evaluateDynamicExpression(assignment.expression(), variables);
                if (value == null) {
                    variables.remove(assignment.variable());
                    warnings.add("Statement " + sourceNumber + ": value of dynamic SQL variable "
                            + assignment.variable() + " could not be reconstructed");
                } else {
                    variables.put(assignment.variable(), value);
                }
                continue;
            }
            if (containsExecuteImmediate(fragment)) {
                String dynamic = resolveExecuteImmediate(fragment, variables);
                if (dynamic == null) {
                    warnings.add("Statement " + sourceNumber
                            + ": dynamic EXECUTE IMMEDIATE depends on runtime values and requires review");
                } else {
                    if (dynamic.contains("&schema..")) warnings.add("Statement " + sourceNumber
                            + ": runtime schema variable was bound to the selected connection schema");
                    statements.add(new ScriptStatement(sourceNumber, dynamic));
                }
                continue;
            }
            int operation = firstOperationKeyword(fragment);
            if (operation >= 0) {
                if (operation > 0) warnings.add("Statement " + sourceNumber
                        + ": procedural wrapper was ignored around the masking operation");
                statements.add(new ScriptStatement(sourceNumber, fragment.substring(operation).trim()));
            } else if (!isProceduralControl(fragment)) {
                statements.add(new ScriptStatement(sourceNumber, fragment));
            }
        }
        return new ScriptSegments(sourceNumber, statements);
    }

    private static DynamicAssignment dynamicAssignment(String fragment) {
        Matcher matcher = Pattern.compile(
                "(?is)\\b([A-Za-z][A-Za-z0-9_$#]*)\\s*:=\\s*(.+)$").matcher(fragment);
        if (!matcher.find()) return null;
        return new DynamicAssignment(matcher.group(1).toUpperCase(Locale.ROOT),
                matcher.group(2).trim());
    }

    private static String resolveExecuteImmediate(String fragment, Map<String, String> variables) {
        Matcher matcher = Pattern.compile(
                "(?is)\\bEXECUTE\\s+IMMEDIATE\\s+(.+)$").matcher(fragment);
        if (!matcher.find()) return null;
        String expression = matcher.group(1).trim();
        int using = findTopLevelKeyword(expression, "USING");
        if (using >= 0) expression = expression.substring(0, using).trim();
        String value = evaluateDynamicExpression(expression, variables);
        return value == null ? null : value.trim().replaceFirst(";\\s*$", "");
    }

    private static String evaluateDynamicExpression(String expression,
            Map<String, String> variables) {
        List<String> parts = splitConcatenation(expression);
        if (parts.isEmpty()) return null;
        StringBuilder value = new StringBuilder();
        for (String raw : parts) {
            String part = stripOuterParentheses(raw.trim());
            if (STRING_LITERAL.matcher(part).matches()) {
                int quote = part.indexOf('\'');
                value.append(part.substring(quote + 1, part.length() - 1)
                        .replace("''", "'"));
                continue;
            }
            String variable = part.toUpperCase(Locale.ROOT);
            String known = variables.get(variable);
            if (known != null) {
                value.append(known);
            } else if (variable.matches("[A-Z][A-Z0-9_$#]*")
                    && (variable.contains("SCHEMA") || variable.contains("OWNER"))) {
                value.append("${PRIVONYM_SCHEMA}");
            } else {
                return null;
            }
        }
        String reconstructed = value.toString().replaceAll(
                "\\$\\{PRIVONYM_SCHEMA\\}\\s*\\.\\s*", "&schema..");
        return reconstructed.contains("${") ? null : reconstructed;
    }

    private static List<String> splitConcatenation(String expression) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < expression.length() - 1; index++) {
            char current = expression.charAt(index);
            if (current == '\'') {
                if (quoted && index + 1 < expression.length()
                        && expression.charAt(index + 1) == '\'') {
                    index++;
                    continue;
                }
                quoted = !quoted;
            } else if (!quoted) {
                if (current == '(') depth++;
                else if (current == ')' && depth > 0) depth--;
                else if (current == '|' && expression.charAt(index + 1) == '|' && depth == 0) {
                    parts.add(expression.substring(start, index));
                    start = index + 2;
                    index++;
                }
            }
        }
        parts.add(expression.substring(start));
        return parts;
    }

    private static String stripOuterParentheses(String value) {
        String result = value;
        while (hasWrappingParentheses(result)) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static boolean hasWrappingParentheses(String value) {
        if (!value.startsWith("(") || !value.endsWith(")")) return false;
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\'') {
                if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    index++;
                    continue;
                }
                quoted = !quoted;
            } else if (!quoted) {
                if (current == '(') depth++;
                else if (current == ')') depth--;
                if (depth == 0 && index < value.length() - 1) return false;
            }
        }
        return depth == 0 && !quoted;
    }

    private static boolean containsExecuteImmediate(String fragment) {
        return Pattern.compile("(?is)\\bEXECUTE\\s+IMMEDIATE\\b").matcher(fragment).find();
    }

    private static int firstOperationKeyword(String text) {
        int result = -1;
        for (String keyword : List.of("UPDATE", "DELETE", "TRUNCATE", "MERGE")) {
            int candidate = findKeywordOutsideLiterals(text, keyword);
            if (candidate >= 0 && (result < 0 || candidate < result)) result = candidate;
        }
        return result;
    }

    private static int findKeywordOutsideLiterals(String text, String keyword) {
        boolean quoted = false;
        boolean identifierQuoted = false;
        for (int index = 0; index <= text.length() - keyword.length(); index++) {
            char current = text.charAt(index);
            if (current == '\'' && !identifierQuoted) {
                if (quoted && index + 1 < text.length() && text.charAt(index + 1) == '\'') {
                    index++;
                    continue;
                }
                quoted = !quoted;
                continue;
            }
            if (current == '"' && !quoted) {
                identifierQuoted = !identifierQuoted;
                continue;
            }
            if (!quoted && !identifierQuoted
                    && text.regionMatches(true, index, keyword, 0, keyword.length())
                    && (index == 0 || !isIdentifierCharacter(text.charAt(index - 1)))
                    && (index + keyword.length() == text.length()
                        || !isIdentifierCharacter(text.charAt(index + keyword.length())))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isProceduralControl(String fragment) {
        String normalized = fragment.stripLeading().toUpperCase(Locale.ROOT);
        return normalized.matches("(?s)(BEGIN|DECLARE|END(?:\\s+IF|\\s+LOOP)?|ELSE|ELSIF.*|IF.*THEN|LOOP|NULL)\\s*")
                || normalized.startsWith("CREATE OR REPLACE PROCEDURE ")
                || normalized.startsWith("CREATE OR REPLACE FUNCTION ");
    }

    /**
     * A table-level destructive operation supersedes column masking for the same table.
     * TRUNCATE also supersedes DELETE because no row remains on which a filtered delete
     * or a column algorithm could operate. The result does not depend on SQL statement order.
     */
    private static List<SqlInventoryTable> retainApplicableAlgorithms(
            List<SqlInventoryTable> entries, List<String> warnings) {
        Set<TableKey> truncated = new HashSet<>();
        Set<TableKey> deleted = new HashSet<>();
        for (SqlInventoryTable entry : entries) {
            TableKey key = new TableKey(entry.schema(), entry.name());
            if ("TRUNCATE".equals(entry.operation())) truncated.add(key);
            else if ("DELETE".equals(entry.operation())) deleted.add(key);
        }

        List<SqlInventoryTable> applicable = new ArrayList<>();
        Set<TableKey> emittedTruncates = new HashSet<>();
        Map<TableKey, Integer> obsoleteCounts = new LinkedHashMap<>();
        for (SqlInventoryTable entry : entries) {
            TableKey key = new TableKey(entry.schema(), entry.name());
            boolean keep;
            if (truncated.contains(key)) {
                keep = "TRUNCATE".equals(entry.operation()) && emittedTruncates.add(key);
            } else if (deleted.contains(key)) {
                keep = "DELETE".equals(entry.operation());
            } else {
                keep = true;
            }
            if (keep) applicable.add(entry);
            else obsoleteCounts.merge(key, 1, Integer::sum);
        }
        obsoleteCounts.forEach((key, count) -> warnings.add(
                display(new Name(key.schema(), key.object())) + ": " + count
                        + " obsolete operation(s) ignored because "
                        + (truncated.contains(key) ? "TRUNCATE" : "DELETE")
                        + " applies at table level"));
        return applicable;
    }

    private static boolean parseUpdate(String statement, int number,
            List<SqlInventoryTable> entries, List<String> warnings) {
        Matcher update = UPDATE.matcher(statement);
        if (!update.find()) return false;
        Name name = name(update.group(1));
        String remainder = statement.substring(update.end());
        int where = findTopLevelKeyword(remainder, "WHERE");
        String assignmentsText = where < 0 ? remainder : remainder.substring(0, where);
        String filter = where < 0 ? "" : remainder.substring(where + 5).trim();
        List<SqlInventoryColumn> assignments = new ArrayList<>();
        for (String item : splitTopLevel(assignmentsText, ',')) {
            Matcher assignment = ASSIGNMENT.matcher(item);
            if (!assignment.matches()) {
                warnings.add("Statement " + number + ": unable to parse an UPDATE assignment");
                continue;
            }
            String column = unquote(assignment.group(1));
            String expression = assignment.group(2).trim();
            Match match = matchAlgorithm(column, expression);
            if ("UNRESOLVED".equals(match.status())) {
                warnings.add("Statement " + number + ": no registered algorithm matched "
                        + display(name) + "." + column);
            }
            assignments.add(new SqlInventoryColumn(column, assignments.size() + 1,
                    expression, match.algorithm(), match.parameters(), match.status(), true));
        }
        entries.add(new SqlInventoryTable(name.schema(), name.object(), "UPDATE", "", filter, assignments));
        return true;
    }

    private static boolean parseMerge(String statement, int number,
            List<SqlInventoryTable> entries, List<String> warnings) {
        Matcher merge = MERGE.matcher(statement);
        if (!merge.find()) return false;
        Name name = name(merge.group(1));
        int updateSet = findTopLevelPhrase(statement, "WHEN MATCHED THEN UPDATE SET");
        if (updateSet < 0) {
            warnings.add("Statement " + number
                    + ": MERGE has no WHEN MATCHED UPDATE clause");
            return true;
        }
        int setKeyword = findTopLevelKeyword(statement.substring(updateSet), "SET");
        if (setKeyword < 0) {
            warnings.add("Statement " + number + ": MERGE UPDATE SET clause is incomplete");
            return true;
        }
        String updateClause = statement.substring(updateSet + setKeyword + 3).trim();
        int nextBranch = findTopLevelPhrase(updateClause, "WHEN NOT MATCHED");
        if (nextBranch >= 0) updateClause = updateClause.substring(0, nextBranch).trim();
        int delete = findTopLevelPhrase(updateClause, "DELETE WHERE");
        if (delete >= 0) {
            warnings.add("Statement " + number
                    + ": MERGE DELETE WHERE clause requires manual review");
            updateClause = updateClause.substring(0, delete).trim();
        }
        int where = findTopLevelKeyword(updateClause, "WHERE");
        String assignmentsText = where < 0 ? updateClause : updateClause.substring(0, where);
        String filter = where < 0 ? "" : updateClause.substring(where + 5).trim();
        List<SqlInventoryColumn> assignments = new ArrayList<>();
        for (String item : splitTopLevel(assignmentsText, ',')) {
            Matcher assignment = ASSIGNMENT.matcher(item);
            if (!assignment.matches()) {
                warnings.add("Statement " + number
                        + ": unable to parse a MERGE UPDATE assignment");
                continue;
            }
            String column = unquote(assignment.group(1));
            String expression = assignment.group(2).trim();
            Match match = matchAlgorithm(column, expression);
            assignments.add(new SqlInventoryColumn(column, assignments.size() + 1,
                    expression, match.algorithm(), match.parameters(), "REVIEW_REQUIRED", true));
        }
        warnings.add("Statement " + number + ": MERGE rules require review because USING/ON "
                + "semantics cannot be converted safely into an independent UPDATE");
        entries.add(new SqlInventoryTable(name.schema(), name.object(), "UPDATE", "",
                filter, assignments));
        return true;
    }

    private static boolean parseDelete(String statement, int number,
            List<SqlInventoryTable> entries, List<String> warnings) {
        Matcher delete = DELETE.matcher(statement);
        if (!delete.matches()) return false;
        Name name = name(delete.group(1));
        String filter = delete.group(2) == null ? "" : delete.group(2).trim();
        String status = filter.isEmpty() ? "MATCHED" : "REVIEW_REQUIRED";
        if (!filter.isEmpty()) warnings.add("Statement " + number
                + ": filtered DELETE requires manual review");
        entries.add(new SqlInventoryTable(name.schema(), name.object(), "DELETE",
                "DELETE_TABLE_ROWS", filter, List.of()));
        return true;
    }

    private static boolean parseTruncate(String statement, int number,
            List<SqlInventoryTable> entries) {
        Matcher truncate = TRUNCATE.matcher(statement);
        if (!truncate.matches()) return false;
        Name name = name(truncate.group(1));
        entries.add(new SqlInventoryTable(name.schema(), name.object(), "TRUNCATE",
                "TRUNCATE_TABLE", "", List.of()));
        return true;
    }

    private static Match matchAlgorithm(String column, String expression) {
        String compact = expression.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (compact.equals("NULL")) return matched("NULLIFY");
        if (compact.matches("(?s).*\\bSTANDARD_HASH\\s*\\(.*'SHA256'.*")) return matched("HASH_SHA256");
        if (compact.contains("REGEXP_REPLACE") && compact.contains("[[:ALNUM:]]")) {
            Map<String, String> parameters = new LinkedHashMap<>();
            Matcher value = Pattern.compile("(?is)REGEXP_REPLACE\\s*\\([^,]+,\\s*'\\[\\[:alnum:\\]\\]'\\s*,\\s*'([^']*)'").matcher(expression);
            if (value.find()) parameters.put("mask_character", value.group(1));
            return matched("MASK_STRING", parameters);
        }
        if (compact.contains("REGEXP_REPLACE") && compact.contains("[[:DIGIT:]]")
                && compact.contains("SUBSTR")) {
            Map<String, String> parameters = new LinkedHashMap<>();
            putMatch(parameters, "visible_suffix_length", expression,
                    "(?is)\\bWHEN\\s+([0-9]+)\\s*=\\s*0\\b", 1);
            return matched("MASK_PAYMENT_CARD", parameters);
        }
        if (compact.contains("ADD_MONTHS") && compact.contains("TRUNC(") && compact.contains("'YEAR'")) {
            Map<String, String> parameters = new LinkedHashMap<>();
            Matcher values = Pattern.compile("(?is)ADD_MONTHS\\s*\\(.*?,\\s*([0-9]+)\\s*-\\s*1\\s*\\)\\s*\\+\\s*\\(\\s*([0-9]+)\\s*-\\s*1\\s*\\)").matcher(expression);
            if (values.find()) {
                parameters.put("month_number", values.group(1));
                parameters.put("day_number", values.group(2));
            }
            return matched("GENERALIZE_DATE_YEAR", parameters);
        }
        if (compact.contains("TRUNC(") && compact.contains("'MONTH'")) {
            Map<String, String> parameters = new LinkedHashMap<>();
            putMatch(parameters, "day_number", expression,
                    "(?is)'MONTH'\\s*\\)\\s*\\+\\s*\\(\\s*([0-9]+)\\s*-\\s*1\\s*\\)", 1);
            return matched("GENERALIZE_DATE_MONTH", parameters);
        }
        if (compact.contains("DBMS_RANDOM.STRING('L'") && compact.contains("INSTR(")
                && compact.contains("'@'")) {
            Map<String, String> parameters = new LinkedHashMap<>();
            putMatch(parameters, "local_part_length", expression,
                    "(?is)DBMS_RANDOM\\.STRING\\s*\\(\\s*'L'\\s*,\\s*([0-9]+)\\s*\\)", 1);
            return matched("RANDOM_EMAIL_LOCAL_PART", parameters);
        }
        if (compact.contains("DBMS_RANDOM.STRING('L'") && compact.contains("LENGTH(")) return matched("RANDOM_LOWER_STRING");
        if (compact.contains("DBMS_RANDOM.STRING('U'") && compact.contains("LENGTH(")) return matched("RANDOM_UPPER_STRING");
        if (compact.contains("LPAD(TRUNC(DBMS_RANDOM.VALUE") && compact.contains("100000000")) {
            Map<String, String> parameters = new LinkedHashMap<>();
            putMatch(parameters, "prefix", expression,
                    "(?is)ELSE\\s+'([^']+)'\\s*\\|\\|\\s*LPAD", 1);
            return matched("RANDOM_PHONE_FR", parameters);
        }
        if (compact.contains("ROUND(") && compact.contains("DBMS_RANDOM.VALUE(-")) {
            Map<String, String> parameters = new LinkedHashMap<>();
            Matcher values = Pattern.compile("(?is)DBMS_RANDOM\\.VALUE\\s*\\(\\s*-([0-9.]+)\\s*,\\s*([0-9.]+)\\s*\\)\\s*\\)\\s*,\\s*([0-9]+)\\s*\\)").matcher(expression);
            if (values.find()) {
                parameters.put("max_ratio", values.group(1));
                parameters.put("scale", values.group(3));
            }
            return matched("PERTURB_NUMBER_PERCENT", parameters);
        }
        if (compact.matches("(?s).*\\bELSE\\s+0\\s+END.*")) return matched("ZERO_NUMBER");
        if (STRING_LITERAL.matcher(expression.trim()).matches()) {
            return suggested(similarStringAlgorithm(column));
        }
        return new Match("UNRESOLVED", Map.of(), "UNRESOLVED");
    }

    private static String similarStringAlgorithm(String column) {
        String semantic = column.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_");
        if (containsAny(semantic, "FIRST_NAME", "FIRSTNAME", "GIVEN_NAME", "FORENAME", "PRENOM")) {
            return "LOOKUP_FIRST_NAME";
        }
        if (containsAny(semantic, "LAST_NAME", "LASTNAME", "SURNAME", "FAMILY_NAME", "NOM_FAMILLE")) {
            return "LOOKUP_LAST_NAME";
        }
        if (containsAny(semantic, "ADDRESS", "ADRESSE", "STREET", "RUE")) return "LOOKUP_ADDRESS";
        if (containsAny(semantic, "CITY", "VILLE", "TOWN", "LOCALITY")) return "LOOKUP_CITY";
        if (containsAny(semantic, "EMAIL", "E_MAIL", "MAIL")) return "RANDOM_EMAIL_LOCAL_PART";
        if (containsAny(semantic, "PHONE", "TELEPHONE", "TEL", "MOBILE")) return "RANDOM_PHONE_FR";
        if (containsAny(semantic, "CARD", "PAN", "CREDIT_CARD")) return "MASK_PAYMENT_CARD";
        return "MASK_STRING";
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.equals(candidate) || value.startsWith(candidate + "_")
                    || value.endsWith("_" + candidate) || value.contains("_" + candidate + "_")) {
                return true;
            }
        }
        return false;
    }

    private static Match matched(String algorithm) {
        return matched(algorithm, Map.of());
    }

    private static Match matched(String algorithm, Map<String, String> parameters) {
        return new Match(algorithm, parameters, "MATCHED");
    }

    private static Match suggested(String algorithm) {
        return new Match(algorithm, Map.of(), "REVIEW_REQUIRED");
    }

    private static void putMatch(Map<String, String> parameters, String parameterName,
            String expression, String pattern, int group) {
        Matcher matcher = Pattern.compile(pattern).matcher(expression);
        if (matcher.find()) parameters.put(parameterName, matcher.group(group));
    }

    private static int findTopLevelKeyword(String text, String keyword) {
        int depth = 0; boolean quoted = false; boolean identifierQuoted = false;
        for (int index = 0; index <= text.length() - keyword.length(); index++) {
            char current = text.charAt(index);
            if (current == '\'' && !identifierQuoted) {
                if (quoted && index + 1 < text.length() && text.charAt(index + 1) == '\'') { index++; continue; }
                quoted = !quoted;
            }
            if (current == '"' && !quoted) {
                if (identifierQuoted && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    index++;
                    continue;
                }
                identifierQuoted = !identifierQuoted;
            }
            if (quoted || identifierQuoted) continue;
            if (current == '(') depth++;
            else if (current == ')' && depth > 0) depth--;
            if (depth == 0 && text.regionMatches(true, index, keyword, 0, keyword.length())
                    && (index == 0 || !isIdentifierCharacter(text.charAt(index - 1)))
                    && (index + keyword.length() == text.length()
                        || !isIdentifierCharacter(text.charAt(index + keyword.length())))) return index;
        }
        return -1;
    }

    private static int findTopLevelPhrase(String text, String phrase) {
        String expression = java.util.Arrays.stream(phrase.trim().split("\\s+"))
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("\\s+"));
        Matcher matches = Pattern.compile("(?is)(?<![A-Za-z0-9_$#])" + expression
                + "(?![A-Za-z0-9_$#])").matcher(text);
        while (matches.find()) {
            if (isTopLevelPosition(text, matches.start())) return matches.start();
        }
        return -1;
    }

    private static boolean isTopLevelPosition(String text, int position) {
        int depth = 0;
        boolean quoted = false;
        boolean identifierQuoted = false;
        for (int index = 0; index < position; index++) {
            char current = text.charAt(index);
            if (current == '\'' && !identifierQuoted) {
                if (quoted && index + 1 < text.length() && text.charAt(index + 1) == '\'') {
                    index++;
                    continue;
                }
                quoted = !quoted;
            } else if (current == '"' && !quoted) {
                identifierQuoted = !identifierQuoted;
            } else if (!quoted && !identifierQuoted) {
                if (current == '(') depth++;
                else if (current == ')' && depth > 0) depth--;
            }
        }
        return depth == 0 && !quoted && !identifierQuoted;
    }

    private static boolean isIdentifierCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$' || value == '#';
    }

    private static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>(); int start = 0; int depth = 0;
        boolean quoted = false; boolean identifierQuoted = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\'' && !identifierQuoted) {
                if (quoted && index + 1 < text.length() && text.charAt(index + 1) == '\'') index++;
                else quoted = !quoted;
            } else if (current == '"' && !quoted) identifierQuoted = !identifierQuoted;
            else if (!quoted && !identifierQuoted) {
                if (current == '(') depth++; else if (current == ')') depth--;
                else if (current == separator && depth == 0) { parts.add(text.substring(start, index)); start = index + 1; }
            }
        }
        parts.add(text.substring(start)); return parts;
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ");
    }

    private static Name name(String qualified) {
        Matcher variable = SQLPLUS_QUALIFIED_NAME.matcher(qualified);
        if (variable.matches()) {
            // SQL*Plus substitutes &schema. at execution time. Transformer imports are
            // bound to a selected Privonym connection, so the variable owner must not
            // become a literal schema name. The connection's default schema is applied
            // later while the draft inventory is created.
            return new Name("", unquote(variable.group(1)));
        }
        String[] parts = qualified.split("\\s*\\.\\s*", 2);
        return parts.length == 2 ? new Name(unquote(parts[0]), unquote(parts[1]))
                : new Name("", unquote(parts[0]));
    }

    private static String unquote(String identifier) {
        String value = identifier.trim();
        return value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1).replace("\"\"", "\"")
                : value.toUpperCase(Locale.ROOT);
    }

    private static String display(Name name) {
        return name.schema().isBlank() ? name.object() : name.schema() + "." + name.object();
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record Name(String schema, String object) { }
    private record TableKey(String schema, String object) { }
    private record Match(String algorithm, Map<String, String> parameters, String status) { }
    private record ScriptStatement(int number, String sql) { }
    private record DynamicAssignment(String variable, String expression) { }
    private record ScriptSegments(int sourceStatementCount, List<ScriptStatement> statements) {
        private ScriptSegments {
            statements = List.copyOf(statements);
        }
    }
}
