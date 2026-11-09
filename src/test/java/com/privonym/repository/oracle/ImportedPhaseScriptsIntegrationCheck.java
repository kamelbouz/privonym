package com.privonym.repository.oracle;

import com.privonym.application.service.OracleSqlInventoryPreviewService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Properties;
import java.util.UUID;

/** Synthetic repository metadata only: no target connection and no source DDL execution. */
public final class ImportedPhaseScriptsIntegrationCheck {
    private ImportedPhaseScriptsIntegrationCheck() { }
    public static void main(String[] args)throws Exception {
        Properties p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
        String url=p.getProperty("repository.url");if(url==null||url.isBlank())url="jdbc:oracle:thin:@//"+p.getProperty("repository.host")+":"+p.getProperty("repository.port","1521")+"/"+p.getProperty("repository.service");
        String tag="PHASE_"+UUID.randomUUID().toString().replace("-", "").substring(0,12).toUpperCase(java.util.Locale.ROOT);
        String user=p.getProperty("repository.user");Driver driver=null;
        try(Connection c=DriverManager.getConnection(url,user,p.getProperty("repository.password"))) {
            c.setAutoCommit(false);
            try {
                long environment=number(c,"SELECT MIN(environment_id) FROM environment");
                execute(c,"INSERT INTO connector(name,technology_code,host_name,port,service_name,username_value,schema_name,encrypted_password,status) VALUES(?,'ORACLE',?,?,?,?,?,?,'ACTIVE')",tag,p.getProperty("repository.host"),Integer.valueOf(p.getProperty("repository.port","1521")),p.getProperty("repository.service"),user,user.toUpperCase(java.util.Locale.ROOT),"fixture-not-used");
                long connector=number(c,"SELECT connector_id FROM connector WHERE name=?",tag);
                execute(c,"INSERT INTO data_source(environment_id,connector_id,name,enabled) VALUES(?,?,?,'Y')",environment,connector,tag);
                long ds=number(c,"SELECT data_source_id FROM data_source WHERE name=?",tag);
                execute(c,"INSERT INTO catalog_schema(data_source_id,schema_name) VALUES(?,?)",ds,user.toUpperCase(java.util.Locale.ROOT));
                long schema=number(c,"SELECT catalog_schema_id FROM catalog_schema WHERE data_source_id=?",ds);
                execute(c,"INSERT INTO catalog_table(catalog_schema_id,table_name) VALUES(?,'T')",schema);
                long table=number(c,"SELECT catalog_table_id FROM catalog_table WHERE catalog_schema_id=?",schema);
                execute(c,"INSERT INTO catalog_column(catalog_table_id,column_name,ordinal_position,native_data_type,normalized_data_type,data_length,nullable,primary_key_member) VALUES(?,'C',1,'VARCHAR2','STRING',255,'Y','N')",table);
                String source="SELECT TO_CHAR(SYSDATE,'HH24:MI:SS') FROM dual;\n"
                    +"ALTER TABLE &schema..T ADD COPY_FLAG NUMBER;\nUPDATE &schema..T SET COPY_FLAG=1 WHERE FLAG=1;\n"
                    +"UPDATE &schema..T SET C=CONCAT('USER_',ID_USER);\n"
                    +"ALTER TABLE &schema..T SET UNUSED COLUMN C;\nALTER TABLE &schema..T ADD C VARCHAR2(255);";
                var preview=new OracleSqlInventoryPreviewService().parse(source);
                require(preview.columnCount()==1&&!preview.preScript().isBlank()&&!preview.postScript().isBlank(),"Phase classification failed");
                Class<?> fixture=Class.forName("com.privonym.repository.oracle.ExactOracleImportIntegrationCheck$FixtureDriver");
                var constructor=fixture.getDeclaredConstructor(Connection.class);constructor.setAccessible(true);
                driver=(Driver)constructor.newInstance(c);DriverManager.registerDriver(driver);
                String fixtureUrl="jdbc:privonym-stl-fixture";
                long version;
                try(var importer=new OracleDraftInventoryAdapter(fixtureUrl,"fixture",new char[0])) {
                    var imported=importer.createAuthorized(ds,tag,tag,source,preview,"synthetic",0,0);
                    require(imported.reviewItems()==0&&imported.columnRulesCreated()==1,"Future PRE column incorrectly became an unresolved masking rule");
                    version=imported.rulesetVersionId();
                }
                long ruleset=number(c,"SELECT ruleset_id FROM ruleset_version WHERE ruleset_version_id=?",version);
                execute(c,"UPDATE ruleset_version SET status='PUBLISHED',published_at=SYSTIMESTAMP WHERE ruleset_version_id=?",version);
                try(var admin=new OracleRulesetAdministrationAdapter(fixtureUrl,"fixture",new char[0])) {
                    var job=admin.createJob(ruleset,version,tag,1,"AUTO",1000,1000,"synthetic");
                    String pre=text(c,"SELECT script_content FROM job_script WHERE job_id=? AND script_phase='PRE'",job.jobId());
                    String post=text(c,"SELECT script_content FROM job_script WHERE job_id=? AND script_phase='POST'",job.jobId());
                    require(pre.indexOf("ADD COPY_FLAG")<pre.indexOf("SET COPY_FLAG")&&!pre.contains("&schema"),"PRE snapshot order/schema invalid");
                    require(post.indexOf("SET UNUSED")<post.indexOf("ADD C")&&!post.contains("SELECT"),"POST snapshot order invalid");
                    String sql=admin.exportSql(ruleset,version).sql();
                    require(sql.indexOf("ADD COPY_FLAG")<sql.indexOf("CONCAT('USER_'" )&&sql.indexOf("CONCAT('USER_'" )<sql.indexOf("SET UNUSED COLUMN C"),"Export PRE/masking/POST order invalid");
                    var draft=DiscoveryDraftWriter.create(c,ruleset,java.util.List.of(),"synthetic");
                    require(ImportedPhaseScripts.phase(c,draft.versionId(),"PRE").equals(preview.preScript())&&ImportedPhaseScripts.phase(c,draft.versionId(),"POST").equals(preview.postScript()),"Draft copy lost phase provenance/classification");
                    execute(c,"UPDATE job_script SET file_name='manual-pre.sql',script_content='manual override' WHERE job_id=? AND script_phase='PRE'",job.jobId());
                    ImportedPhaseScripts.attach(c,job.jobId(),version);
                    require(text(c,"SELECT script_content FROM job_script WHERE job_id=? AND script_phase='PRE'",job.jobId()).equals("manual override"),"Manual script was overwritten");
                }
                System.out.println("Import PRE/POST integration passed: persisted source, generated Job snapshots, schema binding, ordered export, v2 copy and manual override preservation.");
            }finally{if(driver!=null)DriverManager.deregisterDriver(driver);c.rollback();System.out.println("All synthetic metadata rolled back; no existing Job or business table changed; no target DDL executed.");}
        }
    }
    private static void execute(Connection c,String sql,Object...values)throws SQLException{try(PreparedStatement q=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);q.executeUpdate();}}
    private static long number(Connection c,String sql,Object...values)throws SQLException{try(PreparedStatement q=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);try(ResultSet rows=q.executeQuery()){rows.next();return rows.getLong(1);}}}
    private static String text(Connection c,String sql,long id)throws SQLException{try(PreparedStatement q=c.prepareStatement(sql)){q.setLong(1,id);try(ResultSet rows=q.executeQuery()){if(!rows.next())throw new AssertionError("Missing phase script");return rows.getString(1);}}}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
