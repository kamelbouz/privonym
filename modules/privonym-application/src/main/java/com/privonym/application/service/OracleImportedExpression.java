package com.privonym.application.service;
import java.util.*;
/** Restricted scalar expressions only: no arbitrary packages, subqueries or statements. */
public final class OracleImportedExpression {
 public static final String ALGORITHM="ORACLE_EXPRESSION_EXACT";
 private OracleImportedExpression() { }
 public static String validate(String expression) {
  return ImportedSqlExpression.validate("ORACLE",expression);
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
