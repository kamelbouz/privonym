package com.privonym.repository.oracle;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;
import com.privonym.adapter.oracle.SqlChecksum;
import com.privonym.domain.job.JobId;
final class SavedPlanResumeSmokeCheck {
 private SavedPlanResumeSmokeCheck() { }
 static void verify() {
  List<String> code=List.of("UPDATE completed_table SET value=1","UPDATE pending_table SET value=2");
  Connection c=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
   if(!m.getName().equals("prepareStatement"))throw new UnsupportedOperationException(m.getName());
   boolean includesCompleted=((String)a[0]).contains("'SUCCEEDED'");int[] row={includesCompleted?-1:0};
   return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(q,n,b)->switch(n.getName()){
    case "setLong","close" -> null;
    case "executeQuery" -> Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(r,get,v)->switch(get.getName()){
     case "next" -> ++row[0]<2;
     case "getString" -> v[0].equals("generated_code")?code.get(row[0]):SqlChecksum.sha256(code.get(row[0]));
     case "getLong" -> (long)row[0]+1;
     case "getInt" -> row[0]+1;
     case "close" -> null;
     default -> throw new UnsupportedOperationException(get.getName());
    });
    default -> throw new UnsupportedOperationException(n.getName());
   });
  });
  var plan=new OraclePlanningAdapter(c).loadExecutablePlan(new JobId(121));
  String approved=SqlChecksum.sha256(String.join("\n",code.stream().map(SqlChecksum::sha256).toList()));
  if(plan.steps().size()!=2||!plan.checksum().value().equals(approved))throw new AssertionError("Partial resume dropped a completed step and changed the approved plan fingerprint");
  System.out.println("Saved plan resume passed: completed steps retained in approved fingerprint; their successful chunks remain reusable.");
 }
}
