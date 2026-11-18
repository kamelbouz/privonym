package com.privonym.repository.oracle;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
public final class StoppedCancellationIntegrationCheck {
 public static void main(String[] args)throws Exception {
  Properties p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
  String url=p.getProperty("repository.url");if(url==null||url.isBlank())url="jdbc:oracle:thin:@//"+p.getProperty("repository.host")+":"+p.getProperty("repository.port","1521")+"/"+p.getProperty("repository.service");
  try(Connection c=DriverManager.getConnection(url,p.getProperty("repository.user"),p.getProperty("repository.password"))) {
   c.setAutoCommit(false);
   String reference="CANCEL_TEST_"+UUID.randomUUID();
   try {
    try(PreparedStatement q=c.prepareStatement("INSERT INTO anonymization_job(job_reference,ruleset_version_id,data_source_id,status,created_at,started_at,planned_at,heartbeat_at) SELECT ?,ruleset_version_id,data_source_id,'CANCELLING',SYSTIMESTAMP-INTERVAL '2' HOUR,SYSTIMESTAMP-INTERVAL '1' HOUR,SYSTIMESTAMP-INTERVAL '1' HOUR,SYSTIMESTAMP-INTERVAL '1' HOUR FROM anonymization_job WHERE job_id=61")){q.setString(1,reference);if(q.executeUpdate()!=1)throw new AssertionError("Missing fixture source");}
    long id;try(PreparedStatement q=c.prepareStatement("SELECT job_id FROM anonymization_job WHERE job_reference=?")){q.setString(1,reference);try(ResultSet r=q.executeQuery()){r.next();id=r.getLong(1);}}
    JobRecoveryRepository.reconcile(c,id,0,"STOPPED",0,"Synthetic stopped executor verified, no target connection used","RECONCILE JOB "+id+" STOPPED","regression");
    require(status(c,id).equals("CANCELLED"),"Stopped empty cancellation did not finish");
    try(PreparedStatement q=c.prepareStatement("UPDATE anonymization_job SET status='CANCELLING',heartbeat_at=SYSTIMESTAMP-INTERVAL '1' HOUR WHERE job_id=?")){q.setLong(1,id);q.executeUpdate();}
    try(PreparedStatement q=c.prepareStatement("INSERT INTO job_event(job_id,event_timestamp,severity,event_code,message) VALUES(?,SYSTIMESTAMP,'INFO','SCRIPT_PRE_STARTED','synthetic unconfirmed script')")){q.setLong(1,id);q.executeUpdate();}
    JobRecoveryRepository.reconcile(c,id,0,"STOPPED",0,"Synthetic stopped executor, script outcome requires review","RECONCILE JOB "+id+" STOPPED","regression");
    require(status(c,id).equals("EXECUTION_FAILED"),"Incomplete script was incorrectly marked cancelled");
    try(PreparedStatement q=c.prepareStatement("UPDATE anonymization_job SET status='CANCELLING',heartbeat_at=SYSTIMESTAMP WHERE job_id=?")){q.setLong(1,id);q.executeUpdate();}
    boolean rejected=false;try{JobRecoveryRepository.reconcile(c,id,0,"STOPPED",0,"Synthetic fresh owner cannot be confirmed stopped","RECONCILE JOB "+id+" STOPPED","regression");}catch(IllegalStateException expected){rejected=true;}
    require(rejected,"Fresh executor was accepted as stopped");
    System.out.println("Stopped cancellation passed: empty stopped job cancelled, incomplete script kept for recovery, fresh owner rejected.");
   } finally {c.rollback();System.out.println("Synthetic test metadata rolled back; existing jobs unchanged.");}
  }
 }
 static String status(Connection c,long id)throws SQLException {try(PreparedStatement q=c.prepareStatement("SELECT status FROM anonymization_job WHERE job_id=?")){q.setLong(1,id);try(ResultSet r=q.executeQuery()){r.next();return r.getString(1);}}}
 static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
