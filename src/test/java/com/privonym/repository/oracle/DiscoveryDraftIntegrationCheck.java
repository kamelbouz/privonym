package com.privonym.repository.oracle;

import com.privonym.application.service.DiscoverySelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Synthetic repository fixtures only; ALWAYS rolled back, no business target connection. */
public final class DiscoveryDraftIntegrationCheck {
    private DiscoveryDraftIntegrationCheck() { }
    public static void main(String[] args)throws Exception {
        Properties p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
        String url=p.getProperty("repository.url");
        if(url==null||url.isBlank())url="jdbc:oracle:thin:@//"+p.getProperty("repository.host")+":"+p.getProperty("repository.port","1521")+"/"+p.getProperty("repository.service");
        Properties credentials=new Properties();credentials.setProperty("user",p.getProperty("repository.user"));credentials.setProperty("password",p.getProperty("repository.password"));
        credentials.setProperty("oracle.net.CONNECT_TIMEOUT","10000");credentials.setProperty("oracle.jdbc.ReadTimeout","15000");
        try(Connection c=DriverManager.getConnection(url,credentials)) {
            c.setAutoCommit(false);
            try {
                String tag="DISCOVERY_TEST_"+UUID.randomUUID().toString().replace("-","");
                long environment=number(c,"SELECT MIN(environment_id) FROM environment");
                execute(c,"INSERT INTO connector(name,technology_code,host_name,port,status) VALUES(?,'ORACLE','synthetic.invalid',1521,'INACTIVE')",tag);
                long connector=number(c,"SELECT connector_id FROM connector WHERE name=?",tag);
                execute(c,"INSERT INTO data_source(environment_id,connector_id,name,enabled) VALUES(?,?,?,'N')",environment,connector,tag);
                long ds=number(c,"SELECT data_source_id FROM data_source WHERE name=?",tag);
                execute(c,"INSERT INTO catalog_schema(data_source_id,schema_name) VALUES(?,'SYNTHETIC')",ds);
                long schema=number(c,"SELECT catalog_schema_id FROM catalog_schema WHERE data_source_id=?",ds);
                execute(c,"INSERT INTO catalog_table(catalog_schema_id,table_name) VALUES(?,'SYNTHETIC')",schema);
                long table=number(c,"SELECT catalog_table_id FROM catalog_table WHERE catalog_schema_id=?",schema);
                long[] columns=new long[3];
                for(int i=0;i<3;i++) {
                    execute(c,"INSERT INTO catalog_column(catalog_table_id,column_name,ordinal_position,native_data_type,normalized_data_type) VALUES(?,?,?,'VARCHAR2','STRING')",table,"FIELD"+i,i+1);
                    columns[i]=number(c,"SELECT catalog_column_id FROM catalog_column WHERE catalog_table_id=? AND column_name=?",table,"FIELD"+i);
                }
                execute(c,"INSERT INTO algorithm(code,name,category) VALUES(?,?,'MASKING')",tag,tag);
                long algorithm=number(c,"SELECT algorithm_id FROM algorithm WHERE code=?",tag);
                long[] versions=new long[2];
                for(int i=0;i<2;i++) {
                    execute(c,"INSERT INTO algorithm_version(algorithm_id,version_number,status,published_at) VALUES(?,?,'PUBLISHED',SYSTIMESTAMP)",algorithm,i+1);
                    versions[i]=number(c,"SELECT algorithm_version_id FROM algorithm_version WHERE algorithm_id=? AND version_number=?",algorithm,i+1);
                    execute(c,"INSERT INTO algorithm_implementation(algorithm_version_id,technology_code,implementation_type,implementation_code,code_checksum,status,published_at) VALUES(?,'ORACLE','SQL_EXPRESSION','NULL','synthetic','PUBLISHED',SYSTIMESTAMP)",versions[i]);
                }
                execute(c,"INSERT INTO algorithm_parameter_def(algorithm_version_id,parameter_name,data_type,required) VALUES(?,'synthetic_parameter','STRING','N')",versions[0]);
                long parameter=number(c,"SELECT parameter_definition_id FROM algorithm_parameter_def WHERE algorithm_version_id=?",versions[0]);
                execute(c,"INSERT INTO ruleset(data_source_id,code,name) VALUES(?,?,?)",ds,tag,tag);
                long ruleset=number(c,"SELECT ruleset_id FROM ruleset WHERE data_source_id=?",ds);
                execute(c,"INSERT INTO ruleset_version(ruleset_id,version_number,status,published_at) VALUES(?,5,'PUBLISHED',SYSTIMESTAMP)",ruleset);
                long source=number(c,"SELECT ruleset_version_id FROM ruleset_version WHERE ruleset_id=?",ruleset);
                for(int i=0;i<2;i++) {
                    execute(c,"INSERT INTO masking_rule(ruleset_version_id,catalog_column_id,algorithm_version_id,execution_order,filter_expression,enabled) VALUES(?,?,?,?,?,'N')",source,columns[i],versions[0],(i+1)*10,"SYNTHETIC_ID > 5");
                    long rule=number(c,"SELECT rule_id FROM masking_rule WHERE ruleset_version_id=? AND catalog_column_id=?",source,columns[i]);
                    execute(c,"INSERT INTO rule_parameter(rule_id,parameter_definition_id,parameter_value) VALUES(?,?,?)",rule,parameter,"retain me");
                }
                long structural=number(c,"SELECT av.algorithm_version_id FROM algorithm a JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id WHERE a.code='TRUNCATE_TABLE' AND av.status='PUBLISHED' FETCH FIRST 1 ROW ONLY");
                execute(c,"INSERT INTO table_masking_rule(ruleset_version_id,catalog_table_id,algorithm_version_id,execution_order,filter_expression,enabled) VALUES(?,?,?,50,'SYNTHETIC_ID > 10','N')",source,table,structural);
                var before=c.setSavepoint();
                var result=DiscoveryDraftWriter.create(c,ruleset,List.of(new DiscoverySelection(columns[0],tag),new DiscoverySelection(columns[1],"NULLIFY"),new DiscoverySelection(columns[2],"NULLIFY")),"synthetic");
                long draft=result.versionId();
                require(result.rulesetId()==ruleset&&result.versionNumber()==6&&!result.newRuleset(),"must create same Rule Set v6");
                require(number(c,"SELECT COUNT(*) FROM ruleset WHERE data_source_id=?",ds)==1,"one Rule Set invariant");
                require(number(c,"SELECT COUNT(*) FROM masking_rule WHERE ruleset_version_id=?",draft)==3,"must merge new and existing columns");
                require(number(c,"SELECT algorithm_version_id FROM masking_rule WHERE ruleset_version_id=? AND catalog_column_id=?",draft,columns[0])==versions[0],"unchanged code must preserve pinned algorithm version");
                require(number(c,"SELECT COUNT(*) FROM rule_parameter p JOIN masking_rule r ON r.rule_id=p.rule_id WHERE r.ruleset_version_id=?",draft)==1,"retain unchanged parameters, remove incompatible parameters only in draft");
                require(number(c,"SELECT COUNT(*) FROM masking_rule WHERE ruleset_version_id=? AND enabled='N' AND DBMS_LOB.SUBSTR(filter_expression,100,1)='SYNTHETIC_ID > 5'",draft)==2,"preserve filters/order/enabled state");
                require(number(c,"SELECT COUNT(*) FROM table_masking_rule WHERE ruleset_version_id=? AND enabled='N'",draft)==1,"preserve table rules");
                require(number(c,"SELECT COUNT(*) FROM rule_parameter p JOIN masking_rule r ON r.rule_id=p.rule_id WHERE r.ruleset_version_id=?",source)==2,"published parameters must be unchanged");
                boolean conflict=false;
                try{DiscoveryDraftWriter.create(c,ruleset,List.of(new DiscoverySelection(columns[2],"NULLIFY")),"synthetic");}
                catch(IllegalStateException expected){conflict=true;}
                require(conflict,"existing draft must be rejected");
                c.rollback(before);
                var unchanged=DiscoveryDraftWriter.create(c,ruleset,List.of(),"synthetic");
                require(number(c,"SELECT COUNT(*) FROM masking_rule WHERE ruleset_version_id=?",unchanged.versionId())==2,
                        "empty selection must still allow a faithful draft copy of the existing version");
                c.rollback(before);
                var atomic=c.setSavepoint();boolean rejected=false;
                try{DiscoveryDraftWriter.create(c,ruleset,List.of(new DiscoverySelection(Long.MAX_VALUE,"NULLIFY")),"synthetic");}
                catch(IllegalArgumentException expected){rejected=true;c.rollback(atomic);}
                require(rejected&&number(c,"SELECT COUNT(*) FROM ruleset_version WHERE ruleset_id=? AND status='DRAFT'",ruleset)==0,"invalid selection must roll back new draft");
                System.out.println("Discovery draft integration passed: same Rule Set v5 -> v6, full rule copy, parameter preservation, selected overrides, existing draft guard and rollback. All synthetic fixtures rolled back.");
            } finally {c.rollback();}
        }
    }
    private static void execute(Connection c,String sql,Object... values)throws Exception {
        try(PreparedStatement s=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);s.executeUpdate();}
    }
    private static long number(Connection c,String sql,Object... values)throws Exception {
        try(PreparedStatement s=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("Missing fixture");return r.getLong(1);}}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
