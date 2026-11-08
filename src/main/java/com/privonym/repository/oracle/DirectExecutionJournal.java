package com.privonym.repository.oracle;

import com.privonym.application.service.ExecutablePlan;
import com.privonym.application.service.StepExecutionResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** One target transaction, one atomic set of step checkpoints in the repository. */
final class DirectExecutionJournal {
    private DirectExecutionJournal() { }
    static List<StepExecutionResult> completed(Connection c,ExecutablePlan plan)throws SQLException {
        List<StepExecutionResult> result=new ArrayList<>();
        for(var step:plan.steps()) {
            OracleParallelPlanExecutor.requireReconciledChunks(c,step.jobStepId());
            try(PreparedStatement q=c.prepareStatement("SELECT status,rows_processed,end_rowid FROM job_chunk WHERE job_step_id=? AND chunk_number=1")) {
                q.setLong(1,step.jobStepId());
                try(ResultSet r=q.executeQuery()){if(r.next()&&r.getString(1).equals("SUCCEEDED")) {
                    if(!r.getString(3).equals("DIRECT_TX"))throw new IllegalStateException("Incompatible prior DIRECT checkpoint");
                    result.add(new StepExecutionResult(step.jobStepId(),r.getLong(2)));
                }}
            }
        }
        if(!result.isEmpty()&&result.size()!=plan.steps().size())throw new IllegalStateException("Partial transaction checkpoint set requires recovery verification");
        return result;
    }
    static void start(Connection c,ExecutablePlan plan)throws SQLException {
        for(var step:plan.steps()) {
            try(PreparedStatement d=c.prepareStatement("DELETE FROM job_chunk WHERE job_step_id=? AND (status='FAILED' OR (status='CANCELLED' AND error_message LIKE 'PRIVONYM-ROLLBACK-CONFIRMED:%'))")) {
                d.setLong(1,step.jobStepId());d.executeUpdate();
            }
            try(PreparedStatement q=c.prepareStatement("INSERT INTO job_chunk(job_step_id,chunk_number,status,end_rowid,rows_processed,started_at) VALUES(?,1,'RUNNING','DIRECT_TX',0,SYSTIMESTAMP)")) {
                q.setLong(1,step.jobStepId());q.executeUpdate();
            }
        }
        c.commit();
    }
    static void complete(Connection c,List<StepExecutionResult> results)throws SQLException {
        for(var result:results)try(PreparedStatement q=c.prepareStatement("UPDATE job_chunk SET status='SUCCEEDED',rows_processed=?,ended_at=SYSTIMESTAMP,error_message=NULL WHERE job_step_id=? AND chunk_number=1 AND status='RUNNING'")) {
            q.setLong(1,result.rowsProcessed());q.setLong(2,result.jobStepId());if(q.executeUpdate()!=1)throw new SQLException("DIRECT checkpoint changed");
        }
        c.commit();
    }
    static void failed(Connection c,ExecutablePlan plan,Throwable failure,boolean confirmed,boolean cancelled,String marker) {
        try {
            for(var step:plan.steps())try(PreparedStatement q=c.prepareStatement("UPDATE job_chunk SET status=?,error_message=?,ended_at=CASE WHEN ?=1 THEN SYSTIMESTAMP ELSE ended_at END WHERE job_step_id=? AND chunk_number=1 AND status='RUNNING'")) {
                q.setString(1,confirmed?(cancelled?"CANCELLED":"FAILED"):"RUNNING");
                String message=marker+String.valueOf(failure.getMessage());q.setString(2,message.substring(0,Math.min(4000,message.length())));
                q.setInt(3,confirmed?1:0);q.setLong(4,step.jobStepId());q.executeUpdate();
            }
            c.commit();
        }catch(SQLException error){failure.addSuppressed(error);try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}}
    }
}
