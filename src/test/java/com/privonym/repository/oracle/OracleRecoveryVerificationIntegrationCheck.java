package com.privonym.repository.oracle;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.privonym.domain.catalog.DataSourceId;
/** Explicit integration check: only dedicated fixture tables/Jobs are created and removed. */
public final class OracleRecoveryVerificationIntegrationCheck {
 public static void main(String[] args)throws Exception {
  Properties p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
  String url=p.getProperty("repository.url");if(url==null||url.isBlank())url="jdbc:oracle:thin:@//"+p.getProperty("repository.host")+":"+p.getProperty("repository.port","1521")+"/"+p.getProperty("repository.service");
  try(Connection c=DriverManager.getConnection(url,p.getProperty("repository.user"),p.getProperty("repository.password"))) {
   c.setAutoCommit(false);long source,version,schema;String encrypted;
   try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT j.data_source_id,j.ruleset_version_id,ct.catalog_schema_id,co.encrypted_password FROM anonymization_job j JOIN job_step s ON s.job_id=j.job_id JOIN catalog_table ct ON ct.catalog_table_id=s.catalog_table_id JOIN data_source ds ON ds.data_source_id=j.data_source_id JOIN connector co ON co.connector_id=ds.connector_id WHERE j.job_id=121 AND s.job_step_id=111")){if(!r.next())throw new IllegalStateException("Expected inspection fixture source 121/111");source=r.getLong(1);version=r.getLong(2);schema=r.getLong(3);encrypted=r.getString(4);}
   var details=new OraclePlanningAdapter(c).loadConnectionDetails(new DataSourceId(source));char[] password=LocalCredentialStore.decrypt(encrypted);
   String tag=UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT),table="PV_VERIFY_"+tag,ref="RECOVERY_TEST_"+tag;long job=0,catalog=0;boolean created=false;
   try(Connection target=DriverManager.getConnection(details.jdbcUrl(),details.username(),new String(password))) {
    target.setAutoCommit(false);
    try {
     try(Statement q=target.createStatement()){q.execute("CREATE TABLE "+table+"(id NUMBER PRIMARY KEY,value_text VARCHAR2(30))");created=true;q.executeUpdate("INSERT INTO "+table+" SELECT LEVEL,'ORIGINAL' FROM dual CONNECT BY LEVEL<=100");}target.commit();Thread.sleep(3500);
     execute(c,"INSERT INTO catalog_table(catalog_schema_id,table_name) VALUES(?,?)",schema,table);catalog=number(c,"SELECT catalog_table_id FROM catalog_table WHERE catalog_schema_id=? AND table_name=?",schema,table);
     execute(c,"INSERT INTO anonymization_job(job_reference,ruleset_version_id,data_source_id,status,started_at) VALUES(?,?,?,'EXECUTION_FAILED',SYSTIMESTAMP)",ref,version,source);job=number(c,"SELECT job_id FROM anonymization_job WHERE job_reference=?",ref);
     execute(c,"INSERT INTO job_step(job_id,step_number,step_type,catalog_table_id,status) VALUES(?,10,'ANONYMIZE_TABLE',?,'FAILED')",job,catalog);long step=number(c,"SELECT job_step_id FROM job_step WHERE job_id=?",job);
     String end;try(Statement q=target.createStatement();ResultSet r=q.executeQuery("SELECT MAX(ROWIDTOCHAR(ROWID)) FROM "+table)){r.next();end=r.getString(1);}
     execute(c,"INSERT INTO job_chunk(job_step_id,chunk_number,status,end_rowid,rows_processed,started_at) VALUES(?,1,'FAILED',?,0,SYSTIMESTAMP)",step,end);long chunk=number(c,"SELECT chunk_id FROM job_chunk WHERE job_step_id=?",step);c.commit();
     try(var verified=OracleChunkRollbackVerifier.verify(c,job,chunk)){JobRecoveryRepository.reconcile(c,job,chunk,"ROLLBACK",0,verified.evidence(),"RECONCILE JOB "+job+" CHUNK "+chunk,"synthetic-test");c.commit();}
     require(number(c,"SELECT COUNT(*) FROM job_chunk WHERE chunk_id=? AND status='FAILED' AND ended_at IS NOT NULL AND error_message LIKE 'PRIVONYM-ROLLBACK-CONFIRMED:%'",chunk)==1,"Verified rollback did not become retryable");
     execute(c,"UPDATE job_chunk SET status='FAILED',ended_at=NULL,error_message=NULL,started_at=SYSTIMESTAMP WHERE chunk_id=?",chunk);c.commit();
     try(Statement q=target.createStatement()){q.executeUpdate("UPDATE "+table+" SET value_text='CHANGED'");}target.commit();
     boolean rejected=false;try(var verification=OracleChunkRollbackVerifier.verify(c,job,chunk)){verification.evidence();}catch(IllegalStateException expected){rejected=true;}finally{c.rollback();}require(rejected,"Changed target rows were wrongly declared rolled back");
     execute(c,"UPDATE job_chunk SET status='SUCCEEDED',ended_at=SYSTIMESTAMP,rows_processed=100 WHERE chunk_id=?",chunk);c.commit();
     rejected=false;try(var verification=OracleChunkRollbackVerifier.verify(c,job,chunk)){verification.evidence();}catch(IllegalStateException expected){rejected=true;}finally{c.rollback();}require(rejected,"SUCCEEDED checkpoint was not preserved");
     execute(c,"UPDATE job_chunk SET status='RUNNING',ended_at=NULL,error_message=NULL,started_at=TIMESTAMP '1900-01-01 00:00:00' WHERE chunk_id=?",chunk);c.commit();
     rejected=false;try(var verification=OracleChunkRollbackVerifier.verify(c,job,chunk)){verification.evidence();}catch(SQLException|IllegalStateException expected){rejected=true;}finally{c.rollback();}require(rejected,"Unavailable history must not become a rollback confirmation");
     require(number(c,"SELECT COUNT(*) FROM job_chunk WHERE chunk_id=? AND status='RUNNING' AND error_message IS NULL",chunk)==1,"Inconclusive verification modified the checkpoint");
     System.out.println("Oracle recovery verification passed: manual FAILED verified, changed rows rejected, SUCCEEDED preserved, unavailable history rejected without checkpoint mutation.");
    }finally{c.rollback();if(job!=0)execute(c,"DELETE FROM anonymization_job WHERE job_id=? AND job_reference=?",job,ref);if(catalog!=0)execute(c,"DELETE FROM catalog_table WHERE catalog_table_id=? AND table_name=?",catalog,table);c.commit();if(created)try(Statement q=target.createStatement()){q.execute("DROP TABLE "+table+" PURGE");}System.out.println("Dedicated recovery fixtures removed; business tables and existing checkpoints untouched.");}
   }finally{Arrays.fill(password,'\0');}
  }
 }
 static long number(Connection c,String sql,Object... values)throws SQLException{try(PreparedStatement q=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);try(ResultSet r=q.executeQuery()){r.next();return r.getLong(1);}}}
 static void execute(Connection c,String sql,Object... values)throws SQLException{try(PreparedStatement q=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);q.executeUpdate();}}
 static void require(boolean yes,String message){if(!yes)throw new AssertionError(message);}
}
