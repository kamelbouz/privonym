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

/** Reconstructs a masking inventory from Microsoft Transact-SQL. */
public final class SqlServerSqlInventoryPreviewService implements SqlDialectParser {
    private static final String IDENTIFIER = "(?:\\[(?:]]|[^]])+]|\"(?:\"\"|[^\"])+\"|[A-Za-z_#][A-Za-z0-9_@$#]*)";
    private static final String TABLE_REFERENCE = IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + "){0,2}";
    private static final Pattern UPDATE = Pattern.compile(
            "(?is)^\\s*UPDATE\\s+(?:(?:TOP\\s*\\([^)]*\\)\\s*)?)(" + TABLE_REFERENCE + ")\\s+SET\\s+");
    private static final Pattern TRUNCATE = Pattern.compile(
            "(?is)^\\s*TRUNCATE\\s+TABLE\\s+(" + TABLE_REFERENCE + ")(?:\\s+.*)?$");
    private static final Pattern MERGE = Pattern.compile(
            "(?is)^\\s*MERGE(?:\\s+INTO)?\\s+(" + TABLE_REFERENCE + ")"
                    + "(?:\\s+(?:AS\\s+)?(" + IDENTIFIER + "))?\\s+USING\\b");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?is)^\\s*(?:" + IDENTIFIER + "\\s*\\.\\s*)?(" + IDENTIFIER + ")\\s*=\\s*(.+)$");
    private static final Pattern STRING_LITERAL = Pattern.compile("(?is)^N?'(?:''|[^'])*'$");
    private static final Pattern SOURCE = Pattern.compile(
            "(?is)\\b(?:FROM|JOIN)\\s+(" + TABLE_REFERENCE + ")"
                    + "(?:\\s+(?:AS\\s+)?(" + IDENTIFIER + "))?");

    @Override
    public String technology() {
        return "SQLSERVER";
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
            if (parseTruncate(statement, entries)) continue;
            warnings.add("Statement " + item.number()
                    + " is not an UPDATE, MERGE, DELETE or TRUNCATE operation");
        }
        List<SqlInventoryTable> applicable = retainApplicableAlgorithms(entries, warnings);
        if (applicable.isEmpty()) warnings.add("No masking operation was detected");
        return new SqlInventoryPreview("SQLSERVER", sha256(sql),
                script.sourceStatementCount(), applicable, warnings);
    }

    private static ScriptSegments segmentScript(String sql, List<String> warnings) {
        List<String> fragments = splitTopLevel(replaceGoSeparators(sql), ';');
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

            DynamicExecution execution = dynamicExecution(fragment);
            if (execution != null) {
                String dynamic = evaluateDynamicExpression(execution.expression(), variables);
                if (dynamic == null) {
                    warnings.add("Statement " + sourceNumber
                            + ": dynamic EXEC/sp_executesql depends on runtime values and requires review");
                } else {
                    statements.add(new ScriptStatement(sourceNumber, dynamic.trim()));
                }
                continue;
            }

            int operation = firstOperationKeyword(fragment);
            if (operation >= 0) {
                String prefix = fragment.substring(0, operation).trim();
                String operationSql = fragment.substring(operation).trim();
                if (startsWithKeyword(fragment, "WITH")) {
                    operationSql = fragment;
                } else if (!prefix.isEmpty()) {
                    warnings.add("Statement " + sourceNumber
                            + ": procedural wrapper was ignored around the masking operation");
                }
                statements.add(new ScriptStatement(sourceNumber, operationSql));
            } else if (!isControlStatement(fragment)) {
                statements.add(new ScriptStatement(sourceNumber, fragment));
            }
        }
        return new ScriptSegments(sourceNumber, statements);
    }

    private static DynamicAssignment dynamicAssignment(String fragment) {
        Matcher matcher = Pattern.compile(
                "(?is)^(?:DECLARE\\s+)?(@[A-Za-z_][A-Za-z0-9_@$#]*)"
                        + "(?:\\s+[A-Za-z0-9_()\\[\\],]+)?\\s*=\\s*(.+)$")
                .matcher(fragment.trim());
        if (!matcher.matches()) {
            matcher = Pattern.compile(
                    "(?is)^(?:SET|SELECT)\\s+(@[A-Za-z_][A-Za-z0-9_@$#]*)\\s*=\\s*(.+)$")
                    .matcher(fragment.trim());
        }
        return matcher.matches() ? new DynamicAssignment(normalizeVariable(matcher.group(1)),
                matcher.group(2).trim()) : null;
    }

    private static DynamicExecution dynamicExecution(String fragment) {
        Matcher execute = Pattern.compile(
                "(?is)^\\s*EXEC(?:UTE)?\\s*(?:sys\\.)?sp_executesql\\s+(.+)$")
                .matcher(fragment);
        if (execute.matches()) {
            String expression = execute.group(1).trim();
            int parameters = findTopLevelSeparator(expression, ',');
            if (parameters >= 0) expression = expression.substring(0, parameters).trim();
            return new DynamicExecution(expression);
        }
        execute = Pattern.compile("(?is)^\\s*EXEC(?:UTE)?\\s*\\((.+)\\)\\s*$")
                .matcher(fragment);
        return execute.matches() ? new DynamicExecution(execute.group(1).trim()) : null;
    }

    private static String evaluateDynamicExpression(String expression,
            Map<String, String> variables) {
        String valueExpression = stripOuterParentheses(expression.trim());
        List<String> parts = splitConcatenation(valueExpression);
        if (parts.isEmpty()) return null;
        StringBuilder result = new StringBuilder();
        for (String raw : parts) {
            String part = stripOuterParentheses(raw.trim());
            if (STRING_LITERAL.matcher(part).matches()) {
                int quote = part.indexOf('\'');
                result.append(part.substring(quote + 1, part.length() - 1).replace("''", "'"));
                continue;
            }
            String known = variables.get(normalizeVariable(part));
            if (known != null) {
                result.append(known);
            } else if (part.matches("(?i)@[A-Za-z_][A-Za-z0-9_@$#]*")
                    && (part.toUpperCase(Locale.ROOT).contains("SCHEMA")
                        || part.toUpperCase(Locale.ROOT).contains("OWNER"))) {
                result.append("${PRIVONYM_SCHEMA}");
            } else {
                return null;
            }
        }
        String reconstructed = result.toString().replaceAll(
                "(?is)(?:\\[)?\\$\\{PRIVONYM_SCHEMA}(?:])?\\s*\\.\\s*", "");
        return reconstructed.contains("${") ? null : reconstructed;
    }

    private static List<String> splitConcatenation(String expression) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        ScanState state = new ScanState();
        for (int index = 0; index < expression.length(); index++) {
            index = state.accept(expression, index);
            if (state.isCode() && state.depth == 0 && expression.charAt(index) == '+') {
                parts.add(expression.substring(start, index));
                start = index + 1;
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

        String targetToken = update.group(1);
        String remainder = statement.substring(update.end());
        int from = findTopLevelKeyword(remainder, "FROM");
        int where = findTopLevelKeyword(remainder, "WHERE");
        int option = findTopLevelKeyword(remainder, "OPTION");
        int assignmentEnd = minPositive(remainder.length(), from, where, option);
        String assignmentsText = remainder.substring(0, assignmentEnd).trim();
        String fromText = from < 0 ? "" : remainder.substring(from + 4,
                minPositive(remainder.length(), where, option)).trim();
        String filter = where < 0 ? "" : remainder.substring(where + 5,
                minPositive(remainder.length(), option)).trim();

        boolean complex = from >= 0 || cte.present();
        Name target = resolveUpdateTarget(targetToken, fromText, cte);
        if (target == null) {
            warnings.add("Statement " + number
                    + ": UPDATE target alias could not be resolved to a physical table");
            return true;
        }

        List<SqlInventoryColumn> assignments = parseAssignments(assignmentsText, number,
                target, complex, warnings, "UPDATE");
        if (complex) warnings.add("Statement " + number
                + ": UPDATE FROM/CTE rules require review because join semantics cannot be "
                + "converted safely into an independent UPDATE");
        entries.add(new SqlInventoryTable(target.schema(), target.object(), "UPDATE", "",
                filter, assignments));
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
        String updateClause = branch.substring(update + 6 + set + 3).trim();
        int next = findTopLevelKeyword(updateClause, "WHEN");
        if (next >= 0) updateClause = updateClause.substring(0, next).trim();
        int where = findTopLevelKeyword(updateClause, "WHERE");
        String assignmentsText = where < 0 ? updateClause : updateClause.substring(0, where);
        String filter = where < 0 ? "" : updateClause.substring(where + 5).trim();
        List<SqlInventoryColumn> assignments = parseAssignments(assignmentsText, number,
                target, true, warnings, "MERGE UPDATE");
        warnings.add("Statement " + number + ": MERGE rules require review because USING/ON "
                + "semantics cannot be converted safely into an independent UPDATE");
        entries.add(new SqlInventoryTable(target.schema(), target.object(), "UPDATE", "",
                filter, assignments));
        return true;
    }

    private static boolean parseDelete(String original, int number,
            List<SqlInventoryTable> entries, List<String> warnings) {
        CteContext cte = removeCtePrefix(original);
        String statement = cte.statement();
        Matcher direct = Pattern.compile("(?is)^\\s*DELETE\\s+(?:TOP\\s*\\([^)]*\\)\\s+)?"
                + "FROM\\s+(" + TABLE_REFERENCE + ")(.*)$").matcher(statement);
        Name target;
        String tail;
        boolean complex = cte.present();
        if (direct.matches()) {
            target = name(direct.group(1));
            tail = direct.group(2);
        } else {
            Matcher aliased = Pattern.compile("(?is)^\\s*DELETE\\s+(" + IDENTIFIER
                    + ")\\s+FROM\\s+(.+)$").matcher(statement);
            if (!aliased.matches()) return false;
            target = resolveAlias(aliased.group(1), aliased.group(2));
            if (target == null) {
                warnings.add("Statement " + number
                        + ": DELETE target alias could not be resolved to a physical table");
                return true;
            }
            tail = aliased.group(2);
            complex = true;
        }
        int where = findTopLevelKeyword(tail, "WHERE");
        String filter = where < 0 ? "" : tail.substring(where + 5).trim();
        if (complex || !filter.isEmpty()) warnings.add("Statement " + number
                + ": filtered or joined DELETE requires manual review");
        entries.add(new SqlInventoryTable(target.schema(), target.object(), "DELETE",
                "DELETE_TABLE_ROWS", filter, List.of()));
        return true;
    }

    private static boolean parseTruncate(String original, List<SqlInventoryTable> entries) {
        String statement = removeCtePrefix(original).statement();
        Matcher truncate = TRUNCATE.matcher(statement);
        if (!truncate.matches()) return false;
        Name target = name(truncate.group(1));
        entries.add(new SqlInventoryTable(target.schema(), target.object(), "TRUNCATE",
                "TRUNCATE_TABLE", "", List.of()));
        return true;
    }

    private static List<SqlInventoryColumn> parseAssignments(String assignmentsText, int number,
            Name target, boolean forceReview, List<String> warnings, String operation) {
        List<SqlInventoryColumn> assignments = new ArrayList<>();
        for (String item : splitTopLevel(assignmentsText, ',')) {
            Matcher assignment = ASSIGNMENT.matcher(item.trim());
            if (!assignment.matches()) {
                warnings.add("Statement " + number + ": unable to parse an " + operation
                        + " assignment");
                continue;
            }
            String column = unquote(assignment.group(1));
            String expression = assignment.group(2).trim();
            Match match;
            if(expression.equalsIgnoreCase("NULL"))match=matched("NULLIFY");
            else try {
                ImportedSqlExpression.validate("SQLSERVER",expression);
                match=matched(ImportedSqlExpression.algorithm("SQLSERVER"),Map.of("expression",expression));
            } catch(IllegalArgumentException unsupported) {
                match=new Match("UNRESOLVED",Map.of(),"UNRESOLVED");
                warnings.add("Statement "+number+": expression retained for review: "+unsupported.getMessage());
            }
            String status = forceReview && !"UNRESOLVED".equals(match.status())
                    ? "REVIEW_REQUIRED" : match.status();
            if ("UNRESOLVED".equals(status)) warnings.add("Statement " + number
                    + ": no registered algorithm matched " + display(target) + "." + column);
            assignments.add(new SqlInventoryColumn(column, assignments.size() + 1,
                    expression, match.algorithm(), match.parameters(), status, true));
        }
        return assignments;
    }

    private static Match matchAlgorithm(String column, String expression) {
        String compact = expression.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (compact.equals("NULL")) return matched("NULLIFY");
        if (compact.contains("HASHBYTES") && compact.matches(
                "(?s).*['\"]SHA2[_-]?256['\"].*")) return matched("HASH_SHA256");
        if (compact.contains("NEWID()")) return matched("RANDOM_UUID");
        if (compact.contains("DATEFROMPARTS") && compact.contains("YEAR(")) {
            Map<String, String> parameters = new LinkedHashMap<>();
            Matcher values = Pattern.compile(
                    "(?is)DATEFROMPARTS\\s*\\(\\s*YEAR\\s*\\([^)]*\\)\\s*,\\s*([0-9]+)\\s*,\\s*([0-9]+)")
                    .matcher(expression);
            if (values.find()) {
                parameters.put("month_number", values.group(1));
                parameters.put("day_number", values.group(2));
            }
            return matched("GENERALIZE_DATE_YEAR", parameters);
        }
        if (compact.contains("DATEADD") && compact.matches("(?s).*\\bDAY\\b.*")) {
            return matched("DATE_SHIFT");
        }
        if (compact.contains("REPLICATE(")) {
            Map<String, String> parameters = new LinkedHashMap<>();
            Matcher mask = Pattern.compile("(?is)REPLICATE\\s*\\(\\s*N?'([^']+)'\\s*,")
                    .matcher(expression);
            if (mask.find()) parameters.put("mask_character", mask.group(1));
            return matched("MASK_STRING", parameters);
        }
        if (STRING_LITERAL.matcher(expression.trim()).matches()) {
            return new Match(similarStringAlgorithm(column), Map.of(), "REVIEW_REQUIRED");
        }
        return new Match("UNRESOLVED", Map.of(), "UNRESOLVED");
    }

    private static Name resolveUpdateTarget(String token, String fromText, CteContext cte) {
        if (cte.present() && unquote(token).equalsIgnoreCase(cte.alias())
                && cte.source() != null) return cte.source();
        if (token.contains(".") || fromText.isBlank()) return name(token);
        Name alias = resolveAlias(token, fromText);
        return alias == null ? name(token) : alias;
    }

    private static Name resolveAlias(String aliasToken, String sources) {
        String alias = unquote(aliasToken);
        Matcher source = SOURCE.matcher("FROM " + sources.replaceFirst("(?is)^\\s*FROM\\s+", ""));
        Name first = null;
        while (source.find()) {
            Name candidate = name(source.group(1));
            if (first == null) first = candidate;
            String sourceAlias = source.group(2);
            if (sourceAlias != null && alias.equalsIgnoreCase(unquote(sourceAlias))) return candidate;
            if (alias.equalsIgnoreCase(candidate.object())) return candidate;
        }
        return first;
    }

    private static CteContext removeCtePrefix(String original) {
        String statement = original.trim();
        if (!startsWithKeyword(statement, "WITH")) return new CteContext(false, "", null, statement);
        int operation = firstTopLevelDmlAfterCte(statement);
        if (operation < 0) return new CteContext(true, "", null, statement);
        String prefix = statement.substring(0, operation);
        Matcher alias = Pattern.compile("(?is)^\\s*WITH\\s+(" + IDENTIFIER + ")\\s+AS\\s*\\(")
                .matcher(prefix);
        String cteAlias = alias.find() ? unquote(alias.group(1)) : "";
        Matcher source = SOURCE.matcher(prefix);
        Name physical = source.find() ? name(source.group(1)) : null;
        return new CteContext(true, cteAlias, physical, statement.substring(operation).trim());
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
        Matcher matcher = Pattern.compile("(?is)(?<![A-Za-z0-9_@$#])" + expression
                + "(?![A-Za-z0-9_@$#])").matcher(text);
        while (matcher.find()) if (isTopLevelPosition(text, matcher.start())) return matcher.start();
        return -1;
    }

    private static boolean isTopLevelPosition(String text, int position) {
        ScanState state = new ScanState();
        for (int index = 0; index < position; index++) index = state.accept(text, index);
        return state.isCode() && state.depth == 0;
    }

    private static int findTopLevelSeparator(String text, char separator) {
        ScanState state = new ScanState();
        for (int index = 0; index < text.length(); index++) {
            index = state.accept(text, index);
            if (state.isCode() && state.depth == 0 && text.charAt(index) == separator) return index;
        }
        return -1;
    }

    private static boolean matchesKeyword(String text, int index, String keyword) {
        return index >= 0 && index + keyword.length() <= text.length()
                && text.regionMatches(true, index, keyword, 0, keyword.length())
                && (index == 0 || !isIdentifierCharacter(text.charAt(index - 1)))
                && (index + keyword.length() == text.length()
                    || !isIdentifierCharacter(text.charAt(index + keyword.length())));
    }

    private static boolean startsWithKeyword(String text, String keyword) {
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        return matchesKeyword(text, start, keyword);
    }

    private static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        ScanState state = new ScanState();
        for (int index = 0; index < text.length(); index++) {
            index = state.accept(text, index);
            if (state.isCode() && state.depth == 0 && text.charAt(index) == separator) {
                parts.add(text.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static String replaceGoSeparators(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        boolean quoted = false;
        for (String line : sql.split("(?<=\\n)", -1)) {
            if (!quoted && line.matches("(?i)\\s*GO(?:\\s+[0-9]+)?\\s*(?:\\r?\\n)?")) {
                result.append(';');
            } else {
                result.append(line);
                for (int index = 0; index < line.length(); index++) {
                    if (line.charAt(index) == '\'') {
                        if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '\'') index++;
                        else quoted = !quoted;
                    }
                }
            }
        }
        return result.toString();
    }

    private static String stripComments(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        boolean quoted = false;
        boolean bracketed = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (!quoted && !bracketed && current == '-' && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '-') {
                while (index < sql.length() && sql.charAt(index) != '\n') index++;
                result.append('\n');
                continue;
            }
            if (!quoted && !bracketed && current == '/' && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '*') {
                index += 2;
                while (index + 1 < sql.length()
                        && !(sql.charAt(index) == '*' && sql.charAt(index + 1) == '/')) index++;
                index++;
                result.append(' ');
                continue;
            }
            result.append(current);
            if (current == '\'' && !bracketed) {
                if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    result.append(sql.charAt(++index));
                } else quoted = !quoted;
            } else if (!quoted && current == '[') bracketed = true;
            else if (!quoted && bracketed && current == ']') {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == ']') {
                    result.append(sql.charAt(++index));
                } else bracketed = false;
            }
        }
        return result.toString();
    }

    private static Name name(String qualified) {
        List<String> parts = splitQualifiedName(qualified);
        if (parts.size() >= 3) return new Name(unquote(parts.get(parts.size() - 2)),
                unquote(parts.get(parts.size() - 1)));
        if (parts.size() == 2) return new Name(unquote(parts.get(0)), unquote(parts.get(1)));
        return new Name("", unquote(parts.get(0)));
    }

    private static List<String> splitQualifiedName(String value) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        boolean bracketed = false;
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!quoted && current == '[') bracketed = true;
            else if (!quoted && bracketed && current == ']') {
                if (index + 1 < value.length() && value.charAt(index + 1) == ']') index++;
                else bracketed = false;
            } else if (!bracketed && current == '"') quoted = !quoted;
            else if (!bracketed && !quoted && current == '.') {
                parts.add(value.substring(start, index).trim());
                start = index + 1;
            }
        }
        parts.add(value.substring(start).trim());
        return parts;
    }

    private static String unquote(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1).replace("]]", "]");
        }
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value.toUpperCase(Locale.ROOT);
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
        return normalized.matches("(?s)(BEGIN(?: TRY| CATCH)?|END(?: TRY| CATCH)?|COMMIT(?: TRANSACTION)?|ROLLBACK(?: TRANSACTION)?|SET NOCOUNT ON)\\s*")
                || normalized.matches("(?s)DECLARE\\s+@[A-Z_][A-Z0-9_@$#]*\\s+.+");
    }

    private static int minPositive(int fallback, int... candidates) {
        int result = fallback;
        for (int candidate : candidates) if (candidate >= 0 && candidate < result) result = candidate;
        return result;
    }

    private static boolean isIdentifierCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '@'
                || value == '$' || value == '#';
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
        List<SqlInventoryTable> applicable = new ArrayList<>();
        Set<TableKey> emittedTruncates = new HashSet<>();
        for (SqlInventoryTable entry : entries) {
            TableKey key = new TableKey(entry.schema(), entry.name());
            boolean keep = truncated.contains(key)
                    ? "TRUNCATE".equals(entry.operation()) && emittedTruncates.add(key)
                    : !deleted.contains(key) || "DELETE".equals(entry.operation());
            if (keep) applicable.add(entry);
            else warnings.add(display(new Name(key.schema(), key.object()))
                    + ": obsolete operation ignored because "
                    + (truncated.contains(key) ? "TRUNCATE" : "DELETE")
                    + " applies at table level");
        }
        return applicable;
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
        private boolean bracketed;
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
            if (bracketed) {
                if (current == ']') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == ']') return index + 1;
                    bracketed = false;
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
            else if (current == '[') bracketed = true;
            else if (current == '"') identifierQuoted = true;
            else if (current == '(') depth++;
            else if (current == ')' && depth > 0) depth--;
            return index;
        }

        boolean isCode() { return !quoted && !bracketed && !identifierQuoted; }
    }

    private record Name(String schema, String object) { }
    private record TableKey(String schema, String object) { }
    private record Match(String algorithm, Map<String, String> parameters, String status) { }
    private record DynamicAssignment(String variable, String expression) { }
    private record DynamicExecution(String expression) { }
    private record ScriptStatement(int number, String sql) { }
    private record CteContext(boolean present, String alias, Name source, String statement) { }
    private record ScriptSegments(int sourceStatementCount, List<ScriptStatement> statements) {
        private ScriptSegments { statements = List.copyOf(statements); }
    }
}
