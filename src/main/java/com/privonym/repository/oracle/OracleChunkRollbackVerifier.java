package com.privonym.repository.oracle;
import java.sql.*;
import java.util.*;
import com.privonym.domain.catalog.DataSourceId;
/** Proves equality against pre-chunk history; never infers rollback from a status or zero rows. */
final class OracleChunkRollbackVerifier implements AutoCloseable {
 private final Connection target;
 private final String evidence;
 private OracleChunkRollbackVerifier(Connection target,String evidence){this.target=target;this.evidence=evidence;}
 String evidence(){return evidence;}
 static OracleChunkRollbackVerifier verify(Connection repository,long job,long chunk)throws SQLException {
  try(PreparedStatement q=repository.prepareStatement("SELECT status FROM anonymization_job WHERE job_id=? FOR UPDATE")){q.setLong(1,job);try(ResultSet r=q.executeQuery()){if(!r.next()||!JobRecoveryRepository.terminal(r.getString(1)))throw new IllegalStateException("Stop the Job before historical verification");}}
  String schema,table,start,end,encrypted;Timestamp at;long source;
  try(PreparedStatement q=repository.prepareStatement("SELECT cs.schema_name,ct.table_name,c.start_rowid,c.end_rowid,c.started_at,j.data_source_id,co.encrypted_password,c.status,c.error_message,c.ended_at FROM job_chunk c JOIN job_step s ON s.job_step_id=c.job_step_id JOIN anonymization_job j ON j.job_id=s.job_id JOIN catalog_table ct ON ct.catalog_table_id=s.catalog_table_id JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id JOIN data_source ds ON ds.data_source_id=j.data_source_id JOIN connector co ON co.connector_id=ds.connector_id WHERE c.chunk_id=? AND j.job_id=? FOR UPDATE OF c.status")) {
   q.setLong(1,chunk);q.setLong(2,job);try(ResultSet r=q.executeQuery()){if(!r.next())throw new IllegalArgumentException("Chunk does not belong to this Job");if(ChunkRecoveryState.resolved(r.getString(8),r.getString(9),r.getTimestamp(10)!=null))throw new IllegalStateException("Checkpoint already resolved and preserved");schema=r.getString(1);table=r.getString(2);start=r.getString(3);end=r.getString(4);at=r.getTimestamp(5);source=r.getLong(6);encrypted=r.getString(7);}
  }
  var details=new OraclePlanningAdapter(repository).loadConnectionDetails(new DataSourceId(source));
  if(!details.technologyCode().equals("ORACLE")||end==null||end.equals("DIRECT")||end.equals("DIRECT_TX")||at==null)throw new IllegalStateException("Automatic historical verification currently requires an Oracle ROWID chunk");
  char[] password=LocalCredentialStore.decrypt(encrypted);Connection target=null;
  try {
   target=DriverManager.getConnection(details.jdbcUrl(),details.username(),new String(password));target.setAutoCommit(false);target.setNetworkTimeout(Runnable::run,120000);
   if(!database(repository).equals(database(target)))throw new IllegalStateException("Automatic timestamp verification requires target and repository in the same Oracle database/container");
   String qualified=quote(schema)+"."+quote(table);List<String> columns=new ArrayList<>();
   try(ResultSet c=target.getMetaData().getColumns(null,schema,table,null)){while(c.next()) {
    int type=c.getInt("DATA_TYPE");if(!List.of(Types.CHAR,Types.VARCHAR,Types.NCHAR,Types.NVARCHAR,Types.NUMERIC,Types.DECIMAL,Types.INTEGER,Types.BIGINT,Types.SMALLINT,Types.FLOAT,Types.REAL,Types.DOUBLE,Types.DATE,Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE,Types.BINARY,Types.VARBINARY).contains(type))throw new IllegalStateException("Historical comparison does not support target column type "+c.getString("TYPE_NAME")+"; no checkpoint changed");columns.add(quote(c.getString("COLUMN_NAME")));
   }}
   if(columns.isEmpty())throw new IllegalStateException("Target column metadata unavailable");
   try(Statement lock=target.createStatement()){lock.setQueryTimeout(5);lock.execute("LOCK TABLE "+qualified+" IN SHARE MODE NOWAIT");}
   String range="ROWID<=CHARTOROWID(?)"+(start==null?"":" AND ROWID>CHARTOROWID(?)");
   String equal=String.join(" AND ",columns.stream().map(c->"DECODE(n."+c+",o."+c+",1,0)=1").toList());
   String sql="WITH old_rows AS(SELECT ROWID rid,"+String.join(",",columns)+" FROM "+qualified+" AS OF TIMESTAMP ? WHERE "+range+"),new_rows AS(SELECT ROWID rid,"+String.join(",",columns)+" FROM "+qualified+" WHERE "+range+") SELECT COUNT(*),COALESCE(SUM(CASE WHEN o.rid IS NULL OR n.rid IS NULL THEN 1 WHEN "+equal+" THEN 0 ELSE 1 END),0) FROM old_rows o FULL OUTER JOIN new_rows n ON n.rid=o.rid";
   long rows,changed;
   try(PreparedStatement q=target.prepareStatement(sql)){q.setQueryTimeout(90);int i=1;q.setTimestamp(i++,at);q.setString(i++,end);if(start!=null)q.setString(i++,start);q.setString(i++,end);if(start!=null)q.setString(i,start);try(ResultSet r=q.executeQuery()){r.next();rows=r.getLong(1);changed=r.getLong(2);}}
   if(rows==0||changed!=0)throw new IllegalStateException("Historical verification inconclusive: rows="+rows+", changed/missing="+changed+". No checkpoint changed");
   return new OracleChunkRollbackVerifier(target,"Oracle Flashback verified "+rows+" rows unchanged across every supported column since chunk start "+at+"; shared target lock held until repository decision commit");
  }catch(SQLException|RuntimeException e){if(target!=null)try{target.rollback();target.close();}catch(SQLException close){e.addSuppressed(close);}throw e;}
  finally{Arrays.fill(password,'\0');}
 }
 private static String database(Connection c)throws SQLException{try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT SYS_CONTEXT('USERENV','DB_UNIQUE_NAME')||'/'||SYS_CONTEXT('USERENV','CON_NAME')||'/'||SYS_CONTEXT('USERENV','SERVER_HOST') FROM dual")){r.next();return r.getString(1);}}
 private static String quote(String s){return "\""+s.replace("\"","\"\"")+"\"";}
 @Override public void close()throws SQLException {try{target.rollback();}finally{target.close();}}
}
