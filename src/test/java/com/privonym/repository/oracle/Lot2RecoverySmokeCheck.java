package com.privonym.repository.oracle;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Faults are injected into isolated JDBC doubles; no repository or business data is touched. */
final class Lot2RecoverySmokeCheck {
    volatile String status="RUNNING",identity="attempt-1";
    volatile boolean disconnected;
    final java.util.concurrent.atomic.AtomicInteger queryTimeouts=new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger busyLocks=new java.util.concurrent.atomic.AtomicInteger();
    final CountDownLatch busyObserved=new CountDownLatch(1);
    final CountDownLatch timeoutObserved=new CountDownLatch(1);
    final CountDownLatch cancelled=new CountDownLatch(1);
    static void verify() throws Exception {
        rejects(()->ExecutionRecoveryPolicy.requireSameVersion(true,12,13));
        ExecutionRecoveryPolicy.requireSameVersion(true,12,12);
        rejects(()->ExecutionRecoveryPolicy.requireSameGeometry("EXECUTION_FAILED",true,"CHUNKED",1000,1000,"DIRECT",1000,1000));
        rejects(()->ExecutionRecoveryPolicy.requireSameGeometry("CANCELLED",true,"CHUNKED",1000,1000,"CHUNKED",2000,2000));
        ExecutionRecoveryPolicy.requireSameGeometry("EXECUTION_FAILED",true,"CHUNKED",1000,1000,"CHUNKED",1000,1000);
        require(ExecutionRecoveryPolicy.stepStatus(3,0,30,30L,true).equals("SUCCEEDED"),"committed work preserved on cancellation");
        require(ExecutionRecoveryPolicy.stepStatus(0,0,0,null,true).equals("PENDING"),"unstarted work remains pending");
        require(!ExecutionRecoveryPolicy.stepStatus(3,0,29,30L,false).equals("SUCCEEDED"),"row mismatch cannot succeed");
        rejects(()->JobRecoveryRepository.requireDecision(61,3032,"ROLLBACK",0,"Verified rollback evidence","wrong"));
        rejects(()->JobRecoveryRepository.requireDecision(61,3032,"COMMIT",-1,"Verified commit evidence","RECONCILE JOB 61 CHUNK 3032"));
        rejects(()->JobRecoveryRepository.requireDecision(61,3032,"ROLLBACK",0,"short","RECONCILE JOB 61 CHUNK 3032"));
        JobRecoveryRepository.requireDecision(61,3032,"ROLLBACK",0,"Verified rollback evidence","RECONCILE JOB 61 CHUNK 3032");
        for(int fault=0;fault<3;fault++) {
            Lot2RecoverySmokeCheck db=new Lot2RecoverySmokeCheck();
            try(JobExecutionControl control=new JobExecutionControl(900+fault,db.connection(),db::connection,null)) {
                Statement statement=(Statement)Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{if(m.getName().equals("cancel"))db.cancelled.countDown();return value(m.getReturnType());});
                control.track(statement);control.beforeCommit(db.connection());
                if(fault==0)db.status="CANCELLING";else if(fault==1)db.identity="new-attempt";else db.disconnected=true;
                require(db.cancelled.await(4,TimeUnit.SECONDS),"external cancellation/ownership loss must interrupt SQL");
                rejects(()->{try{control.beforeCommit(db.connection());}catch(SQLException e){throw new IllegalStateException(e);}});
                control.untrack(statement);
            }
        }
        for(int failures:new int[]{1,3}) {
            Lot2RecoverySmokeCheck timed=new Lot2RecoverySmokeCheck();
            try(JobExecutionControl control=new JobExecutionControl(950+failures,timed.connection(),timed::connection,null)) {
                Statement tracked=(Statement)Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{if(m.getName().equals("cancel"))timed.cancelled.countDown();return value(m.getReturnType());});
                control.track(tracked);timed.queryTimeouts.set(failures);
                require(timed.timeoutObserved.await(3,TimeUnit.SECONDS),"monitor timeout must be injected");
                if(failures==1){Thread.sleep(900);control.check();control.beforeCommit(timed.connection());require(timed.cancelled.getCount()==1,"one polling timeout must not cancel target SQL");}
                else{require(timed.cancelled.await(4,TimeUnit.SECONDS),"repeated polling timeouts must stop target SQL");try{control.check();throw new AssertionError("Persistent timeout accepted");}catch(IllegalStateException expected){require(expected.getMessage().contains("SQL code=1013")&&expected.getMessage().contains("injected monitor timeout"),"SQL cause must survive into the Job error");}}
                control.untrack(tracked);
            }
        }
        for(boolean cancellation:new boolean[]{true,false}) {
            Lot2RecoverySmokeCheck busy=new Lot2RecoverySmokeCheck();
            var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
            try(JobExecutionControl control=new JobExecutionControl(cancellation?980:981,busy.connection(),null,null)) {
                busy.busyLocks.set(1000);
                var waiting=worker.submit(()->{try{control.beforeCommit(busy.connection());throw new AssertionError("Changed/cancelled owner committed after contention");}catch(com.privonym.application.service.JobExecutionCancelledException expected){if(!cancellation)throw expected;}catch(IllegalStateException expected){if(cancellation||!expected.getMessage().contains("owner changed"))throw expected;}catch(SQLException e){throw new IllegalStateException(e);}});
                require(busy.busyObserved.await(2,TimeUnit.SECONDS),"Commit fence contention not injected");
                if(cancellation)control.cancel();else{busy.identity="new owner";busy.busyLocks.set(0);}
                waiting.get(2,TimeUnit.SECONDS);
            }finally{worker.shutdownNow();}
        }
        Lot2RecoverySmokeCheck db=new Lot2RecoverySmokeCheck();
        try(JobExecutionControl c=new JobExecutionControl(999,db.connection(),null,null)) {
            db.identity="reclaimed";rejects(()->{try{c.beforeCommit(db.connection());}catch(SQLException e){throw new IllegalStateException(e);}});
        }
        System.out.println("Lot 2 recovery checks passed: pinned version/boundaries, checkpoint statuses, typed operator decisions, external cancellation, ownership fencing and control outage.");
    }
    Connection connection(){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
        if(m.getName().equals("prepareStatement"))return statement((String)a[0]);return value(m.getReturnType());
    });}
    PreparedStatement statement(String sql){return (PreparedStatement)Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
        if(m.getName().equals("executeQuery")) {
            if(disconnected)throw new SQLException("Injected control connection outage");
            if(sql.contains("FOR UPDATE NOWAIT")&&busyLocks.getAndUpdate(n->Math.max(0,n-1))>0){busyObserved.countDown();throw new SQLException("Resource busy", "61000",54);}
            if(queryTimeouts.getAndUpdate(n->Math.max(0,n-1))>0){timeoutObserved.countDown();throw new SQLTimeoutException("injected monitor timeout", "HYT00",1013);}
            boolean[] seen={false};String s=status,i=identity;
            return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(r,n,b)->switch(n.getName()){
                case "next" -> {boolean first=!seen[0];seen[0]=true;yield first;}
                case "getString" -> ((Integer)b[0])==1?s:i;
                case "getLong" -> 999L;
                default -> value(n.getReturnType());
            });
        }
        if(m.getName().equals("executeUpdate"))return 1;return value(m.getReturnType());
    });}
    static Object value(Class<?> t){if(t==boolean.class)return false;if(t==int.class)return 0;if(t==long.class)return 0L;return null;}
    static void rejects(Runnable task){try{task.run();}catch(IllegalArgumentException|IllegalStateException|com.privonym.application.service.JobExecutionCancelledException expected){return;}throw new AssertionError("Unsafe operation was accepted");}
    static void require(boolean yes,String message){if(!yes)throw new AssertionError(message);}
}
