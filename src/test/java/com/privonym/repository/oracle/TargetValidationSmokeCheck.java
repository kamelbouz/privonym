package com.privonym.repository.oracle;

import com.privonym.application.service.ValidationOutcome;
import com.privonym.domain.catalog.DataSourceId;
import com.privonym.domain.job.JobId;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

final class TargetValidationSmokeCheck {
    private TargetValidationSmokeCheck() { }
    static void verifyLive(Connection realTarget,String technology)throws Exception {
        for(int size:List.of(100,99)) {
            Fixture fixture=new Fixture(size,true,false);fixture.technology=technology;
            Connection synthetic=(Connection)Proxy.newProxyInstance(TargetValidationSmokeCheck.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                if(!m.getName().equals("prepareStatement"))return empty(m.getReturnType());
                String original=(String)a[0];
                require(original.contains(technology.equals("SQLSERVER")?"[S].[T]":"\"S\".\"T\""),"target identifier quoting must match the dialect");
                String sql=switch(technology) {
                    case "ORACLE" -> "SELECT COUNT(*) FROM (SELECT 1 FROM DUAL CONNECT BY LEVEL<="+size+") fixture";
                    case "POSTGRESQL" -> "SELECT COUNT(*) FROM generate_series(1,"+size+")";
                    default -> "SELECT COUNT_BIG(*) FROM (VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) a(n) CROSS JOIN (VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) b(n) WHERE a.n*10+b.n<"+size;
                };
                return realTarget.prepareStatement(sql);
            });
            boolean failed=false;
            try {new OracleTargetDataValidator(fixture.repository(),synthetic).validate(new DataSourceId(1),new JobId(1));}
            catch(IllegalStateException expected){failed=true;}
            require(failed==(size!=100),"live synthetic target count must detect population change");
        }
        System.out.println(technology+": real JDBC synthetic counts passed; changed population rejected; no business table read or modified.");
    }
    static void verify()throws Exception {
        for(long actual:List.of(0L,99L,101L)) {
            Fixture fixture=new Fixture(actual,true,false);boolean rejected=false;
            try{fixture.validate();}catch(IllegalStateException expected){rejected=expected.getMessage().contains("Independent target row-count");}
            require(rejected&&fixture.targetQueries==1,"matching journal counters must not conceal changed target population");
        }
        Fixture valid=new Fixture(100,true,false);
        ValidationOutcome outcome=valid.validate();
        require(valid.targetQueries==1&&outcome.metrics().stream().anyMatch(m->m.name().equals("TARGET_CHECKED_S_T")&&m.value()==1),
                "validation must independently inspect the target");
        require(outcome.metrics().stream().anyMatch(m->m.name().equals("PRIVACY_ASSESSED")&&m.value()==0),
                "row counts must not claim sensitive data was removed");
        Fixture historical=new Fixture(100,false,false);
        var old=historical.validate();
        require(historical.targetQueries==1&&old.messages().stream().anyMatch(m->m.startsWith("TARGET_COUNT_NOT_CHECKED:")),
                "historical executions must not manufacture a pre-execution baseline");
        boolean unavailable=false;
        try{new Fixture(100,true,true).validate();}catch(IllegalStateException expected){unavailable=true;}
        require(unavailable,"unreadable targets must not validate successfully");
        for(String type:List.of("UPDATE","DELETE","TRUNCATE")) {
            Fixture fixture=new Fixture(200,true,false);
            TargetRowCountEvidence.capture(fixture.repository(),fixture.target(),1,"T",type,40,true,false);
            require(fixture.savedExpected==(type.equals("UPDATE")?200:type.equals("DELETE")?160:0),"population baseline must respect operation semantics");
        }
        Fixture retry=new Fixture(200,false,false);
        TargetRowCountEvidence.capture(retry.repository(),retry.target(),1,"T","UPDATE",40,true,true);
        require(retry.savedExpected==-1&&retry.targetQueries==0,"partially committed resumes must retain original evidence or its absence");
        OracleDraftInventoryAdapter.requireExpectedRuleset(0,0);
        OracleDraftInventoryAdapter.requireExpectedRuleset(61,61);
        OracleDraftInventoryAdapter.requireExpectedOwner(3,3);
        OracleDraftInventoryAdapter.requireExpectedOwner(0,4);
        boolean ownerChanged=false;
        try{OracleDraftInventoryAdapter.requireExpectedOwner(3,4);}catch(IllegalStateException expected){ownerChanged=true;}
        require(ownerChanged,"ownership must be rechecked under Rule Set lock");
        for(long[] changed:List.of(new long[]{0,61},new long[]{61,62},new long[]{61,0})) {
            boolean rejected=false;try{OracleDraftInventoryAdapter.requireExpectedRuleset(changed[0],changed[1]);}
            catch(IllegalStateException expected){rejected=true;}require(rejected,"import authorization must be rechecked under connection lock");
        }
        System.out.println("Lot 1 validation/access checks passed: independent target counts, POST changes, missing baseline, unreadable target, retry evidence and import authorization race.");
    }
    private static final class Fixture {
        private final long actual;private final boolean baseline,failRead;
        private int targetQueries;private long savedExpected=-1;
        private String technology="ORACLE";
        Fixture(long actual,boolean baseline,boolean failRead){this.actual=actual;this.baseline=baseline;this.failRead=failRead;}
        ValidationOutcome validate(){return new OracleTargetDataValidator(repository(),target()).validate(new DataSourceId(1),new JobId(1));}
        Connection repository(){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(!m.getName().equals("prepareStatement"))return empty(m.getReturnType());
            String sql=(String)a[0];
            List<Map<String,Object>> data;
            if(sql.contains("FROM catalog_relationship"))data=List.of();
            else if(sql.contains("SELECT c.technology_code"))data=List.of(Map.of("1",technology));
            else if(sql.contains("SUM(a.rows_processed)"))data=List.of(Map.of("schema_name","S","table_name","T","expected_rows",100L,"anonymized_rows",100L));
            else if(sql.contains("SELECT cs.schema_name,ct.table_name,m.metric_value"))data=baseline?List.of(Map.of("1","S","2","T","3",100L)):List.of();
            else data=List.of();
            return statement(sql,data,false);
        });}
        Connection target(){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(!m.getName().equals("prepareStatement"))return empty(m.getReturnType());targetQueries++;
            if(failRead)throw new SQLException("synthetic read failure");
            require(((String)a[0]).startsWith("SELECT COUNT"),"target validation must only read counts");
            return statement((String)a[0],List.of(Map.of("1",actual)),true);
        });}
        PreparedStatement statement(String sql,List<Map<String,Object>> data,boolean target) {
            return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                if(m.getName().equals("executeQuery"))return rows(data);
                if(m.getName().equals("executeUpdate"))return 1;
                if(!target&&sql.contains("SELECT job_id,?,'TARGET_ROWS_EXPECTED'")&&m.getName().equals("setLong")&&(int)a[0]==2)savedExpected=(long)a[1];
                return empty(m.getReturnType());
            });
        }
    }
    private static ResultSet rows(List<Map<String,Object>> values) {
        int[] index={-1};
        return (ResultSet)Proxy.newProxyInstance(TargetValidationSmokeCheck.class.getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->switch(m.getName()) {
            case "next" -> ++index[0]<values.size();
            case "getString" -> String.valueOf(values.get(index[0]).get(a[0].toString()));
            case "getLong" -> ((Number)values.get(index[0]).get(a[0].toString())).longValue();
            default -> empty(m.getReturnType());
        });
    }
    private static Object empty(Class<?> type){if(!type.isPrimitive())return null;if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
