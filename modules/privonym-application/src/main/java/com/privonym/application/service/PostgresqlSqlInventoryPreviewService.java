package com.privonym.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reconstructs a masking inventory from PostgreSQL SQL and simple PL/pgSQL. */
public final class PostgresqlSqlInventoryPreviewService implements SqlDialectParser {
    private static final String IDENTIFIER = "(?:\"(?:\"\"|[^\"])+\"|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final String TABLE_REFERENCE = IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern UPDATE = Pattern.compile(
            "(?is)^\\s*UPDATE\\s+(?:ONLY\\s+)?(" + TABLE_REFERENCE + ")\\s*(?:\\*\\s*)?"
                    + "(?:AS\\s+" + IDENTIFIER + "\\s+|" + IDENTIFIER + "\\s+)?SET\\s+");
    private static final Pattern MERGE = Pattern.compile(
            "(?is)^\\s*MERGE\\s+INTO\\s+(?:ONLY\\s+)?(" + TABLE_REFERENCE + ")"
                    + "(?:\\s+(?:AS\\s+)?" + IDENTIFIER + ")?\\s+USING\\b");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?is)^\\s*(?:" + IDENTIFIER + "\\s*\\.\\s*)?(" + IDENTIFIER + ")\\s*=\\s*(.+)$");
    private static final Pattern STRING_LITERAL = Pattern.compile("(?is)^(?:[EeUu]&?)?'(?:''|[^'])*'$|^\\$[A-Za-z0-9_]*\\$.*\\$[A-Za-z0-9_]*\\$$");
    private static final Pattern SOURCE = Pattern.compile(
            "(?is)\\b(?:FROM|JOIN|USING)\\s+(?:ONLY\\s+)?(" + TABLE_REFERENCE + ")"
                    + "(?:\\s+(?:AS\\s+)?(" + IDENTIFIER + "))?");

    @Override
    public String technology() {
        return "POSTGRESQL";
    }

    @Override
    public SqlInventoryPreview parse(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL content is required");
        if (sql.length() > 2_000_000) throw new IllegalArgumentException(
                "SQL content exceeds the 2 MB preview limit");

        List<SqlInventoryTable> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        ScriptSegments script = segmentScript(stripComments(sql), warnings);
        for (ScriptStatement item : script.statements()) {
            String statement = item.sql();
            if (parseUpdate(statement, item.number(), entries, warnings)) continue;
            if (parseMerge(statement, item.number(), entries, warnings)) continue;
            if (parseDelete(statement, item.number(), entries, warnings)) continue;
            if (parseTruncate(statement, item.number(), entries, warnings)) continue;
            warnings.add("Statement " + item.number()
                    + " is not an UPDATE, MERGE, DELETE or TRUNCATE operation");
        }
        List<SqlInventoryTable> applicable = retainApplicableAlgorithms(entries, warnings);
        if (applicable.isEmpty()) warnings.add("No masking operation was detected");
        return new SqlInventoryPreview("POSTGRESQL", sha256(sql),
                script.sourceStatementCount(), applicable, warnings);
    }

    private static ScriptSegments segmentScript(String sql, List<String> warnings) {
        String normalized = exposeProceduralBodies(sql);
        List<String> fragments = splitTopLevel(normalized, ';');
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
                } else variables.put(assignment.variable(), value);
                continue;
            }

            DynamicExecution execution = dynamicExecution(fragment);
            if (execution != null) {
                String dynamic = evaluateDynamicExpression(execution.expression(), variables);
                if (dynamic == null) {
                    warnings.add("Statement " + sourceNumber
                            + ": dynamic PL/pgSQL EXECUTE depends on runtime values and requires review");
                } else statements.add(new ScriptStatement(sourceNumber, dynamic.trim()));
                continue;
            }

            int operation = firstOperationKeyword(fragment);
            if (operation >= 0) {
                String prefix = fragment.substring(0, operation).trim();
                String operationSql = startsWithKeyword(fragment, "WITH")
                        ? fragment : fragment.substring(operation).trim();
                if (!prefix.isEmpty() && !startsWithKeyword(fragment, "WITH")) {
                    warnings.add("Statement " + sourceNumber
                            + ": PL/pgSQL wrapper was ignored around the masking operation");
                }
                statements.add(new ScriptStatement(sourceNumber, operationSql));
            } else if (!isControlStatement(fragment)) {
                statements.add(new ScriptStatement(sourceNumber, fragment));
            }
        }
        return new ScriptSegments(sourceNumber, statements);
    }

    /** Makes statements inside DO/function dollar-quoted bodies visible to the statement splitter. */
    private static String exposeProceduralBodies(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        for (int index = 0; index < sql.length();) {
            Matcher delimiter = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*\\$|\\$\\$")
                    .matcher(sql);
            delimiter.region(index, sql.length());
            if (!delimiter.find() || delimiter.start() != index) {
                result.append(sql.charAt(index++));
                continue;
            }
            String tag = delimiter.group();
            int close = sql.indexOf(tag, delimiter.end());
            if (close < 0) {
                result.append(tag);
                index = delimiter.end();
                continue;
            }
            String before = result.toString().stripTrailing().toUpperCase(Locale.ROOT);
            String content = sql.substring(delimiter.end(), close);
            if (before.endsWith("EXECUTE") || before.endsWith("EXECUTE IMMEDIATE")) {
                result.append('\'').append(content.replace("'", "''")).append('\'');
            } else if (before.endsWith("DO") || before.endsWith("AS")) {
                result.append(' ').append(content).append(' ');
            } else {
                // A dollar-quoted value outside a procedural body is a PostgreSQL
                // string literal. Converting it to an equivalent single-quoted
                // literal keeps semicolons inside the value invisible to splitting.
                result.append('\'').append(content.replace("'", "''")).append('\'');
            }
            index = close + tag.length();
        }
        return result.toString();
    }

    private static DynamicAssignment dynamicAssignment(String fragment) {
        Matcher matcher = Pattern.compile(
                "(?is)\\b([A-Za-z_][A-Za-z0-9_$]*)\\s*:=\\s*(.+)$")
                .matcher(fragment);
        return matcher.find() ? new DynamicAssignment(normalizeVariable(matcher.group(1)),
                matcher.group(2).trim()) : null;
    }

    private static DynamicExecution dynamicExecution(String fragment) {
        Matcher matcher = Pattern.compile("(?is)\\bEXECUTE\\s+(.+)$").matcher(fragment);
        if (!matcher.find()) return null;
        String expression = matcher.group(1).trim();
        int using = findTopLevelKeyword(expression, "USING");
        if (using >= 0) expression = expression.substring(0, using).trim();
        return new DynamicExecution(expression);
    }

    private static String evaluateDynamicExpression(String expression,
            Map<String, String> variables) {
        List<String> parts = splitConcatenation(stripOuterParentheses(expression.trim()));
        if (parts.isEmpty()) return null;
        StringBuilder result = new StringBuilder();
        for (String raw : parts) {
            String part = stripOuterParentheses(raw.trim());
            String literal = stringLiteral(part);
            if (literal != null) {
                result.append(literal);
                continue;
            }
            String known = variables.get(normalizeVariable(part));
            if (known != null) result.append(known);
            else if (part.matches("(?i)[A-Za-z_][A-Za-z0-9_$]*")
                    && (part.toUpperCase(Locale.ROOT).contains("SCHEMA")
                        || part.toUpperCase(Locale.ROOT).contains("OWNER"))) {
                result.append("${PRIVONYM_SCHEMA}");
            } else return null;
        }
        String reconstructed = result.toString().replaceAll(
                "(?is)(?:\")?\\$\\{PRIVONYM_SCHEMA}(?:\")?\\s*\\.\\s*", "");
        return reconstructed.contains("${") ? null : reconstructed;
    }

    private static String stringLiteral(String value) {
        Matcher dollar = Pattern.compile("(?s)^\\$([A-Za-z0-9_]*)\\$(.*)\\$\\1\\$$")
                .matcher(value);
        if (dollar.matches()) return dollar.group(2);
        if (!value.matches("(?is)^(?:[EeUu]&?)?'(?:''|[^'])*'$")) return null;
        int quote = value.indexOf('\'');
        return value.substring(quote + 1, value.length() - 1).replace("''", "'");
    }

    private static List<String> splitConcatenation(String expression) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        ScanState state = new ScanState();
        for (int index = 0; index < expression.length() - 1; index++) {
            index = state.accept(expression, index);
            if (state.isCode() && state.depth == 0 && expression.charAt(index) == '|'
                    && expression.charAt(index + 1) == '|') {
                parts.add(expression.substring(start, index));
                start = index + 2;
                index++;
            }
        }
        parts.add(expression.substring(start));
        return parts;
    }

    private static boolean parseUpdate(String original, int number,
            List<SqlInventoryTable> entries, List<String> warnings) {
        CteContext cte = removeCtePrefix(original);
        String statement = cte.statement();
        Matcher update = UPDATE.matcher(statement);
        if (!update.find()) return false;
        Name target = name(update.group(1));
        String remainder = statement.substring(update.end());
        int from = findTopLevelKeyword(remainder, "FROM");
        int where = findTopLevelKeyword(remainder, "WHERE");
        int returning = findTopLevelKeyword(remainder, "RETURNING");
        int assignmentEnd = minPositive(remainder.length(), from, where, returning);
        String assignmentText = remainder.substring(0, assignmentEnd).trim();
        String filter = where < 0 ? "" : remainder.substring(where + 5,
                minPositive(remainder.length(), returning)).trim();
        boolean complex = from >= 0 || cte.present();
        List<SqlInventoryColumn> columns = parseAssignments(assignmentText, number,
                target, complex, warnings, "UPDATE");
        if (complex) warnings.add("Statement " + number
                + ": UPDATE FROM/CTE rules require review because join semantics cannot be "
                + "converted safely into an independent UPDATE");
        entries.add(new SqlInventoryTable(target.schema(), target.object(), "UPDATE", "",
                filter, columns));
        return true;
    }

    private static boolean parseMerge(String original, int number,
            List<SqlInventoryTable> entries, List<String> warnings) {
        String statement = removeCtePrefix(original).statement();
        Matcher merge = MERGE.matcher(statement);
        if (!merge.find()) return false;
        Name target = name(merge.group(1));
        int matched = findTopLevelPhrase(statement, "WHEN MATCHED");
        if (matched < 0) {
            warnings.add("Statement " + number + ": MERGE has no WHEN MATCHED clause");
            return true;
        }
        String branch = statement.substring(matched);
        int update = findTopLevelKeyword(branch, "UPDATE");
        int set = update < 0 ? -1 : findTopLevelKeyword(branch.substring(update + 6), "SET");
        if (update < 0 || set < 0) {
            warnings.add("Statement " + number + ": MERGE has no WHEN MATCHED UPDATE clause");
            return true;
        }
        String clause = branch.substring(update + 6 + set + 3).trim();
        int next = findTopLevelKeyword(clause, "WHEN");
        if (next >= 0) clause = clause.substring(0, next).trim();
        List<SqlInventoryColumn> columns = parseAssignments(clause, number,
                target, true, warnings, "MERGE UPDATE");
        warnings.add("Statement " + number + ": MERGE rules require review because USING/ON "
                + "semantics cannot be converted safely into an independent UPDATE");
        entries.add(new SqlInventoryTable(target.schema(), target.object(), "UPDATE", "",
                "", columns));
        return true;
    }

    private static boolean parseDelete(String original, int number,
            List<SqlInventoryTable> entries, List<String> warnings) {
        CteContext cte = removeCtePrefix(original);
        Matcher delete = Pattern.compile("(?is)^\\s*DELETE\\s+FROM\\s+(?:ONLY\\s+)?("
                + TABLE_REFERENCE + ")\\s*(?:\\*\\s*)?(?:AS\\s+" + IDENTIFIER
                + "\\s+|(?!USING\\b|WHERE\\b|RETURNING\\b)" + IDENTIFIER
                + "\\s+)?(.*)$").matcher(cte.statement());
        if (!delete.matches()) return false;
        Name target = name(delete.group(1));
        String tail = delete.group(2);
        int using = findTopLevelKeyword(tail, "USING");
        int where = findTopLevelKeyword(tail, "WHERE");
        int returning = findTopLevelKeyword(tail, "RETURNING");
        String filter = where < 0 ? "" : tail.substring(where + 5,
                minPositive(tail.length(), returning)).trim();
        if (using >= 0 || cte.present() || !filter.isEmpty()) warnings.add("Statement " + number
                + ": filtered DELETE, DELETE USING or CTE DELETE requires manual review");
        entries.add(new SqlInventoryTable(target.schema(), target.object(), "DELETE",
                "DELETE_TABLE_ROWS", filter, List.of()));
        return true;
    }

    private static boolean parseTruncate(String original, int number,
            List<SqlInventoryTable> entries, List<String> warnings) {
        String statement = removeCtePrefix(original).statement();
        Matcher truncate = Pattern.compile("(?is)^\\s*TRUNCATE\\s+(?:TABLE\\s+)?(.+)$")
                .matcher(statement);
        if (!truncate.matches()) return false;
        String targets = truncate.group(1).trim();
        int option = firstTopLevelPhrase(targets, List.of("RESTART IDENTITY", "CONTINUE IDENTITY",
                "CASCADE", "RESTRICT"));
        if (option >= 0) targets = targets.substring(0, option).trim();
        for (String item : splitTopLevel(targets, ',')) {
            String targetText = item.trim().replaceFirst("(?is)^ONLY\\s+", "")
                    .replaceFirst("\\s*\\*$", "").trim();
            if (!targetText.matches("(?is)^" + TABLE_REFERENCE + "$")) {
                warnings.add("Statement " + number + ": unable to parse TRUNCATE target "
                        + targetText);
                continue;
            }
            Name target = name(targetText);
            entries.add(new SqlInventoryTable(target.schema(), target.object(), "TRUNCATE",
                    "TRUNCATE_TABLE", "", List.of()));
        }
        return true;
    }

    private static List<SqlInventoryColumn> parseAssignments(String text, int number,
            Name target, boolean forceReview, List<String> warnings, String operation) {
        List<SqlInventoryColumn> result = new ArrayList<>();
        for (String item : splitTopLevel(text, ',')) {
            Matcher assignment = ASSIGNMENT.matcher(item.trim());
            if (!assignment.matches()) {
                warnings.add("Statement " + number + ": unable to parse an " + operation
                        + " assignment");
                continue;
            }
            String column = unquote(assignment.group(1));
            String expression = assignment.group(2).trim();
            Match match = matchAlgorithm(column, expression);
            String status = forceReview && !"UNRESOLVED".equals(match.status())
                    ? "REVIEW_REQUIRED" : match.status();
            if ("UNRESOLVED".equals(status)) warnings.add("Statement " + number
                    + ": no registered algorithm matched " + display(target) + "." + column);
            result.add(new SqlInventoryColumn(column, result.size() + 1, expression,
                    match.algorithm(), match.parameters(), status, true));
        }
        return result;
    }

    private static Match matchAlgorithm(String column, String expression) {
        String compact = expression.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (compact.equals("NULL")) return matched("NULLIFY");
        if ((compact.contains("DIGEST(") && compact.matches("(?s).*['\"]SHA-?256['\"].*"))
                || compact.contains("SHA256(")) return matched("HASH_SHA256");
        if (compact.contains("GEN_RANDOM_UUID()") || compact.contains("UUID_GENERATE_V4()")) {
            return matched("RANDOM_UUID");
        }
        if (compact.contains("REGEXP_REPLACE") && compact.contains("[[:ALNUM:]]")) {
            Map<String, String> parameters = new LinkedHashMap<>();
            Matcher mask = Pattern.compile("(?is)REGEXP_REPLACE\\s*\\([^,]+,\\s*'\\[\\[:alnum:\\]\\]'\\s*,\\s*'([^']*)'")
                    .matcher(expression);
            if (mask.find()) parameters.put("mask_character", mask.group(1));
            return matched("MASK_STRING", parameters);
        }
        if (compact.contains("DATE_TRUNC('YEAR'") || compact.contains("DATE_TRUNC( 'YEAR'")) {
            return matched("GENERALIZE_DATE_YEAR");
        }
        if (compact.contains("DATE_TRUNC('MONTH'") || compact.contains("DATE_TRUNC( 'MONTH'")) {
            return matched("GENERALIZE_DATE_MONTH");
        }
        if (compact.matches("(?s).*INTERVAL\\s+'[-+]?[0-9]+\\s+DAY[S]?'.*")) {
            return matched("DATE_SHIFT");
        }
        if (STRING_LITERAL.matcher(expression.trim()).matches()) {
            return new Match(similarStringAlgorithm(column), Map.of(), "REVIEW_REQUIRED");
        }
        return new Match("UNRESOLVED", Map.of(), "UNRESOLVED");
    }

    private static CteContext removeCtePrefix(String original) {
        String statement = original.trim();
        if (!startsWithKeyword(statement, "WITH")) return new CteContext(false, statement);
        int operation = firstTopLevelDmlAfterCte(statement);
        return operation < 0 ? new CteContext(true, statement)
                : new CteContext(true, statement.substring(operation).trim());
    }

    private static int firstTopLevelDmlAfterCte(String text) {
        ScanState state = new ScanState();
        for (int index = 0; index < text.length(); index++) {
            index = state.accept(text, index);
            if (!state.isCode() || state.depth != 0) continue;
            for (String keyword : List.of("UPDATE", "DELETE", "MERGE", "TRUNCATE")) {
                if (matchesKeyword(text, index, keyword)) return index;
            }
        }
        return -1;
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
        ScanState state = new ScanState();
        for (int index = 0; index <= text.length() - keyword.length(); index++) {
            index = state.accept(text, index);
            if (state.isCode() && matchesKeyword(text, index, keyword)) return index;
        }
        return -1;
    }

    private static int findTopLevelKeyword(String text, String keyword) {
        ScanState state = new ScanState();
        for (int index = 0; index <= text.length() - keyword.length(); index++) {
            index = state.accept(text, index);
            if (state.isCode() && state.depth == 0 && matchesKeyword(text, index, keyword)) return index;
        }
        return -1;
    }

    private static int findTopLevelPhrase(String text, String phrase) {
        String expression = java.util.Arrays.stream(phrase.trim().split("\\s+"))
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("\\s+"));
        Matcher matcher = Pattern.compile("(?is)(?<![A-Za-z0-9_$])" + expression
                + "(?![A-Za-z0-9_$])").matcher(text);
        while (matcher.find()) if (isTopLevelPosition(text, matcher.start())) return matcher.start();
        return -1;
    }

    private static int firstTopLevelPhrase(String text, List<String> phrases) {
        int result = -1;
        for (String phrase : phrases) {
            int candidate = findTopLevelPhrase(text, phrase);
            if (candidate >= 0 && (result < 0 || candidate < result)) result = candidate;
        }
        return result;
    }

    private static boolean isTopLevelPosition(String text, int position) {
        ScanState state = new ScanState();
        for (int index = 0; index < position; index++) index = state.accept(text, index);
        return state.isCode() && state.depth == 0;
    }

    private static boolean matchesKeyword(String text, int index, String keyword) {
        return index >= 0 && index + keyword.length() <= text.length()
                && text.regionMatches(true, index, keyword, 0, keyword.length())
                && (index == 0 || !isIdentifierCharacter(text.charAt(index - 1)))
                && (index + keyword.length() == text.length()
                    || !isIdentifierCharacter(text.charAt(index + keyword.length())));
    }

    private static boolean startsWithKeyword(String text, String keyword) {
        int index = 0;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        return matchesKeyword(text, index, keyword);
    }

    private static List<String> splitTopLevel(String text, char separator) {
        List<String> result = new ArrayList<>();
        int start = 0;
        ScanState state = new ScanState();
        for (int index = 0; index < text.length(); index++) {
            index = state.accept(text, index);
            if (state.isCode() && state.depth == 0 && text.charAt(index) == separator) {
                result.add(text.substring(start, index));
                start = index + 1;
            }
        }
        result.add(text.substring(start));
        return result;
    }

    private static String stripComments(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        boolean quoted = false;
        boolean identifierQuoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (!quoted && !identifierQuoted && current == '-' && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '-') {
                while (index < sql.length() && sql.charAt(index) != '\n') index++;
                result.append('\n');
                continue;
            }
            if (!quoted && !identifierQuoted && current == '/' && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '*') {
                index += 2;
                int depth = 1;
                while (index < sql.length() && depth > 0) {
                    if (index + 1 < sql.length() && sql.charAt(index) == '/'
                            && sql.charAt(index + 1) == '*') { depth++; index += 2; continue; }
                    if (index + 1 < sql.length() && sql.charAt(index) == '*'
                            && sql.charAt(index + 1) == '/') { depth--; index += 2; continue; }
                    index++;
                }
                index--;
                result.append(' ');
                continue;
            }
            result.append(current);
            if (current == '\'' && !identifierQuoted) {
                if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    result.append(sql.charAt(++index));
                } else quoted = !quoted;
            } else if (!quoted && current == '"') {
                if (identifierQuoted && index + 1 < sql.length() && sql.charAt(index + 1) == '"') {
                    result.append(sql.charAt(++index));
                } else identifierQuoted = !identifierQuoted;
            }
        }
        return result.toString();
    }

    private static Name name(String qualified) {
        List<String> parts = splitQualifiedName(qualified);
        return parts.size() == 2 ? new Name(unquote(parts.get(0)), unquote(parts.get(1)))
                : new Name("", unquote(parts.get(0)));
    }

    private static List<String> splitQualifiedName(String value) {
        List<String> result = new ArrayList<>();
        int start = 0;
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '"') index++;
                else quoted = !quoted;
            } else if (!quoted && current == '.') {
                result.add(value.substring(start, index).trim());
                start = index + 1;
            }
        }
        result.add(value.substring(start).trim());
        return result;
    }

    private static String unquote(String identifier) {
        String value = identifier.trim();
        return value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1).replace("\"\"", "\"")
                : value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeVariable(String variable) {
        return variable == null ? "" : variable.trim().toUpperCase(Locale.ROOT);
    }

    private static String stripOuterParentheses(String value) {
        String result = value;
        while (hasWrappingParentheses(result)) result = result.substring(1,
                result.length() - 1).trim();
        return result;
    }

    private static boolean hasWrappingParentheses(String value) {
        if (!value.startsWith("(") || !value.endsWith(")")) return false;
        ScanState state = new ScanState();
        for (int index = 0; index < value.length(); index++) {
            index = state.accept(value, index);
            if (state.isCode() && state.depth == 0 && index < value.length() - 1) return false;
        }
        return state.depth == 0 && state.isCode();
    }

    private static String similarStringAlgorithm(String column) {
        String semantic = column.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        if (containsAny(semantic, "FIRST_NAME", "FIRSTNAME", "GIVEN_NAME", "FORENAME", "PRENOM")) return "LOOKUP_FIRST_NAME";
        if (containsAny(semantic, "LAST_NAME", "LASTNAME", "SURNAME", "FAMILY_NAME", "NOM_FAMILLE")) return "LOOKUP_LAST_NAME";
        if (containsAny(semantic, "ADDRESS", "ADRESSE", "STREET", "RUE")) return "LOOKUP_ADDRESS";
        if (containsAny(semantic, "CITY", "VILLE", "TOWN", "LOCALITY")) return "LOOKUP_CITY";
        if (containsAny(semantic, "EMAIL", "E_MAIL", "MAIL")) return "RANDOM_EMAIL_LOCAL_PART";
        if (containsAny(semantic, "PHONE", "TELEPHONE", "TEL", "MOBILE")) return "RANDOM_PHONE_FR";
        if (containsAny(semantic, "CARD", "PAN", "CREDIT_CARD")) return "MASK_PAYMENT_CARD";
        return "MASK_STRING";
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.equals(candidate)
                || value.startsWith(candidate + "_") || value.endsWith("_" + candidate)
                || value.contains("_" + candidate + "_")) return true;
        return false;
    }

    private static Match matched(String algorithm) { return matched(algorithm, Map.of()); }
    private static Match matched(String algorithm, Map<String, String> parameters) {
        return new Match(algorithm, parameters, "MATCHED");
    }

    private static boolean isControlStatement(String fragment) {
        String normalized = fragment.stripLeading().toUpperCase(Locale.ROOT);
        return normalized.matches("(?s)(BEGIN|END|ELSE|COMMIT|ROLLBACK|LANGUAGE\\s+PLPGSQL)\\s*")
                || normalized.startsWith("DO ") || normalized.startsWith("CREATE FUNCTION ")
                || normalized.startsWith("CREATE OR REPLACE FUNCTION ");
    }

    private static int minPositive(int fallback, int... candidates) {
        int result = fallback;
        for (int candidate : candidates) if (candidate >= 0 && candidate < result) result = candidate;
        return result;
    }

    private static boolean isIdentifierCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private static String display(Name name) {
        return name.schema().isBlank() ? name.object() : name.schema() + "." + name.object();
    }

    private static List<SqlInventoryTable> retainApplicableAlgorithms(
            List<SqlInventoryTable> entries, List<String> warnings) {
        Set<TableKey> truncated = new HashSet<>();
        Set<TableKey> deleted = new HashSet<>();
        for (SqlInventoryTable entry : entries) {
            TableKey key = new TableKey(entry.schema(), entry.name());
            if ("TRUNCATE".equals(entry.operation())) truncated.add(key);
            else if ("DELETE".equals(entry.operation())) deleted.add(key);
        }
        List<SqlInventoryTable> result = new ArrayList<>();
        Set<TableKey> emittedTruncates = new HashSet<>();
        for (SqlInventoryTable entry : entries) {
            TableKey key = new TableKey(entry.schema(), entry.name());
            boolean keep = truncated.contains(key)
                    ? "TRUNCATE".equals(entry.operation()) && emittedTruncates.add(key)
                    : !deleted.contains(key) || "DELETE".equals(entry.operation());
            if (keep) result.add(entry);
            else warnings.add(display(new Name(key.schema(), key.object()))
                    + ": obsolete operation ignored because "
                    + (truncated.contains(key) ? "TRUNCATE" : "DELETE")
                    + " applies at table level");
        }
        return result;
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static final class ScanState {
        private boolean quoted;
        private boolean identifierQuoted;
        private int depth;

        int accept(String text, int index) {
            char current = text.charAt(index);
            if (quoted) {
                if (current == '\'') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '\'') return index + 1;
                    quoted = false;
                }
                return index;
            }
            if (identifierQuoted) {
                if (current == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') return index + 1;
                    identifierQuoted = false;
                }
                return index;
            }
            if (current == '\'') quoted = true;
            else if (current == '"') identifierQuoted = true;
            else if (current == '(') depth++;
            else if (current == ')' && depth > 0) depth--;
            return index;
        }

        boolean isCode() { return !quoted && !identifierQuoted; }
    }

    private record Name(String schema, String object) { }
    private record TableKey(String schema, String object) { }
    private record Match(String algorithm, Map<String, String> parameters, String status) { }
    private record DynamicAssignment(String variable, String expression) { }
    private record DynamicExecution(String expression) { }
    private record ScriptStatement(int number, String sql) { }
    private record CteContext(boolean present, String statement) { }
    private record ScriptSegments(int sourceStatementCount, List<ScriptStatement> statements) {
        private ScriptSegments { statements = List.copyOf(statements); }
    }
}
