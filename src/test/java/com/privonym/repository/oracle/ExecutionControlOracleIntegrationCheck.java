package com.privonym.repository.oracle;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
/** Optional Oracle-only control qualification. Creates and removes its own synthetic Job; no target SQL. */
public final class ExecutionControlOracleIntegrationCheck {
 public static void main(String[] args)throws Exception {
  Properties p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
  String url=p.getProperty("repository.url");if(url==null||url.isBlank())url="jdbc:oracle:thin:@//"+p.getProperty("repository.host")+":"+p.getProperty("repository.port","1521")+"/"+p.getProperty("repository.service");
  final String jdbc=url,user=p.getProperty("repository.user"),password=p.getProperty("repository.password"),reference="CONTROL_PROBE_"+UUID.randomUUID().toString();
  try(Connection c=DriverManager.getConnection(jdbc,user,password)) {
   c.setAutoCommit(false);long id=0;
   try {
    try(PreparedStatement q=c.prepareStatement("INSERT INTO anonymization_job(job_reference,ruleset_version_id,data_source_id,status,started_at,heartbeat_at) SELECT ? probe_reference,v.ruleset_version_id,r.data_source_id,'RUNNING' probe_status,SYSTIMESTAMP probe_started,SYSTIMESTAMP probe_heartbeat FROM ruleset r JOIN ruleset_version v ON v.ruleset_id=r.ruleset_id WHERE v.status='PUBLISHED' ORDER BY v.ruleset_version_id FETCH FIRST 1 ROW ONLY")){q.setString(1,reference);if(q.executeUpdate()!=1)throw new AssertionError("Missing fixture source");}
    try(PreparedStatement q=c.prepareStatement("SELECT job_id FROM anonymization_job WHERE job_reference=?")){q.setString(1,reference);try(ResultSet r=q.executeQuery()){r.next();id=r.getLong(1);}}c.commit();
    try(JobExecutionControl control=new JobExecutionControl(id,c,()->DriverManager.getConnection(jdbc,user,password),null)) {
     for(int i=0;i<8;i++){Thread.sleep(250);control.check();}control.beforeCommit(c);
     var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
     try(Connection waiting=DriverManager.getConnection(jdbc,user,password)) {
      waiting.setAutoCommit(false);
      var future=pool.submit(()->{try{control.beforeCommit(waiting);waiting.rollback();return "OK";}catch(Exception e){throw new IllegalStateException("Competing worker failed waiting for another worker commit",e);}});
      Thread.sleep(6500);c.rollback();future.get();
     }finally{pool.shutdownNow();}
     control.check();
     System.out.println("Real Oracle control passed: a competing worker waits beyond five seconds for the commit fence without false ORA-01013.");
    }
   } finally {
    c.rollback();try(PreparedStatement q=c.prepareStatement("DELETE FROM anonymization_job WHERE job_id=? AND job_reference=?")){q.setLong(1,id);q.setString(2,reference);q.executeUpdate();}c.commit();System.out.println("Synthetic control Job removed; no business SQL or existing Job mutation.");
   }
  }
 }
}
