package com.privonym.repository.oracle;
import java.sql.*;
/** Stored scripts may contain implicit commits: incomplete phase receipts prohibit replay. */
final class ScriptPhaseJournal {
    private ScriptPhaseJournal() { }
    static void requireReconciled(Connection c,long job)throws SQLException {
        for(String phase:new String[]{"PRE","POST"})if(state(c,job,phase)==1)throw new IllegalStateException(phase+" script has no completion receipt; verify its target effects before recovery. Script replay is blocked.");
    }
    static int state(Connection c,long job,String phase)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT COALESCE(MAX(CASE WHEN e.event_code=? THEN 2 ELSE 1 END),0) FROM job_event e JOIN anonymization_job j ON j.job_id=e.job_id WHERE j.job_id=? AND e.event_timestamp>=j.planned_at AND e.event_code IN(?,?)")) {
            q.setString(1,"SCRIPT_"+phase+"_COMPLETED");q.setLong(2,job);q.setString(3,"SCRIPT_"+phase+"_STARTED");q.setString(4,"SCRIPT_"+phase+"_COMPLETED");try(ResultSet r=q.executeQuery()){r.next();return r.getInt(1);}
        }
    }
    static boolean start(Connection c,long job,String phase)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT job_id FROM anonymization_job WHERE job_id=? FOR UPDATE")){q.setLong(1,job);try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("Job missing");}}
        int state=state(c,job,phase);if(state==2){c.commit();return false;}if(state==1){c.rollback();throw new IllegalStateException(phase+" script outcome requires verification; automatic replay blocked");}
        event(c,job,phase,"STARTED");c.commit();return true;
    }
    static void complete(Connection c,long job,String phase)throws SQLException{event(c,job,phase,"COMPLETED");c.commit();}
    private static void event(Connection c,long job,String phase,String state)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("INSERT INTO job_event(job_id,event_timestamp,severity,event_code,message) VALUES(?,SYSTIMESTAMP,'INFO',?,?)")){q.setLong(1,job);q.setString(2,"SCRIPT_"+phase+"_"+state);q.setString(3,phase+" stored script "+state.toLowerCase());q.executeUpdate();}
    }
}
