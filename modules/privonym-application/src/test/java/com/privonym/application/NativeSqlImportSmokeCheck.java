package com.privonym.application;

import com.privonym.application.service.*;
import java.util.List;
import java.util.Map;

final class NativeSqlImportSmokeCheck {
    private NativeSqlImportSmokeCheck() { }
    static void verify() {
        var transformer=new MultiDialectSqlInventoryPreviewService(new OracleSqlInventoryPreviewService(),
                new SqlServerSqlInventoryPreviewService(),new PostgresqlSqlInventoryPreviewService());
        Map<String,List<String>> cases=Map.of(
            "ORACLE",List.of("LPAD(TO_CHAR(ID_USER),10,'0')","CONCAT('USER_',ID_USER)",
                "NVL(REGEXP_REPLACE(TXT,'[0-9]+','anonymized'),'empty')",
                "ADD_MONTHS(TRUNC(DT,'YEAR'),6)","CAST(ROUND(AMOUNT*1.25,2) AS NUMBER(12,2))"),
            "SQLSERVER",List.of("CONCAT(N'USER_',CONVERT(nvarchar(40),[ID_USER]))",
                "STUFF([TXT],1,3,N'--')","ISNULL(LEFT([TXT],3),N'empty')",
                "HASHBYTES('SHA2_256',CONVERT(varbinary(max),[TXT]))",
                "DATEADD(day,7,DATEFROMPARTS(YEAR(DT),1,1))",
                "TRY_CAST(ROUND(AMOUNT*1.25,2) AS decimal(12,2))"),
            "POSTGRESQL",List.of("concat('USER_',id_user::text)",
                "regexp_replace(txt,'[0-9]+','anonymized','g')",
                "encode(digest(txt::text,'sha256'),'hex')",
                "substring(txt from 1 for 3)","date_trunc('year',dt)",
                "round(amount::numeric(12,2)*1.25,2)","$mask$--literal;value$mask$"));
        for(var engine:cases.entrySet())for(String expression:engine.getValue()) {
            var preview=transformer.preview(engine.getKey(),"UPDATE T SET C="+expression+";");
            var column=preview.tables().get(0).columns().get(0);
            require(column.matchStatus().equals("MATCHED"),engine.getKey()+" unresolved: "+expression);
            require(column.algorithmCode().equals(ImportedSqlExpression.algorithm(engine.getKey())),"Wrong dialect algorithm");
            require(expression.equals(column.parameters().get("expression")),"Expression was approximated or rewritten");
            require(expression.equals(ImportedSqlExpression.validateAlgorithm(column.algorithmCode(),engine.getKey(),expression)),"Generation changed expression");
        }
        for(String engine:cases.keySet()) {
            for(String expression:List.of("(SELECT C FROM T)","evil(C)","pkg.mutate(C)","1;DELETE FROM T","1--comment","1/*comment*/")) {
                boolean rejected=false;try{ImportedSqlExpression.validate(engine,expression);}catch(IllegalArgumentException expected){rejected=true;}
                require(rejected,"Unsafe/unknown expression accepted on "+engine+": "+expression);
            }
        }
        for(var wrong:Map.of("ORACLE","ISNULL(C,'')","SQLSERVER","NVL(C,'')","POSTGRESQL","DATEADD(day,1,C)").entrySet()) {
            boolean rejected=false;try{ImportedSqlExpression.validate(wrong.getKey(),wrong.getValue());}catch(IllegalArgumentException expected){rejected=true;}
            require(rejected,"Foreign engine function was accepted");
        }
        boolean mismatch=false;try{ImportedSqlExpression.validateAlgorithm("SQLSERVER_EXPRESSION_EXACT","POSTGRESQL","concat('x',id)");}catch(IllegalArgumentException expected){mismatch=true;}
        require(mismatch,"Cross-engine expression execution was accepted");
        System.out.println("Native SQL import passed: three dialects, exact nested functions, casts, Unicode, literals and engine isolation.");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
