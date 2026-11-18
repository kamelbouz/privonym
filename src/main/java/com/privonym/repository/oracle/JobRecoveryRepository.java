package com.privonym.repository.oracle;

import com.privonym.application.service.JobRecoveryView;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Operator-only decisions, never inferred from a stale timestamp or zero rows. */
final class JobRecoveryRepository {
    private JobRecoveryRepository() { }
    static boolean terminal(String status){return List.of("EXECUTION_FAILED","CANCELLED","FAILED","VALIDATION_FAILED","PLANNED","PREFLIGHT_PASSED").contains(status);}
    static void requireDecision(long jobId,long chunkId,String decision,long rows,String note,String confirmation) {
        if(decision==null||!List.of("ROLLBACK","COMMIT","STOPPED").contains(decision)||(!decision.equals("STOPPED")&&chunkId<=0)||rows<0||note==null||note.strip().length()<15||note.length()>2000)
            throw new IllegalArgumentException("Recovery requires a decision, non-negative rows and a verification note of 15 to 2000 characters");
        String required="RECONCILE JOB "+jobId+(decision.equals("STOPPED")?" STOPPED":" CHUNK "+chunkId);
        if(!required.equals(confirmation))throw new IllegalArgumentException("Type exactly: "+required);
    }
    static JobRecoveryView inspect(Connection c,long jobId)throws SQLException {
        String reference,status,mode;long version;boolean stale;
        try(PreparedStatement q=c.prepareStatement("SELECT job_reference,status,execution_mode,ruleset_version_id,CASE WHEN heartbeat_at<SYSTIMESTAMP-INTERVAL '30' SECOND OR heartbeat_at IS NULL THEN 1 ELSE 0 END FROM anonymization_job WHERE job_id=?")) {
            q.setLong(1,jobId);try(ResultSet r=q.executeQuery()){if(!r.next())throw new IllegalArgumentException("Job not found");reference=r.getString(1);status=r.getString(2);mode=r.getString(3);version=r.getLong(4);stale=r.getInt(5)==1;}
        }
        List<JobRecoveryView.Step> steps=new ArrayList<>();
        try(PreparedStatement q=c.prepareStatement("""
                SELECT s.job_step_id,cs.schema_name||'.'||ct.table_name,
                  (SELECT MAX(CASE WHEN c.end_rowid IN('DIRECT','DIRECT_TX') THEN 0 ELSE 1 END) FROM job_chunk c WHERE c.job_step_id=s.job_step_id)
                FROM job_step s JOIN catalog_table ct ON ct.catalog_table_id=s.catalog_table_id
                JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                WHERE s.job_id=? AND s.step_type='ANONYMIZE_TABLE' ORDER BY s.step_number
                """)) {
            q.setLong(1,jobId);try(ResultSet r=q.executeQuery()){while(r.next()){
                int effective=r.getInt(3);boolean unknown=r.wasNull();
                steps.add(new JobRecoveryView.Step(r.getLong(1),r.getString(2),mode,unknown?"NOT EXECUTED":effective==1?"CHUNKED":"DIRECT",unknown?"No execution checkpoint yet":"Mode recorded in chunk journal"));
            }}
        }
        // Forecasts are metadata-only: no target values are sampled by this panel.
        try(PreparedStatement q=c.prepareStatement("""
                SELECT cs.schema_name||'.'||ct.table_name,co.technology_code,
                 (SELECT COUNT(*) FROM catalog_column k WHERE k.catalog_table_id=ct.catalog_table_id AND k.primary_key_member='Y'),
                 (SELECT COUNT(*) FROM masking_rule mr JOIN catalog_column cc ON cc.catalog_column_id=mr.catalog_column_id WHERE mr.ruleset_version_id=? AND mr.enabled='Y' AND cc.catalog_table_id=ct.catalog_table_id AND (mr.filter_expression IS NOT NULL OR cc.primary_key_member='Y')),
                 (SELECT COUNT(*) FROM table_masking_rule tr WHERE tr.ruleset_version_id=? AND tr.catalog_table_id=ct.catalog_table_id AND tr.enabled='Y')
                FROM catalog_table ct JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                JOIN data_source ds ON ds.data_source_id=cs.data_source_id JOIN connector co ON co.connector_id=ds.connector_id
                WHERE ct.catalog_table_id IN(SELECT cc.catalog_table_id FROM masking_rule mr JOIN catalog_column cc ON cc.catalog_column_id=mr.catalog_column_id WHERE mr.ruleset_version_id=? AND mr.enabled='Y'
                  UNION SELECT tr.catalog_table_id FROM table_masking_rule tr WHERE tr.ruleset_version_id=? AND tr.enabled='Y')
                ORDER BY cs.schema_name,ct.table_name
                """)) {
            for(int i=1;i<=4;i++)q.setLong(i,version);
            try(ResultSet r=q.executeQuery()){while(r.next()) {
                String table=r.getString(1);boolean direct=mode.equals("DIRECT")||(!r.getString(2).equals("ORACLE")&&r.getInt(3)!=1)||r.getInt(4)>0||r.getInt(5)>0;
                if(steps.stream().noneMatch(s->s.table().equals(table)&&!s.effectiveMode().equals("NOT EXECUTED"))) {
                    steps.removeIf(s->s.table().equals(table));
                    steps.add(new JobRecoveryView.Step(0,table,mode,direct?"DIRECT":"TARGET QUALIFICATION REQUIRED",direct?"Metadata forecast: strategy, primary key, filters or structural rule prevents chunking":"Supported key type and AUTO threshold must be checked on target"));
                }
            }}
        }
        List<JobRecoveryView.Chunk> chunks=new ArrayList<>();
        try(PreparedStatement q=c.prepareStatement("SELECT c.chunk_id,c.job_step_id,c.chunk_number,c.status,c.rows_processed,c.error_message,c.ended_at FROM job_chunk c JOIN job_step s ON s.job_step_id=c.job_step_id WHERE s.job_id=? AND c.status<>'SUCCEEDED' ORDER BY c.job_step_id,c.chunk_number")) {
            q.setLong(1,jobId);try(ResultSet r=q.executeQuery()){while(r.next()) {
                String state=r.getString(4),detail=r.getString(6);
                boolean unresolved=!ChunkRecoveryState.resolved(state,detail,r.getTimestamp(7)!=null);
                if(detail==null)detail=unresolved?"Target outcome not confirmed; verify historical data before retry":"No additional error detail";
                chunks.add(new JobRecoveryView.Chunk(r.getLong(1),r.getLong(2),r.getInt(3),state,r.getLong(5),detail,unresolved));
            }}
        }
        return new JobRecoveryView(jobId,reference,status,terminal(status),stale&&List.of("RUNNING","RETRYING","CANCELLING").contains(status),steps,chunks);
    }
    static void reconcile(Connection c,long jobId,long chunkId,String decision,long rows,String note,String confirmation,String actor)throws SQLException {
        requireDecision(jobId,chunkId,decision,rows,note,confirmation);
        String status;boolean stale;
        try(PreparedStatement q=c.prepareStatement("SELECT status,CASE WHEN heartbeat_at<SYSTIMESTAMP-INTERVAL '30' SECOND OR heartbeat_at IS NULL THEN 1 ELSE 0 END FROM anonymization_job WHERE job_id=? FOR UPDATE")) {
            q.setLong(1,jobId);try(ResultSet r=q.executeQuery()){if(!r.next())throw new IllegalArgumentException("Job not found");status=r.getString(1);stale=r.getInt(2)==1;}
        }
        if(decision.equals("STOPPED")) {
            if(!stale||!List.of("RUNNING","RETRYING","CANCELLING").contains(status))throw new IllegalStateException("Runner is not eligible for stopped-runner confirmation");
            boolean cancelled = status.equals("CANCELLING") && hasNoChunks(c,jobId)
                    && ScriptPhaseJournal.state(c,jobId,"PRE") != 1
                    && ScriptPhaseJournal.state(c,jobId,"POST") != 1;
            if (cancelled) {
                try (PreparedStatement q=c.prepareStatement("UPDATE job_step_attempt SET status='CANCELLED',ended_at=SYSTIMESTAMP,heartbeat_at=SYSTIMESTAMP WHERE status='RUNNING' AND job_step_id IN(SELECT job_step_id FROM job_step WHERE job_id=?)")) {
                    q.setLong(1,jobId);q.executeUpdate();
                }
                try (PreparedStatement q=c.prepareStatement("UPDATE job_step SET status='CANCELLED',ended_at=SYSTIMESTAMP WHERE job_id=? AND status='RUNNING'")) {
                    q.setLong(1,jobId);q.executeUpdate();
                }
                try (PreparedStatement q=c.prepareStatement("UPDATE anonymization_job SET status='CANCELLED',ended_at=SYSTIMESTAMP,heartbeat_at=SYSTIMESTAMP,error_code=NULL,error_message=NULL WHERE job_id=?")) {
                    q.setLong(1,jobId);q.executeUpdate();
                }
            } else {
                try(PreparedStatement q=c.prepareStatement("UPDATE anonymization_job SET status='EXECUTION_FAILED',ended_at=SYSTIMESTAMP,error_code='RECOVERY_REQUIRED',error_message='Operator confirmed stopped runner; chunks or script outcomes require recovery review' WHERE job_id=?")){q.setLong(1,jobId);q.executeUpdate();}
            }
        } else {
            if(!terminal(status))throw new IllegalStateException("Stop the runner and wait for a terminal Job state before reconciling chunks");
            try(PreparedStatement q=c.prepareStatement("SELECT c.status,c.error_message,c.ended_at FROM job_chunk c JOIN job_step s ON s.job_step_id=c.job_step_id WHERE c.chunk_id=? AND s.job_id=? FOR UPDATE OF c.status")) {
                q.setLong(1,chunkId);q.setLong(2,jobId);try(ResultSet r=q.executeQuery()) {
                    if(!r.next())throw new IllegalArgumentException("Chunk does not belong to this Job");
                    String old=r.getString(1),detail=r.getString(2);
                    if(ChunkRecoveryState.resolved(old,detail,r.getTimestamp(3)!=null))throw new IllegalStateException("Chunk is already reconciled; its checkpoint is preserved");
                }
            }
            try(PreparedStatement q=c.prepareStatement("UPDATE job_chunk SET status=?,rows_processed=?,ended_at=SYSTIMESTAMP,error_message=? WHERE chunk_id=?")) {
                q.setString(1,decision.equals("ROLLBACK")?"FAILED":"SUCCEEDED");q.setLong(2,decision.equals("ROLLBACK")?0:rows);
                q.setString(3,(decision.equals("ROLLBACK")?"PRIVONYM-ROLLBACK-CONFIRMED: ":"PRIVONYM-COMMIT-CONFIRMED: ")+"Operator "+actor+": "+note);
                q.setLong(4,chunkId);q.executeUpdate();
            }
        }
        try(PreparedStatement q=c.prepareStatement("INSERT INTO job_event(job_id,event_timestamp,severity,event_code,message) VALUES(?,SYSTIMESTAMP,'WARN','OPERATOR_RECOVERY_DECISION',?)")) {
            q.setLong(1,jobId);q.setString(2,"Actor="+actor+" decision="+decision+" chunk="+chunkId+" rows="+rows+" verification="+note);q.executeUpdate();
        }
    }
    private static boolean hasNoChunks(Connection c,long jobId)throws SQLException {
        try (PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM job_chunk c JOIN job_step s ON s.job_step_id=c.job_step_id WHERE s.job_id=?")) {
            q.setLong(1,jobId);try(ResultSet rows=q.executeQuery()){rows.next();return rows.getLong(1)==0;}
        }
    }
}
