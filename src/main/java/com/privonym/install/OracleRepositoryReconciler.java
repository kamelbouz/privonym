package com.privonym.install;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Explicit adoption of observed runtime state. Never claims historical seed scripts ran. */
public final class OracleRepositoryReconciler {
    private OracleRepositoryReconciler() { }
    public static void main(String[] args)throws Exception {
        if(args.length<3)throw new IllegalArgumentException("Usage: OracleRepositoryReconciler <properties> <migrations> --inspect | --apply <inspection-sha256> | --bind <source:ruleset>");
        Properties p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
        String url=p.getProperty("repository.url");if(url==null||url.isBlank())url="jdbc:oracle:thin:@//"+p.getProperty("repository.host")+":"+p.getProperty("repository.port","1521")+"/"+p.getProperty("repository.service");
        String user=p.getProperty("repository.user"),password=p.getProperty("repository.password");
        if(password==null){if(System.console()==null)throw new IllegalStateException("Repository credentials required");password=new String(System.console().readPassword("Repository password: "));}
        try(Connection c=DriverManager.getConnection(url,user,password)) {
            c.setAutoCommit(false);Path directory=Path.of(args[1]);
            if(args[2].equals("--bind")){if(args.length!=4)throw new IllegalArgumentException("Expected source:ruleset");String[] ids=args[3].split(":");if(ids.length!=2)throw new IllegalArgumentException("Expected source:ruleset");bind(c,Long.parseLong(ids[0]),Long.parseLong(ids[1]),user);System.out.println("Current Rule Set designated; all historical Rule Sets, versions and Jobs retained.");return;}
            qualify(c,directory);String fingerprint=fingerprint(c);
            System.out.println("Qualified observed repository fingerprint: "+fingerprint);
            if(args[2].equals("--inspect")){inspect(c);c.rollback();return;}
            if(args[2].equals("--verify")) {
                try(var files=Files.list(directory)){for(Path f:files.filter(x->x.getFileName().toString().matches("V[0-9_]+.*_oracle\\.sql")).sorted().toList()){String recorded=OracleRepositoryUpgrader.recordedChecksum(c,f.getFileName().toString());if(recorded==null||!OracleRepositoryUpgrader.matchesChecksum(f,recorded))throw new IllegalStateException("Unverified migration: "+f.getFileName());}}
                try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM user_objects WHERE status='INVALID'")){r.next();if(r.getLong(1)>0)throw new IllegalStateException("Invalid schema objects");}
                inspect(c);c.rollback();System.out.println("Verified complete migration/adoption chain without replay or writes.");return;
            }
            if(!args[2].equals("--apply")||args.length!=4||!fingerprint.equals(args[3]))throw new IllegalArgumentException("Inspect first and pass the exact unchanged fingerprint to --apply");
            requireIdle(c);apply(c,directory,user);inspect(c);
            System.out.println("Observed baseline adopted separately from migration execution history; only missing boundary widening and V2_025 applied.");
        }
    }
    static void requireIdle(Connection c)throws SQLException {
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM anonymization_job WHERE status IN('PLANNING','PREFLIGHTING','RUNNING','RETRYING','PAUSING','CANCELLING','VALIDATING','REPORTING')")){r.next();if(r.getLong(1)!=0)throw new IllegalStateException("Stop active Jobs before repository maintenance");}
    }
    static void qualify(Connection c,Path directory)throws Exception {
        // Baseline physical columns plus every later runtime feature. No historical DML is replayed.
        String baseline=Files.readString(directory.getParent().resolve("generated/V2_001_privonym_repository_oracle.sql"));
        var tables=Pattern.compile("(?ms)^\\s*CREATE TABLE \\\"([A-Z_]+)\\\"\\s*\\((.*?)^\\s*\\)\\s*;").matcher(baseline);
        int count=0;
        while(tables.find()) {
            var columns=Pattern.compile("(?m)^\\s*\\\"([A-Z_]+)\\\"\\s+([A-Z0-9]+)").matcher(tables.group(2));
            while(columns.find()){requireColumn(c,tables.group(1),columns.group(1),columns.group(2));count++;}
        }
        if(count<150)throw new IllegalStateException("Baseline contract parser did not find enough columns");
        for(String spec:List.of("RULESET.DATA_SOURCE_ID","RULESET.OWNER_USER_ID","CONNECTOR.SCHEMA_NAME","CONNECTOR.ENCRYPTED_PASSWORD","ANONYMIZATION_JOB.EXECUTION_MODE","ANONYMIZATION_JOB.CHUNK_SIZE","ANONYMIZATION_JOB.CHUNK_THRESHOLD","ALGORITHM_LOOKUP_CONFIG.LOOKUP_DATASET_ID","TABLE_MASKING_RULE.CATALOG_TABLE_ID","JOB_SCRIPT.SCRIPT_CONTENT","PRIVONYM_USER.PASSWORD_HASH","PRIVONYM_USER_PRIVILEGE.USER_ID","PRIVONYM_SECURITY_EVENT.SECURITY_EVENT_ID","TRANSFORMATION_IMPORT.STATUS","TRANSFORMATION_MAPPING.TRANSFORMATION_IMPORT_ID")) {
            String[] pair=spec.split("\\.");requireColumn(c,pair[0],pair[1],null);
        }
        for(String constraint:List.of("JOB_CHUNK_PK","JOB_CHUNK_UK","JOB_CHUNK_STEP_FK","RULESET_VERSION_UK","RULESET_DATA_SOURCE_FK","MASKING_RULE_UK","ALGORITHM_LOOKUP_CONFIG_VERSION_FK","ALGORITHM_LOOKUP_CONFIG_DATASET_FK")) {
            try(PreparedStatement q=c.prepareStatement("SELECT status,validated FROM user_constraints WHERE constraint_name=?")){q.setString(1,constraint);try(ResultSet r=q.executeQuery()){if(!r.next()||!r.getString(1).equals("ENABLED")||!r.getString(2).equals("VALIDATED"))throw new IllegalStateException("Required validated constraint missing: "+constraint);}}
        }
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM (SELECT ruleset_id FROM ruleset_version WHERE status='DRAFT' GROUP BY ruleset_id HAVING COUNT(*)>1)")){r.next();if(r.getLong(1)>0)throw new IllegalStateException("Historical duplicate drafts must be resolved explicitly before adoption");}
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM user_objects WHERE status='INVALID' AND NOT(object_type='VIEW' AND object_name IN('V_CATALOG_COLUMNS','V_RULESET_RULES'))")){r.next();if(r.getLong(1)>0)throw new IllegalStateException("Invalid schema objects require verification before adoption");}
        try(var paths=Files.list(directory)) {
            for(Path f:paths.filter(x->x.getFileName().toString().matches("V2_0(0[2-9]|1[0-9]|2[0-4]).*_oracle.sql")).sorted().toList()) {
                String recorded=OracleRepositoryUpgrader.recordedChecksum(c,f.getFileName().toString());
                if(recorded!=null&&!OracleRepositoryUpgrader.matchesChecksum(f,recorded))throw new IllegalStateException("Historical migration checksum mismatch: "+f.getFileName());
            }
        }
    }
    private static void requireColumn(Connection c,String table,String column,String type)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT data_type FROM user_tab_columns WHERE table_name=? AND column_name=?")){q.setString(1,table);q.setString(2,column);try(ResultSet r=q.executeQuery()){if(!r.next()||type!=null&&!r.getString(1).startsWith(type))throw new IllegalStateException("Runtime schema contract missing/incompatible: "+table+"."+column);}}
    }
    static String fingerprint(Connection c)throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(String sql:List.of("SELECT table_name,column_name,data_type,TO_CHAR(char_length) FROM user_tab_columns ORDER BY table_name,column_id", "SELECT TO_CHAR(lookup_dataset_id),TO_CHAR(lookup_value_id),value_text,TO_CHAR(weight_value),enabled,attributes_json FROM lookup_dataset_value ORDER BY lookup_dataset_id,lookup_value_id", "SELECT TO_CHAR(algorithm_version_id),TO_CHAR(lookup_dataset_id) FROM algorithm_lookup_config ORDER BY algorithm_version_id", "SELECT code,status FROM algorithm ORDER BY code")) {
            try(Statement q=c.createStatement();ResultSet r=q.executeQuery(sql)){int columns=r.getMetaData().getColumnCount();while(r.next())for(int i=1;i<=columns;i++){String value=r.getString(i);byte[] bytes=(value==null?"<NULL>":value).getBytes(StandardCharsets.UTF_8);hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);}}
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    static String adoptedChecksum(Connection c,String name)throws SQLException {
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM user_tables WHERE table_name='PRIVONYM_SCHEMA_ADOPTION'")){r.next();if(r.getInt(1)==0)return null;}
        try(PreparedStatement q=c.prepareStatement("SELECT checksum_sha256 FROM privonym_schema_adoption WHERE migration_name=?")){q.setString(1,name);try(ResultSet r=q.executeQuery()){return r.next()?r.getString(1):null;}}
    }
    private static void apply(Connection c,Path directory,String user)throws Exception {
        // Widening is additive; old values and unresolved chunks are untouched.
        OracleRepositoryUpgrader.apply(c,directory.resolve("V2_021_portable_chunk_boundaries_oracle.sql"));
        for(String view:List.of("V_CATALOG_COLUMNS","V_RULESET_RULES"))try(Statement q=c.createStatement()){q.execute("ALTER VIEW "+view+" COMPILE");}
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM user_objects WHERE status='INVALID'")){r.next();if(r.getLong(1)>0)throw new IllegalStateException("View recompilation failed; baseline adoption refused");}
        try(Statement q=c.createStatement()){q.execute("BEGIN EXECUTE IMMEDIATE 'CREATE TABLE privonym_schema_adoption(migration_name VARCHAR2(200 CHAR) PRIMARY KEY,checksum_sha256 VARCHAR2(64 CHAR) NOT NULL,qualified_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,qualified_by VARCHAR2(128 CHAR) NOT NULL,observed_sha256 VARCHAR2(64 CHAR) NOT NULL,reason VARCHAR2(500 CHAR) NOT NULL)'; EXCEPTION WHEN OTHERS THEN IF SQLCODE<>-955 THEN RAISE;END IF;END;");}
        String observed=fingerprint(c);
        try(var paths=Files.list(directory)) {
            for(Path f:paths.filter(x->x.getFileName().toString().matches("V2_0(0[2-9]|1[0-9]|2[0-4]).*_oracle.sql")).sorted().toList()) {
                String name=f.getFileName().toString();if(OracleRepositoryUpgrader.recordedChecksum(c,name)!=null)continue;
                try(PreparedStatement q=c.prepareStatement("INSERT INTO privonym_schema_adoption(migration_name,checksum_sha256,qualified_by,observed_sha256,reason) VALUES(?,?,?,?,?)")){q.setString(1,name);q.setString(2,OracleRepositoryUpgrader.sha256(f));q.setString(3,user);q.setString(4,observed);q.setString(5,"Observed runtime schema adopted; historical seed DML was NOT replayed or claimed as executed. Boundary widening V2_021 explicitly verified.");q.executeUpdate();}
            }
        }
        c.commit();OracleRepositoryUpgrader.applyAll(c,directory,user);
    }
    static void bind(Connection c,long source,long ruleset,String actor)throws SQLException {
        requireIdle(c);
        try(PreparedStatement q=c.prepareStatement("SELECT data_source_id FROM data_source WHERE data_source_id=? FOR UPDATE")){q.setLong(1,source);try(ResultSet r=q.executeQuery()){if(!r.next())throw new IllegalArgumentException("Connection missing");}}
        try(PreparedStatement q=c.prepareStatement("SELECT ruleset_id FROM ruleset WHERE ruleset_id=? AND data_source_id=? FOR UPDATE")){q.setLong(1,ruleset);q.setLong(2,source);try(ResultSet r=q.executeQuery()){if(!r.next())throw new IllegalArgumentException("Rule Set does not belong to the connection");}}
        try(PreparedStatement q=c.prepareStatement("MERGE INTO connection_ruleset_binding b USING(SELECT ? data_source_id,? ruleset_id,? chosen_by FROM dual) s ON(b.data_source_id=s.data_source_id) WHEN MATCHED THEN UPDATE SET b.ruleset_id=s.ruleset_id,b.chosen_by=s.chosen_by,b.chosen_at=SYSTIMESTAMP WHEN NOT MATCHED THEN INSERT(data_source_id,ruleset_id,chosen_by) VALUES(s.data_source_id,s.ruleset_id,s.chosen_by)")){q.setLong(1,source);q.setLong(2,ruleset);q.setString(3,actor);q.executeUpdate();}
        c.commit();
    }
    private static void inspect(Connection c)throws SQLException {
        for(String sql:List.of("SELECT 'Sources with multiple retained historical Rule Sets: '||COUNT(*) FROM (SELECT data_source_id FROM ruleset GROUP BY data_source_id HAVING COUNT(*)>1)","SELECT column_name||': '||char_length||' characters' FROM user_tab_columns WHERE table_name='JOB_CHUNK' AND column_name IN('START_ROWID','END_ROWID') ORDER BY column_name"))try(Statement q=c.createStatement();ResultSet r=q.executeQuery(sql)){while(r.next())System.out.println(r.getString(1));}
    }
}
