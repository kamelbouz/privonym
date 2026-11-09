package com.privonym.application.service;
import java.util.*;
/** Restricted scalar expressions only: no arbitrary packages, subqueries or statements. */
public final class OracleImportedExpression {
 public static final String ALGORITHM="ORACLE_EXPRESSION_EXACT";
 private static final Set<String> FUNCTIONS=Set.of("SUBSTR","SUBSTRB","INSTR","LENGTH","LENGTHB","NVL","NVL2","COALESCE","NULLIF","REGEXP_REPLACE","REGEXP_SUBSTR","REGEXP_INSTR","CHR","LOWER","UPPER","TRIM","LTRIM","RTRIM","REPLACE","TRANSLATE","TO_CHAR","TO_DATE","TO_NUMBER","TO_CLOB","ROUND","TRUNC","MOD","ABS","CEIL","FLOOR","DECODE","GREATEST","LEAST","ADD_MONTHS","STANDARD_HASH");
 private static final Set<String> FORBIDDEN=Set.of("SELECT","INSERT","UPDATE","DELETE","MERGE","DROP","ALTER","CREATE","COMMIT","ROLLBACK","EXECUTE","GRANT","REVOKE","UNION","CONNECT","PRIOR","NEXTVAL","CURRVAL");
 private OracleImportedExpression() { }
 public static String validate(String expression) {
  if(expression==null||expression.isBlank()||expression.length()>4000||expression.contains("${"))throw new IllegalArgumentException("Imported Oracle expression must contain 1–4000 characters and no template tokens");
  List<String> tokens=tokens(expression);int depth=0;
  for(int i=0;i<tokens.size();i++) {
   String t=tokens.get(i);if(i+1<tokens.size()&&(t.equals("-")&&tokens.get(i+1).equals("-")||t.equals("/")&&tokens.get(i+1).equals("*")))throw new IllegalArgumentException("SQL comments are not allowed in stored scalar expressions");
   if(t.startsWith("\"")&&i+1<tokens.size()&&tokens.get(i+1).equals("("))throw new IllegalArgumentException("Quoted function calls are not supported");
   if(t.startsWith("'")||t.startsWith("\""))continue;
   if(FORBIDDEN.contains(t)||Set.of(";",":","&",".").contains(t))throw new IllegalArgumentException("Unsupported token in imported Oracle expression: "+t);
   if(t.matches("[A-Z_][A-Z0-9_$#]*")&&i+1<tokens.size()&&tokens.get(i+1).equals("(")&&!FUNCTIONS.contains(t)&&!Set.of("IN","CASE","WHEN","THEN","ELSE","AND","OR","NOT").contains(t))throw new IllegalArgumentException("Unsupported function in imported Oracle expression: "+t);
   if(t.equals("("))depth++;if(t.equals(")")&&--depth<0)throw new IllegalArgumentException("Unbalanced imported expression");
   if(!t.matches("[A-Z0-9_$#]+|[()+*/|=,<>!-]"))throw new IllegalArgumentException("Unsupported expression token: "+t);
  }
  if(depth!=0)throw new IllegalArgumentException("Unbalanced imported expression");return expression;
 }
 public static List<String> tokens(String sql) {
  List<String> result=new ArrayList<>();
  for(int i=0;i<sql.length();) {
   char c=sql.charAt(i);if(Character.isWhitespace(c)){i++;continue;}
   int start=i++;
   if(c=='\''||c=='"') {boolean closed=false;while(i<sql.length()){if(sql.charAt(i++)==c){if(i<sql.length()&&sql.charAt(i)==c){i++;continue;}closed=true;break;}}if(!closed)throw new IllegalArgumentException("Unclosed SQL literal/identifier");result.add(sql.substring(start,i));}
   else if(Character.isLetterOrDigit(c)||c=='_'||c=='$'||c=='#'){while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||"_$#".indexOf(sql.charAt(i))>=0))i++;result.add(sql.substring(start,i).toUpperCase(Locale.ROOT));}
   else result.add(String.valueOf(c));
  }
  return List.copyOf(result);
 }
}
