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
  String post="ALTER TABLE &schema..TBL_CASH_MOVEMENT set unused column DE_MOVEMENT;\n"
    +"ALTER TABLE &schema..TBL_CASH_MOVEMENT set unused column DE_MOVEMENT_SUPPL;\n"
    +"ALTER TABLE &schema..TBL_CASH_MOVEMENT ADD (DE_MOVEMENT VARCHAR(255), DE_MOVEMENT_SUPPL VARCHAR(255));";
  var withPost=parser.parse("SELECT TO_CHAR(SYSDATE,'HH24:MI:SS') FROM dual;\nUPDATE &schema..TBL_CASH_MOVEMENT SET DE_MOVEMENT=NULL;\n"+post);
  require(withPost.postScript().equals(post),"Structural POST order or SQL changed");
  require(withPost.columnCount()==1&&withPost.warnings().stream().noneMatch(w->w.startsWith("[UNSUPPORTED]")),"Supported POST/timing statements must not block import");
  var dependency=parser.parse("ALTER TABLE T ADD COPY_FLAG NUMBER; UPDATE T SET COPY_FLAG=1;");
  require(dependency.preScript().equals("ALTER TABLE T ADD COPY_FLAG NUMBER;\nUPDATE T SET COPY_FLAG=1;")&&dependency.postScript().isBlank()&&dependency.columnCount()==0,"Dependent ADD and UPDATE must stay together in PRE");
  var cleanup=parser.parse("""
      SET SERVEROUTPUT ON SIZE UNLIMITED;
      BEGIN
        FOR r IN (SELECT * FROM user_tables WHERE table_name LIKE 'TMP_%' ORDER BY table_name)
        LOOP
          EXECUTE IMMEDIATE 'DROP TABLE ' || r.table_name || ' PURGE';
          DBMS_OUTPUT.PUT_LINE('-> table ' || r.table_name || ' dropped');
        END LOOP;
      END;
      /
      SELECT 'End dropTables' FROM dual;
      """);
  require(cleanup.postScript().startsWith("BEGIN")&&cleanup.postScript().endsWith("END;\n/")
      &&cleanup.postScript().contains("LIKE 'TMP_%'")&&!cleanup.postScript().contains("End dropTables")
      &&cleanup.warnings().stream().noneMatch(w->w.startsWith("[UNSUPPORTED]")),"Complete cleanup loop belongs in POST");
  var crossed=parser.parse("ALTER TABLE T ADD COPY_FLAG NUMBER; UPDATE X SET C=0; UPDATE T SET COPY_FLAG=1;");
  require(crossed.warnings().stream().anyMatch(w->w.startsWith("[UNSUPPORTED]")),"Non-local dependencies require review instead of reordering");
  for(String prefix:List.of("COMMON_","FIRST_NAME_","LAST_NAME_","USER_")) {
   String expression="CONCAT('"+prefix+"',ID_USER)";
   var concatenation=parser.parse("UPDATE T SET NM_USER="+expression+";").tables().get(0).columns().get(0);
   require(concatenation.matchStatus().equals("MATCHED")
     &&concatenation.algorithmCode().equals(OracleImportedExpression.ALGORITHM)
     &&expression.equals(concatenation.parameters().get("expression")),"CONCAT must preserve both the literal prefix and source column");
  }
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
  require(ddl.tables().isEmpty()&&ddl.warnings().stream().filter(w->w.startsWith("[UNSUPPORTED]")).count()==0&&ddl.postScript().contains("DROP COLUMN C;")&&ddl.postScript().contains("ADD C VARCHAR2(20)"),"DROP and ADD must be preserved in POST");
  var conditional=parser.parse("BEGIN IF ENABLED=1 THEN UPDATE T SET C=NULL; END IF;END;");require(conditional.warnings().stream().anyMatch(w->w.startsWith("[UNSUPPORTED]")),"Business condition must not be dropped");
  var correlated=parser.parse("UPDATE T x SET C=0 WHERE EXISTS(SELECT 1 FROM P WHERE P.ID=x.ID);");require(correlated.warnings().stream().anyMatch(w->w.startsWith("[UNSUPPORTED]")),"Unsupported alias scope must be explicit");
  boolean different=false;try{parser.parse(wrapper("T","C='ORIGINAL'").replace(".T SET C=''ORIGINAL'''; END IF", ".T SET C=''OTHER'''; END IF"));}catch(IllegalArgumentException expected){different=true;}require(different,"Different branch transformations must not be consolidated");
  System.out.println("Exact Oracle import passed: seven faithful rules, directive report, CLOB chain, cross-column dependencies and rejection of unsupported/unsafe semantics.");
 }
 static void require(boolean yes,String message){if(!yes)throw new AssertionError(message);}
}
