package com.privonym.application;
import com.privonym.application.service.*;
import java.util.*;
final class ExactOracleImportSmokeCheck {
 private ExactOracleImportSmokeCheck() { }
 static String clob() {
  String expression="CLB_MSG_BODY";
  for(String tag:List.of("95L","95P","95Q","95R","95S"))expression="REGEXP_REPLACE("+expression+",'("+tag+"::.{5})([^:]*)(:)','\\1MASK_"+tag+"'||CHR(10)||'\\3')";
  return "NVL("+expression+",NULL)";
 }
 static String wrapper(String table,String assignment) {
  String sql="UPDATE &schema.."+table+" SET "+assignment;
  return "DECLARE l_schema VARCHAR2(30) := '&schema'; l_sql_stmt VARCHAR2(32767); BEGIN IF l_schema='LOCAL' THEN l_sql_stmt := '"+(sql+" WHERE rowid BETWEEN :start_id AND :end_id").replace("'","''")+"'; DBMS_PARALLEL_EXECUTE.run_task(task_name=>'synthetic',sql_stmt=>l_sql_stmt,parallel_level=>10); ELSE EXECUTE IMMEDIATE 'UPDATE '||l_schema||'."+(table+" SET "+assignment).replace("'","''")+"'; END IF; END;\n/\n";
 }
 static String fixture() {
  String party="'nom : '||SUBSTR(ID_ACCOUNT,1,INSTR(ID_ACCOUNT,SUBSTR(ID_ACCOUNT,-2),-1,1)-1)||' prenom : '||SUBSTR(ID_ACCOUNT,-2)";
  return "WHENEVER SQLERROR EXIT SQL.SQLCODE\ndefine schema=&1\nSET SERVEROUTPUT ON\nUPDATE &schema..PARTY SET NAME="+party+",DESCRIPTION="+party+" WHERE ROLE='CLIENT' OR ROLE='BROKER';\nCOMMIT;\nUPDATE &schema..NARRATIVE SET NOTE='TXT -- /* literal */';\n"+wrapper("SWIFT_MSG","CALLER=NULL,CLB_MSG_BODY="+clob())+wrapper("MESSAGE_LOB","CLB_MSG_BODY="+clob())+"UPDATE &schema..NOTE_TABLE SET NOTE='Note'; COMMIT;";
 }
 static void verify() {
  var parser=new OracleSqlInventoryPreviewService();var p=parser.parse(fixture());
  require(p.tables().size()==5&&p.columnCount()==7,"Five business tables and seven assignments required");
  require(p.tables().get(0).filterExpression().equals("ROLE='CLIENT' OR ROLE='BROKER'"),"Business predicate changed");
  require(p.tables().get(0).columns().get(0).sourceExpression().equals(p.tables().get(0).columns().get(1).sourceExpression()),"Cross-column derivation lost");
  require(p.tables().get(1).columns().get(0).parameters().get("expression").equals("'TXT -- /* literal */'"),"Literal contents were stripped as comments");
  for(var t:p.tables())for(var c:t.columns())require(c.matchStatus().equals("MATCHED"),"Unexpected approximate mapping");
  require(p.tables().get(2).columns().get(1).sourceExpression().equals(clob()),"Nested CLOB chain/backreferences changed");
  require(p.warnings().stream().allMatch(w->w.startsWith("[INFO]")||w.startsWith("[IGNORED]")),"Technical directives should be reported without business review items");
  require(parser.parse("UPDATE T SET C='COMMIT; whenever sqlerror';").tables().get(0).columns().get(0).sourceExpression().equals("'COMMIT; whenever sqlerror'"),"Directive text inside literals must survive");
  var packageLiteral=parser.parse("UPDATE T SET C='DBMS_PARALLEL_EXECUTE.run_task(sql_stmt=>x)';");require(packageLiteral.columnCount()==1,"Orchestration text inside a literal must not discard the UPDATE");
  for(String expression:List.of("(SELECT PASSWORD FROM T)","pkg.mutate(C)","1;DELETE FROM T","1--x","1/*x*/","\"EVIL\"(C)")) {boolean rejected=false;try{OracleImportedExpression.validate(expression);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Unsafe expression accepted: "+expression);}
  var unknownParallel=parser.parse("UPDATE T SET C=NULL; BEGIN DBMS_PARALLEL_EXECUTE.run_task('task','UPDATE X SET C=NULL',1,10);END;");require(unknownParallel.warnings().stream().anyMatch(w->w.startsWith("[UNSUPPORTED]")),"Unresolved parallel SQL must block a partial import");
  var unknownDynamic=parser.parse("UPDATE T SET C=NULL; EXECUTE IMMEDIATE runtime_sql;");require(unknownDynamic.warnings().stream().anyMatch(w->w.startsWith("[UNSUPPORTED]")),"Unresolved dynamic SQL must block a partial import");
  var ddl=parser.parse("WHENEVER SQLERROR EXIT SQL.SQLCODE\nALTER TABLE &schema..T DROP COLUMN C; ALTER TABLE &schema..T ADD C VARCHAR2(20);");
  require(ddl.tables().isEmpty()&&ddl.warnings().stream().filter(w->w.startsWith("[UNSUPPORTED]")).count()==2,"Business DDL must not disappear as a directive");
  var conditional=parser.parse("BEGIN IF ENABLED=1 THEN UPDATE T SET C=NULL; END IF;END;");require(conditional.warnings().stream().anyMatch(w->w.startsWith("[UNSUPPORTED]")),"Business condition must not be dropped");
  var correlated=parser.parse("UPDATE T x SET C=0 WHERE EXISTS(SELECT 1 FROM P WHERE P.ID=x.ID);");require(correlated.warnings().stream().anyMatch(w->w.startsWith("[UNSUPPORTED]")),"Unsupported alias scope must be explicit");
  boolean different=false;try{parser.parse(wrapper("T","C='ORIGINAL'").replace(".T SET C=''ORIGINAL'''; END IF", ".T SET C=''OTHER'''; END IF"));}catch(IllegalArgumentException expected){different=true;}require(different,"Different branch transformations must not be consolidated");
  System.out.println("Exact Oracle import passed: seven faithful rules, directive report, CLOB chain, cross-column dependencies and rejection of unsupported/unsafe semantics.");
 }
 static void require(boolean yes,String message){if(!yes)throw new AssertionError(message);}
}
