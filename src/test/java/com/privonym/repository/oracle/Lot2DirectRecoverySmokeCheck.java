package com.privonym.repository.oracle;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import com.privonym.application.service.*;
import com.privonym.domain.job.*;
import com.privonym.adapter.oracle.SqlChecksum;
final class Lot2DirectRecoverySmokeCheck {
    int fault,dml;String checkpoint;String marker;boolean failJournal;
    static void verify() {
        for(int fault=0;fault<5;fault++) {
            Lot2DirectRecoverySmokeCheck db=new Lot2DirectRecoverySmokeCheck();db.fault=fault;
            String sql="UPDATE demo SET value=1";var checksum=new PlanChecksum(SqlChecksum.sha256(sql));
            var plan=new ExecutablePlan(new JobId(500+fault),checksum,List.of(new ExecutableStep(1,10,sql,checksum)));
            RuntimeException failure=null;
            try{new OracleMeasuredPlanExecutor(db.connection(false),db.connection(true)).execute(plan);}catch(RuntimeException e){failure=e;}
            if(fault==0){require(failure==null&&"SUCCEEDED".equals(db.checkpoint),"DIRECT successful commit must checkpoint");
                new OracleMeasuredPlanExecutor(db.connection(false),db.connection(true)).execute(plan);require(db.dml==1,"completed DIRECT transaction must not execute twice");}
            else if(fault==1)require(failure!=null&&!(failure instanceof TargetOutcomeUncertainException)&&"FAILED".equals(db.checkpoint),"confirmed rollback remains retryable");
            else require(failure instanceof TargetOutcomeUncertainException&&"RUNNING".equals(db.checkpoint)&&db.marker!=null,"commit/rollback/journal ambiguity must stay unresolved: "+fault);
        }
        System.out.println("Lot 2 DIRECT fault injection passed: completed replay skipped, rollback retryable, commit/rollback/checkpoint ambiguity blocked.");
    }
    Connection connection(boolean target){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
        if(m.getName().equals("prepareStatement"))return statement((String)a[0],target);
        if(target&&m.getName().equals("commit")&&fault==2)throw new SQLException("commit response lost");
        if(target&&m.getName().equals("rollback")&&fault==3)throw new SQLException("rollback response lost");
        return Lot2RecoverySmokeCheck.value(m.getReturnType());
    });}
    PreparedStatement statement(String sql,boolean target){Map<Integer,Object> params=new HashMap<>();return (PreparedStatement)Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
        if(m.getName().startsWith("set")&&a!=null&&a.length>=2&&a[0] instanceof Integer){params.put((Integer)a[0],a[1]);return null;}
        if(m.getName().equals("executeQuery")) {
            List<Object[]> data=new ArrayList<>();
            if(target)data.add(new Object[]{1L});
            else if(sql.startsWith("SELECT status,TO_CHAR"))data.add(new Object[]{"RUNNING","attempt"});
            else if(sql.startsWith("SELECT status,rows_processed")){if(checkpoint!=null)data.add(new Object[]{checkpoint,1L,"DIRECT_TX"});}
            else if(sql.contains("COUNT(*)"))data.add(new Object[]{0L});
            int[] index={-1};return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(r,n,b)->switch(n.getName()){
                case "next" -> ++index[0]<data.size();
                case "getString" -> String.valueOf(data.get(index[0])[(Integer)b[0]-1]);
                case "getLong" -> ((Number)data.get(index[0])[(Integer)b[0]-1]).longValue();
                case "getInt" -> ((Number)data.get(index[0])[(Integer)b[0]-1]).intValue();
                default -> Lot2RecoverySmokeCheck.value(n.getReturnType());
            });
        }
        if(m.getName().equals("executeUpdate")) {
            if(target){dml++;if(fault==1||fault==3)throw new SQLException("DML interrupted");}
            else if(sql.startsWith("INSERT INTO job_chunk"))checkpoint="RUNNING";
            else if(sql.startsWith("UPDATE job_chunk SET status='SUCCEEDED'")){if(fault==4)throw new SQLException("journal completion unavailable");checkpoint="SUCCEEDED";}
            else if(sql.startsWith("UPDATE job_chunk SET status=?")){checkpoint=(String)params.get(1);marker=(String)params.get(2);}
            return 1;
        }
        return Lot2RecoverySmokeCheck.value(m.getReturnType());
    });}
    static void require(boolean yes,String message){if(!yes)throw new AssertionError(message);}
}
