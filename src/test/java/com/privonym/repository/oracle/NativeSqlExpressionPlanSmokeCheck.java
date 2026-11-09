package com.privonym.repository.oracle;

import com.privonym.adapter.oracle.OracleMaskingAssignment;
import com.privonym.application.service.ImportedSqlExpression;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.util.Map;

final class NativeSqlExpressionPlanSmokeCheck {
    private NativeSqlExpressionPlanSmokeCheck() { }
    @SuppressWarnings("unchecked")
    static void verify() throws Exception {
        Class<?> type=Class.forName("com.privonym.repository.oracle.OracleJobPlanRepository$MutableAssignment");
        var constructor=type.getDeclaredConstructor(String.class,String.class,String.class,String.class,int.class,Long.class,Integer.class);
        constructor.setAccessible(true);
        var values=type.getDeclaredField("values");values.setAccessible(true);
        var freeze=type.getDeclaredMethod("freeze",Connection.class,String.class);freeze.setAccessible(true);
        for(var example:Map.of("ORACLE","LPAD(TO_CHAR(ID_USER),10,'0')",
                "SQLSERVER","CONCAT(N'USER_',CONVERT(nvarchar(40),[ID_USER]))",
                "POSTGRESQL","concat('USER_',id_user::text)").entrySet()) {
            Object pending=constructor.newInstance("C",ImportedSqlExpression.algorithm(example.getKey()),
                    "SQL_EXPRESSION","PRIVONYM_IMPORT_RUNTIME_V27_REQUIRED(${column})",1,null,200);
            ((Map<String,String>)values.get(pending)).put("expression",example.getValue());
            var result=(OracleMaskingAssignment)freeze.invoke(pending,null,example.getKey());
            if(!result.template().equals(example.getValue())||!result.parameterValues().isEmpty()
                    ||!result.parameterDefinitions().isEmpty())throw new AssertionError("Exact expression was not restored in generated plan");
            String wrong=example.getKey().equals("ORACLE")?"SQLSERVER":"ORACLE";
            boolean rejected=false;try{freeze.invoke(pending,null,wrong);}catch(InvocationTargetException expected){rejected=expected.getCause() instanceof IllegalArgumentException;}
            if(!rejected)throw new AssertionError("Persisted expression executed on wrong dialect");
        }
        System.out.println("Native expression plan loading passed: stored expression replaces sentinel and rejects other engines.");
    }
}
