package com.privonym.repository.oracle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

final class ExecutionRecoveryPolicy {
    private ExecutionRecoveryPolicy() { }
    static boolean hasHistory(Connection c,long jobId)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM job_step_attempt a JOIN job_step s ON s.job_step_id=a.job_step_id WHERE s.job_id=?")) {
            q.setLong(1,jobId);try(ResultSet r=q.executeQuery()){r.next();return r.getLong(1)>0;}
        }
    }
    static void requireSameGeometry(String status,boolean history,String oldMode,int oldSize,long oldThreshold,
            String newMode,int newSize,long newThreshold) {
        if(history&&!status.equals("SUCCEEDED")&&(!oldMode.equals(newMode)||oldSize!=newSize||oldThreshold!=newThreshold))
            throw new IllegalStateException("Resume keeps its execution strategy and chunk boundaries; create a new Job to change them");
    }
    static void requireSameVersion(boolean history,long current,long requested) {
        if(history&&current!=requested)throw new IllegalStateException("An executed Job keeps its Rule Set version; create a new Job for another version");
    }
    static boolean hasChunks(Connection c,long jobId)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM job_chunk c JOIN job_step s ON s.job_step_id=c.job_step_id WHERE s.job_id=?")) {
            q.setLong(1,jobId);try(ResultSet r=q.executeQuery()){r.next();return r.getLong(1)>0;}
        }
    }
    static void requireReconciled(Connection c,long jobId)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM job_chunk c JOIN job_step s ON s.job_step_id=c.job_step_id WHERE s.job_id=? AND "+ChunkRecoveryState.unresolvedSql("c"))) {
            q.setLong(1,jobId);try(ResultSet r=q.executeQuery()){r.next();if(r.getLong(1)>0)throw new IllegalStateException("Recovery verification required: inspect the Job recovery panel before retrying unreconciled chunks");}
        }
    }
    static String stepStatus(long total,long incomplete,long rows,Long expected,boolean cancelled) {
        if(total>0&&incomplete==0&&expected!=null&&rows==expected)return "SUCCEEDED";
        return total==0?"PENDING":cancelled?"CANCELLED":"FAILED";
    }
}
