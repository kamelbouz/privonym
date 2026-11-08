package com.privonym.repository.oracle;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.privonym.adapter.oracle.SqlChecksum;
import com.privonym.application.service.*;
import com.privonym.domain.job.*;
/** Optional real engine test; only a dedicated synthetic table and Job are created and removed. */
public final class OracleChunkRoundTripIntegrationCheck {
 public static void main(String[] args)throws Exception {
  Properties p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
  String url=p.getProperty("repository.url");if(url==null||url.isBlank())url="jdbc:oracle:thin:@//"+p.getProperty("repository.host")+":"+p.getProperty("repository.port","1521")+"/"+p.getProperty("repository.service");
  String user=p.getProperty("repository.user"),password=p.getProperty("repository.password"),tag=UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT),table="PV_CHUNK_"+tag,trigger="PV_FAIL_"+tag,reference="CHUNK_ROUNDTRIP_"+tag;
  long job=0;boolean tableCreated=false;
  try(Connection c=DriverManager.getConnection(url,user,password)) {
   c.setAutoCommit(false);
   try {
    try(Statement q=c.createStatement()){q.execute("CREATE TABLE "+table+"(id NUMBER PRIMARY KEY,masked NUMBER DEFAULT 0 NOT NULL)");tableCreated=true;q.executeUpdate("INSERT INTO "+table+"(id) SELECT LEVEL FROM dual CONNECT BY LEVEL<=18000");}c.commit();
    try(PreparedStatement q=c.prepareStatement("INSERT INTO anonymization_job(job_reference,ruleset_version_id,data_source_id,status,started_at,execution_mode,chunk_size,chunk_threshold) SELECT ? ref,v.ruleset_version_id,r.data_source_id,'RUNNING' state,SYSTIMESTAMP started,'CHUNKED' mode_value,1000 chunk_value,1000 threshold_value FROM ruleset r JOIN ruleset_version v ON v.ruleset_id=r.ruleset_id WHERE v.status='PUBLISHED' ORDER BY v.ruleset_version_id FETCH FIRST 1 ROW ONLY")){q.setString(1,reference);require(q.executeUpdate()==1,"Missing fixture source");}
    job=number(c,"SELECT job_id FROM anonymization_job WHERE job_reference=?",reference);
    String sql="UPDATE "+table+" SET masked=masked+1",digest=SqlChecksum.sha256(sql);
    execute(c,"INSERT INTO job_step(job_id,step_number,step_type,status,generated_code,generated_code_checksum) VALUES(?,10,'ANONYMIZE_TABLE','RUNNING',?,?)",job,sql,digest);
    long step=number(c,"SELECT job_step_id FROM job_step WHERE job_id=?",job);c.commit();
    var plan=new ExecutablePlan(new JobId(job),new PlanChecksum(digest),List.of(new ExecutableStep(step,10,sql,new PlanChecksum(digest))));
    try(Statement q=c.createStatement()){q.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON "+table+" FOR EACH ROW BEGIN IF :OLD.id=1500 THEN RAISE_APPLICATION_ERROR(-20080,'SYNTHETIC_CHUNK_FAILURE');END IF;END;");}
    RuntimeException failure=null;
    try(var executor=new OracleParallelPlanExecutor(url,user,password.toCharArray(),url,user,password.toCharArray(),4,"CHUNKED",1000,1000)){try{executor.execute(plan);}catch(RuntimeException e){failure=e;}}
    require(failure!=null,"Injected first-attempt chunk failure was not detected");
    require(number(c,"SELECT COUNT(*) FROM job_chunk WHERE job_step_id=? AND status='FAILED'",step)>0,"Failed chunk missing");
    require(number(c,"SELECT COUNT(*) FROM job_chunk WHERE job_step_id=? AND status='RUNNING'",step)==0,"Confirmed rollback left an orphan RUNNING chunk");
    Set<Long> succeeded=new HashSet<>();try(PreparedStatement q=c.prepareStatement("SELECT chunk_id FROM job_chunk WHERE job_step_id=? AND status='SUCCEEDED'")){q.setLong(1,step);try(ResultSet r=q.executeQuery()){while(r.next())succeeded.add(r.getLong(1));}}
    require(!succeeded.isEmpty(),"No committed chunks to verify on resume");
    try(Statement q=c.createStatement()){q.execute("DROP TRIGGER "+trigger);}
    execute(c,"UPDATE anonymization_job SET started_at=SYSTIMESTAMP WHERE job_id=?",job);c.commit();
    ExecutionOutcome outcome;try(var executor=new OracleParallelPlanExecutor(url,user,password.toCharArray(),url,user,password.toCharArray(),4,"CHUNKED",1000,1000)){outcome=executor.execute(plan);}
    require(outcome.steps().stream().mapToLong(StepExecutionResult::rowsProcessed).sum()==18000,"Resume row count mismatch");
    require(number(c,"SELECT COUNT(*) FROM "+table+" WHERE masked<>1")==0,"Rows were skipped or masked more than once");
    require(number(c,"SELECT COUNT(*) FROM job_chunk WHERE job_step_id=? AND status='SUCCEEDED'",step)==18,"Expected eighteen successful chunks");
    for(long id:succeeded)require(number(c,"SELECT COUNT(*) FROM job_chunk WHERE chunk_id=? AND status='SUCCEEDED'",id)==1,"Original successful checkpoint was replaced");
    System.out.println("Real Oracle chunk round trip passed: 18000 rows, 18 chunks, injected failure, no orphan RUNNING, successful checkpoints retained and every row modified exactly once after resume.");
   } finally {
    c.rollback();if(job!=0){execute(c,"DELETE FROM anonymization_job WHERE job_id=? AND job_reference=?",job,reference);c.commit();}
    if(tableCreated)try(Statement q=c.createStatement()){q.execute("DROP TABLE "+table+" PURGE");}
    System.out.println("Only the dedicated synthetic Job/table were removed; existing Jobs and business tables were untouched.");
   }
  }
 }
 static long number(Connection c,String sql,Object... values)throws SQLException{try(PreparedStatement q=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);try(ResultSet r=q.executeQuery()){r.next();return r.getLong(1);}}}
 static void execute(Connection c,String sql,Object... values)throws SQLException{try(PreparedStatement q=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);q.executeUpdate();}}
 static void require(boolean yes,String message){if(!yes)throw new AssertionError(message);}
}
