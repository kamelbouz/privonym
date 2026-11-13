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

/** Reconstructs a masking inventory from Oracle masking SQL and structural scripts. */
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
    private static final Pattern MERGE = Pattern.compile(
            "(?is)^\\s*MERGE\\s+INTO\\s+" + QUALIFIED_NAME
                    + "(?:\\s+(?:AS\\s+)?[A-Za-z0-9_$#]+)?\\s+USING\\b");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?is)^\\s*(?:[A-Za-z0-9_$#]+\\.)?(\\\"[^\\\"]+\\\"|[A-Za-z0-9_$#]+)\\s*=\\s*(.+)$");
    private static final Pattern STRING_LITERAL = Pattern.compile("(?is)^N?'(?:''|[^'])*'$");

    private static final Pattern DDL = Pattern.compile("(?is)^(?:ALTER|CREATE|DROP|TRUNCATE|RENAME|COMMENT|GRANT|REVOKE|AUDIT|NOAUDIT|FLASHBACK|PURGE)\\b.*");
    private static final Pattern TABLE_CLEANUP = Pattern.compile(
            "(?is)^BEGIN\\s+FOR\\s+([A-Za-z][A-Za-z0-9_]*)\\s+IN\\s*\\(\\s*SELECT\\s+\\*\\s+FROM\\s+USER_TABLES"
            + "\\s+WHERE\\s+TABLE_NAME\\s+LIKE\\s+'TMP_%'\\s+ORDER\\s+BY\\s+TABLE_NAME\\s*\\)\\s+LOOP"
            + "\\s+EXECUTE\\s+IMMEDIATE\\s+'DROP TABLE '\\s*\\|\\|\\s*\\1\\.TABLE_NAME\\s*\\|\\|\\s*' PURGE'\\s*;"
            + "(?:\\s*DBMS_OUTPUT\\.PUT_LINE\\('-> table '\\s*\\|\\|\\s*\\1\\.TABLE_NAME\\s*\\|\\|\\s*' dropped'\\)\\s*;)?"
            + "\\s*END\\s+LOOP\\s*;\\s*END\\s*;?$");
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
        List<String> post = new ArrayList<>();
        List<String> pre = new ArrayList<>();

        ScriptSegments script = segmentScript(stripDirectives(stripComments(sql),warnings), warnings);
        List<ScriptStatement> statements = script.statements().stream()
                .map(item -> new ScriptStatement(item.number(), stripSchemaPlaceholder(item.sql())))
                .toList();
        Set<Integer> preparation=preparationStatements(statements,warnings);
        Pattern alterTable = Pattern.compile("(?is)^ALTER\\s+TABLE\\s+" + QUALIFIED_NAME + "\\s+.*");
        for (ScriptStatement item : statements) {
            String statement = item.sql();
            if(preparation.contains(item.number())) {
                pre.add(statement.strip()+";");
                warnings.add("[INFO] Statement "+item.number()+": dependent ADD/UPDATE preserved in the generated PRE script");continue;
            }
            Matcher alter = alterTable.matcher(statement);
            if (alter.matches()) {
                List<String> target = OracleImportedExpression.tokens(alter.group(1));
                boolean affectsMasking = statements.stream().anyMatch(next -> next.number() > item.number()
                        && !preparation.contains(next.number()) && targetsTable(next.sql(), target));
                if (affectsMasking) {
                    pre.add(statement.strip() + ";");
                    warnings.add("[INFO] Statement " + item.number() + ": ALTER TABLE preserved in PRE before masking on the same table");
                    if (statements.stream().anyMatch(previous -> previous.number() < item.number()
                            && !preparation.contains(previous.number()) && targetsTable(previous.sql(), target))) {
                        warnings.add("[UNSUPPORTED] Statement " + item.number()
                                + ": ALTER TABLE is between masking operations on the same table and requires preserving their order");
                    }
                    continue;
                }
            }
            if (TABLE_CLEANUP.matcher(statement).matches()) {
                post.add(statement.strip() + ";\n/");
                warnings.add("[INFO] Statement " + item.number() + ": table cleanup block preserved in POST");
                continue;
            }
            Matcher truncate = Pattern.compile("(?is)^TRUNCATE\\s+TABLE\\s+" + QUALIFIED_NAME + "(?:\\s+.*)?$").matcher(statement);
            if (truncate.matches()) {
                List<String> target = OracleImportedExpression.tokens(truncate.group(1));
                boolean usedLater = statements.stream().filter(next -> next.number() > item.number())
                        .anyMatch(next -> targetsTable(next.sql(), target));
                if (usedLater) {
                    pre.add(statement.strip() + ";");
                    warnings.add("[INFO] Statement " + item.number() + ": TRUNCATE precedes masking on this table and was preserved in PRE");
                    continue;
                }
            }
            if (DDL.matcher(statement).matches()) {
                post.add(statement.strip() + ";");
                warnings.add("[INFO] Statement " + item.number() + ": DDL preserved in POST");
                continue;
            }
            if(statement.stripLeading().matches("(?is)^SELECT\\b.*")){warnings.add("[IGNORED] Statement "+item.number()+": reporting SELECT not imported as a masking rule");continue;}
            if (parseUpdate(statement, item.number(), entries, warnings)) continue;
            if (parseMerge(statement, item.number(), entries, warnings)) continue;
            if (parseDelete(statement, item.number(), entries, warnings)) continue;
            warnings.add("[UNSUPPORTED] Statement " + item.number()
                    + " cannot be classified as a masking operation or a phase script");
        }
        List<SqlInventoryTable> applicableEntries = retainApplicableAlgorithms(entries, warnings);
        if (applicableEntries.isEmpty()) warnings.add("No masking operation was detected");
        return new SqlInventoryPreview("ORACLE", sha256(sql), script.sourceStatementCount(),
                applicableEntries, warnings,String.join("\n",pre),String.join("\n",post));
    }

    public static String stripSchemaPlaceholder(String sql) {
        Pattern placeholder = Pattern.compile("(?i)&&?schema\\s*\\.\\s*\\.");
        Matcher matcher = placeholder.matcher(sql);
        StringBuilder result = new StringBuilder(sql.length());
        char quote = 0;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (quote != 0) {
                result.append(current);
                if (current == quote) {
                    if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                        result.append(sql.charAt(++index));
                    } else {
                        quote = 0;
                    }
                }
            } else if (current == '\'' || current == '"') {
                quote = current;
                result.append(current);
            } else if (current == '&' && matcher.region(index, sql.length()).lookingAt()) {
                index = matcher.end() - 1;
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }
    private static boolean targetsTable(String sql, List<String> target) {
        for (Pattern operation : List.of(UPDATE, DELETE, MERGE)) {
            Matcher match = operation.matcher(sql);
            if (match.find() && OracleImportedExpression.tokens(match.group(1)).equals(target)) return true;
        }
        return false;
    }
    private static Set<Integer> preparationStatements(List<ScriptStatement> statements,List<String> warnings) {
        Set<Integer> pre=new HashSet<>();
        Pattern add=Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+"+QUALIFIED_NAME+"\\s+ADD\\s+(.+)$");
        for(int i=0;i<statements.size();i++) {
            Matcher declaration=add.matcher(statements.get(i).sql());if(!declaration.matches())continue;
            String table=String.join(" ",OracleImportedExpression.tokens(declaration.group(1)));
            String body=declaration.group(2).strip();if(body.startsWith("(")&&body.endsWith(")"))body=body.substring(1,body.length()-1);
            Set<String> columns=new HashSet<>();
            for(String definition:splitTopLevel(body,',')) {
                Matcher column=Pattern.compile("^\\s*("+IDENTIFIER+")\\s+.+",Pattern.DOTALL).matcher(definition);
                if(column.matches())columns.add(column.group(1).replace("\"", "").toUpperCase(Locale.ROOT));
            }
            boolean contiguous=true;
            for(int j=i+1;j<statements.size();j++) {
                String next=statements.get(j).sql();if(next.stripLeading().matches("(?is)^SELECT\\b.*"))continue;
                Matcher update=UPDATE.matcher(next);
                boolean same=update.find()&&String.join(" ",OracleImportedExpression.tokens(update.group(1))).equals(table);
                boolean dependent=same&&OracleImportedExpression.tokens(next).stream().anyMatch(t->columns.contains(t.replace("\"", "").toUpperCase(Locale.ROOT)));
                if(!dependent){contiguous=false;continue;}
                boolean earlierUpdate=false;
                for(int k=0;k<i;k++){
                    Matcher before=UPDATE.matcher(statements.get(k).sql());if(before.find()&&String.join(" ",OracleImportedExpression.tokens(before.group(1))).equals(table))earlierUpdate=true;
                    Matcher structural=Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+"+QUALIFIED_NAME+"\\s+.*").matcher(statements.get(k).sql());
                    if(structural.matches()&&String.join(" ",OracleImportedExpression.tokens(structural.group(1))).equals(table))earlierUpdate=true;
                }
                if(!contiguous||earlierUpdate) {
                    warnings.add("[UNSUPPORTED] Statement "+statements.get(j).number()+": ADD/UPDATE dependency crosses other business operations and cannot move to PRE independently");
                } else {pre.add(statements.get(i).number());pre.add(statements.get(j).number());}
            }
        }
        return pre;
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
        List<String> parallelCandidates=new ArrayList<>();
        boolean conditionalParallel=false;
        int sourceNumber = 0;
        for (int index = 0; index < fragments.size(); index++) {
            String raw = fragments.get(index);
            if (raw.stripLeading().matches("(?is)^BEGIN\\s+FOR\\b.*")) {
                for (int size : List.of(3, 4)) {
                    if (index + size > fragments.size()) continue;
                    String block = String.join(";\n", fragments.subList(index, index + size)).strip();
                    if (TABLE_CLEANUP.matcher(block).matches()) {
                        statements.add(new ScriptStatement(++sourceNumber, block));
                        index += size - 1;
                        raw = "";
                        break;
                    }
                }
            }
            String fragment = raw.trim();
            if (fragment.isEmpty()) continue;
            sourceNumber++;
            if(fragment.matches("(?is)(COMMIT|ROLLBACK)\\s*")){warnings.add("[IGNORED] Transaction directive: "+fragment);continue;}
            if (fragment.matches("(?is)^CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE\\s+BODY)\\b.*")) {
                warnings.add("[UNSUPPORTED] Statement " + sourceNumber + ": stored PL/SQL definition requires preserving its complete body");
                continue;
            }
            if(DDL.matcher(fragment).matches()){statements.add(new ScriptStatement(sourceNumber,fragment));continue;}
            if(fragment.stripLeading().toUpperCase(Locale.ROOT).startsWith("EXCEPTION")){warnings.add("[UNSUPPORTED] Exception handling affects execution semantics and requires review");continue;}
            List<String> fragmentTokens=OracleImportedExpression.tokens(fragment);
            if(fragmentTokens.contains("IF")&&!fragmentTokens.get(0).equals("END"))conditionalParallel=true;
            if(fragment.matches("(?is)(?:DECLARE\\s+)?[A-Za-z][A-Za-z0-9_$#]*\\s+(?:VARCHAR2|NUMBER|PLS_INTEGER|INTEGER)(?:\\s*\\([^)]*\\))?\\s*(?::=.*)?")||fragment.matches("(?is)SELECT\\s+TRUNC\\s*\\(\\s*COUNT\\s*\\(\\s*\\*\\s*\\).*\\bINTO\\b.*")) {
                warnings.add("[IGNORED] PL/SQL declaration or chunk-size calculation");
                DynamicAssignment declaration=dynamicAssignment(fragment);if(declaration!=null){String value=evaluateDynamicExpression(declaration.expression(),variables);if(value!=null)variables.put(declaration.variable(),value);}continue;
            }
            Matcher run=Pattern.compile("(?is)\\bDBMS_PARALLEL_EXECUTE\\.RUN_TASK\\s*\\(.*?\\bsql_stmt\\s*=>\\s*([A-Za-z][A-Za-z0-9_$#]*)").matcher(fragment);
            if(run.find()&&fragmentTokens.contains("DBMS_PARALLEL_EXECUTE")) {
                String value=variables.get(run.group(1).toUpperCase(Locale.ROOT));
                if(value==null)throw new IllegalArgumentException("Parallel SQL variable cannot be reconstructed; import rejected");
                value=parallelBusinessSql(value);parallelCandidates.add(value);statements.add(new ScriptStatement(sourceNumber,value));
                warnings.add("[INFO] Statement "+sourceNumber+": DBMS_PARALLEL_EXECUTE orchestration ignored; business UPDATE extracted");continue;
            }
            if(fragmentTokens.contains("DBMS_PARALLEL_EXECUTE")&&fragmentTokens.contains("RUN_TASK")&&!Set.of("UPDATE","DELETE","MERGE","TRUNCATE").contains(fragmentTokens.get(0))){warnings.add("[UNSUPPORTED] Parallel SQL argument could not be statically reconstructed");continue;}
            if(fragmentTokens.contains("DBMS_PARALLEL_EXECUTE")&&!Set.of("UPDATE","DELETE","MERGE","TRUNCATE").contains(fragmentTokens.get(0))) {warnings.add("[INFO] Statement "+sourceNumber+": parallel task/chunk/retry orchestration ignored");continue;}
            DynamicAssignment assignment = dynamicAssignment(fragment);
            if (assignment != null) {
                String value = evaluateDynamicExpression(assignment.expression(), variables);
                if (value == null) {
                    variables.remove(assignment.variable());
                    warnings.add("[INFO] Statement " + sourceNumber + ": PL/SQL variable ignored because its scalar value was not statically reconstructed: "
                            + assignment.variable() + "");
                } else {
                    variables.put(assignment.variable(), value);
                    warnings.add("[INFO] Statement "+sourceNumber+": PL/SQL variable evaluated for static SQL reconstruction, not imported as a rule");
                }
                continue;
            }
            if (containsExecuteImmediate(fragment)) {
                int execute=findKeywordOutsideLiterals(fragment,"EXECUTE");
                if(execute>0&&OracleImportedExpression.tokens(fragment.substring(0,execute)).stream().anyMatch(t->Set.of("IF","LOOP","WHILE","FOR").contains(t))){warnings.add("[UNSUPPORTED] Conditional or repeated dynamic SQL requires preserving its control flow");continue;}
                String dynamic = resolveExecuteImmediate(fragment, variables);
                if (dynamic == null) {
                    warnings.add("[UNSUPPORTED] Statement " + sourceNumber
                            + ": dynamic EXECUTE IMMEDIATE depends on runtime values and requires review");
                } else {
                    if (dynamic.contains("&schema..")) warnings.add("[INFO] Statement " + sourceNumber
                            + ": runtime schema variable was bound to the selected connection schema");
                    if(fragment.stripLeading().toUpperCase(Locale.ROOT).startsWith("ELSE")&&!parallelCandidates.isEmpty()) {
                        String business=parallelBusinessSql(dynamic);
                        var tokens=OracleImportedExpression.tokens(business);
                        int same=-1;for(int i=0;i<parallelCandidates.size();i++)if(OracleImportedExpression.tokens(parallelCandidates.get(i)).equals(tokens)){same=i;break;}
                        if(same<0)throw new IllegalArgumentException("DIRECT and parallel branches differ; schema-dependent business rules require review");
                        parallelCandidates.remove(same);warnings.add("[INFO] Statement "+sourceNumber+": equivalent DIRECT/parallel branches consolidated into one business UPDATE");
                    }else statements.add(new ScriptStatement(sourceNumber, dynamic));
                }
                continue;
            }
            int operation = firstOperationKeyword(fragment);
            if (operation >= 0) {
                if(operation>0&&OracleImportedExpression.tokens(fragment.substring(0,operation)).stream().anyMatch(t->Set.of("IF","LOOP","WHILE","FOR").contains(t))){warnings.add("[UNSUPPORTED] Conditional/repeated business DML requires preserving its control flow");continue;}
                if (operation > 0) warnings.add("Statement " + sourceNumber
                        + ": procedural wrapper was ignored around the masking operation");
                statements.add(new ScriptStatement(sourceNumber, fragment.substring(operation).trim()));
            } else if (!isProceduralControl(fragment)) {
                statements.add(new ScriptStatement(sourceNumber, fragment));
            }
        }
        if(conditionalParallel&&!parallelCandidates.isEmpty())throw new IllegalArgumentException("Schema-dependent parallel branch lacks an equivalent direct branch; import requires review");
        return new ScriptSegments(sourceNumber, statements);
    }

    private static DynamicAssignment dynamicAssignment(String fragment) {
        Matcher matcher = Pattern.compile(
                "(?is)\\b([A-Za-z][A-Za-z0-9_$#]*)(?:\\s+(?:VARCHAR2|NUMBER|INTEGER|PLS_INTEGER)(?:\\s*\\([^)]*\\))?)?\\s*:=\\s*(.+)$").matcher(fragment);
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
        reconstructed=reconstructed.replaceAll("(&&?[A-Za-z][A-Za-z0-9_$#]*)\\.(?!\\.)","$1..");
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
        String prefix=statement.substring(0,update.end());
        Matcher alias=Pattern.compile("(?is)^\\s*UPDATE\\s+"+Pattern.quote(update.group(1))+"\\s+(?:AS\\s+)?("+IDENTIFIER+")\\s+SET\\s*$").matcher(prefix);
        if(alias.matches()&&Pattern.compile("(?i)\\b"+Pattern.quote(alias.group(1))+"\\s*\\.").matcher(filter).find())warnings.add("[UNSUPPORTED] Statement "+number+": correlated target alias requires preserving alias scope");
        if(OracleImportedExpression.tokens(filter).contains("&"))warnings.add("[UNSUPPORTED] Statement "+number+": schema variables in subquery predicates require explicit binding");
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
            if(!expression.equalsIgnoreCase("NULL"))try{OracleImportedExpression.validate(expression);match=matched(OracleImportedExpression.ALGORITHM,Map.of("expression",expression));}catch(IllegalArgumentException unsupported){match=new Match("UNRESOLVED",Map.of(),"UNRESOLVED");warnings.add("Statement "+number+": expression retained for review: "+unsupported.getMessage());}
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
        StringBuilder out=new StringBuilder();boolean literal=false,identifier=false;
        for(int i=0;i<sql.length();i++) {
            char c=sql.charAt(i);
            if(!literal&&!identifier&&i+1<sql.length()&&c=='-'&&sql.charAt(i+1)=='-'){while(i<sql.length()&&sql.charAt(i)!='\n')i++;out.append('\n');continue;}
            if(!literal&&!identifier&&i+1<sql.length()&&c=='/'&&sql.charAt(i+1)=='*'){int end=sql.indexOf("*/",i+2);if(end<0)throw new IllegalArgumentException("Unclosed SQL comment");i=end+1;out.append(' ');continue;}
            out.append(c);
            if(c=='\''&&!identifier){if(literal&&i+1<sql.length()&&sql.charAt(i+1)=='\''){out.append(sql.charAt(++i));}else literal=!literal;}
            else if(c=='"'&&!literal){if(identifier&&i+1<sql.length()&&sql.charAt(i+1)=='"')out.append(sql.charAt(++i));else identifier=!identifier;}
        }
        return out.toString();
    }
    private static String stripDirectives(String sql,List<String> warnings) {
        StringBuilder out=new StringBuilder();boolean literal=false;
        for(String line:sql.split("\\R",-1)) {
            if(!literal&&line.strip().matches("(?i)(WHENEVER\\b.*|DEFINE\\b.*|SET\\s+(SERVEROUTPUT|DEFINE|ECHO|FEEDBACK|VERIFY|PAGESIZE|LINESIZE)\\b.*|PROMPT\\b.*|COMMIT\\s*;?|ROLLBACK\\s*;?|/)") ) {
                warnings.add("[IGNORED] Technical directive: "+line.strip().split("\\s+",2)[0].toUpperCase(Locale.ROOT));out.append('\n');continue;
            }
            out.append(line).append('\n');
            for(int i=0;i<line.length();i++)if(line.charAt(i)=='\''){if(literal&&i+1<line.length()&&line.charAt(i+1)=='\'')i++;else literal=!literal;}
        }
        return out.toString();
    }
    private static String parallelBusinessSql(String sql) {
        int where=findTopLevelKeyword(sql,"WHERE");if(where<0)return sql.strip();
        String filter=sql.substring(where+5).strip();
        if(filter.matches("(?is)ROWID\\s+BETWEEN\\s+:start_id\\s+AND\\s+:end_id"))return sql.substring(0,where).strip();
        if(filter.contains(":start_id")||filter.contains(":end_id"))throw new IllegalArgumentException("Cannot separate technical ROWID boundaries from the business predicate");return sql.strip();
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
