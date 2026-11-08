package com.privonym.repository.oracle;

import com.privonym.application.service.JobExecutionCancelledException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.SQLTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Cross-process cancellation and a job-row commit fence. No target commit is allowed on stale ownership. */
final class JobExecutionControl implements AutoCloseable {
    private static final ConcurrentHashMap<Long,JobExecutionControl> ACTIVE=new ConcurrentHashMap<>();
    private static final String IDENTITY="TO_CHAR(started_at,'YYYY-MM-DD HH24:MI:SS.FF9 TZH:TZM')";
    private final long jobId;
    private final String identity;
    private final Set<Statement> statements=ConcurrentHashMap.newKeySet();
    private final Runnable cancellationHook;
    private final ScheduledExecutorService monitor;
    private final Connection monitorConnection;
    private volatile boolean cancelled;
    private volatile boolean closing;
    private int consecutiveTimeouts;
    private volatile RuntimeException unavailable;

    JobExecutionControl(long jobId,Connection repository,ExecutionControlConnections connections,Runnable hook)throws SQLException {
        this.jobId=jobId;this.cancellationHook=hook;
        identity=read(repository,false);
        if(ACTIVE.putIfAbsent(jobId,this)!=null)throw new IllegalStateException("Job already has an execution owner");
        Connection opened=null;ScheduledExecutorService scheduler=null;
        try {
            if(connections!=null) {
                opened=connections.open();opened.setAutoCommit(false);opened.setNetworkTimeout(Runnable::run,5000);
                scheduler=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"privonym-control-"+jobId);t.setDaemon(true);return t;});
            }
            monitorConnection=opened;monitor=scheduler;
            if(monitor!=null)monitor.scheduleWithFixedDelay(this::poll,0,500,TimeUnit.MILLISECONDS);
        }catch(SQLException|RuntimeException error){ACTIVE.remove(jobId,this);if(scheduler!=null)scheduler.shutdownNow();if(opened!=null)try{opened.close();}catch(SQLException close){error.addSuppressed(close);}throw error;}
    }
    static boolean cancel(long jobId){JobExecutionControl control=ACTIVE.get(jobId);if(control==null)return false;control.cancel();return true;}
    void cancel(){cancelled=true;if(cancellationHook!=null)cancellationHook.run();cancelStatements();}
    void track(Statement statement){statements.add(statement);check();}
    void untrack(Statement statement){statements.remove(statement);}
    void check(){if(unavailable!=null)throw unavailable;if(cancelled||Thread.currentThread().isInterrupted())throw new JobExecutionCancelledException(jobId);}
    void beforeCommit(Connection repository)throws SQLException {
        while(true) {
            check();
            try {
                String current=read(repository,true);
                if(!identity.equals(current))throw new IllegalStateException("Execution owner changed; stale worker cannot commit");
                check();return;
            }catch(SQLException busy) {
                if(busy.getErrorCode()!=54)throw busy;
                repository.rollback();
                // A different worker may legitimately hold the fence throughout a
                // long target commit. NOWAIT keeps contention separate from an
                // unavailable repository; check cancellation between attempts.
                try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new JobExecutionCancelledException(jobId,interrupted);}
            }
        }
    }
    private String read(Connection repository,boolean lock)throws SQLException {
        try(PreparedStatement query=repository.prepareStatement("SELECT status,"+IDENTITY+" FROM anonymization_job WHERE job_id=?"+(lock?" FOR UPDATE NOWAIT":""))) {
            query.setLong(1,jobId);query.setQueryTimeout(5);
            try(ResultSet row=query.executeQuery()) {
                if(!row.next())throw new SQLException("Execution owner no longer exists");
                String status=row.getString(1);
                if(status.equals("CANCELLING")||status.equals("CANCELLED"))throw new JobExecutionCancelledException(jobId);
                if(!status.equals("RUNNING")&&!status.equals("RETRYING"))throw new IllegalStateException("Execution is no longer running: "+status);
                String token=row.getString(2);if(token==null)throw new SQLException("Execution identity is missing");return token;
            }
        }
    }
    private void poll() {
        try {
            if(cancelled||unavailable!=null)return;
            if(!identity.equals(read(monitorConnection,false)))throw new IllegalStateException("Execution owner changed");
            monitorConnection.rollback();
            try(PreparedStatement lock=monitorConnection.prepareStatement("SELECT job_id FROM anonymization_job WHERE job_id=? FOR UPDATE NOWAIT")) {
                lock.setLong(1,jobId);lock.setQueryTimeout(2);
                try(ResultSet row=lock.executeQuery()){if(!row.next())throw new SQLException("Execution owner missing");}
            }catch(SQLException busy){monitorConnection.rollback();if(busy.getErrorCode()==54){consecutiveTimeouts=0;return;}throw busy;}
            try(PreparedStatement heartbeat=monitorConnection.prepareStatement("UPDATE anonymization_job SET heartbeat_at=SYSTIMESTAMP WHERE job_id=? AND status IN('RUNNING','RETRYING') AND "+IDENTITY+"=?")) {
                heartbeat.setLong(1,jobId);heartbeat.setString(2,identity);heartbeat.setQueryTimeout(2);
                if(heartbeat.executeUpdate()!=1)throw new IllegalStateException("Execution owner changed");
            }
            monitorConnection.commit();
            consecutiveTimeouts=0;
        }catch(JobExecutionCancelledException expected){cancel();}
        catch(SQLException|RuntimeException error){
            if(closing)return;
            // A single monitor query timeout does not prove loss of ownership. Every
            // target commit still requires a fresh, locked repository ownership read.
            if(isQueryTimeout(error)&&++consecutiveTimeouts<3) {
                try{monitorConnection.rollback();return;}catch(SQLException rollback){error.addSuppressed(rollback);}
            }
            String detail=error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());
            if(error instanceof SQLException sql)detail="SQL code="+sql.getErrorCode()+", state="+sql.getSQLState()+", "+detail;
            unavailable=new IllegalStateException("Execution control unavailable; target commits are blocked. Cause: "+detail,error);
            try{monitorConnection.rollback();}catch(SQLException rollback){error.addSuppressed(rollback);}
            System.err.println("Job "+jobId+": "+unavailable.getMessage());
            cancelStatements();
        }
    }
    private static boolean isQueryTimeout(Throwable error) {
        return error instanceof SQLTimeoutException||error instanceof SQLException sql&&sql.getErrorCode()==1013;
    }
    private void cancelStatements(){for(Statement statement:statements)try{statement.cancel();}catch(SQLException ignored){/* fence remains mandatory */}}
    @Override public void close() {
        closing=true;
        ACTIVE.remove(jobId,this);
        if(monitor!=null){monitor.shutdownNow();try{monitor.awaitTermination(6,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
        if(monitorConnection!=null)try{monitorConnection.close();}catch(SQLException ignored){/* no target transaction here */}
    }
}
