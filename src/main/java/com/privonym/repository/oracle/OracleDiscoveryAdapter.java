package com.privonym.repository.oracle;

import com.privonym.application.port.in.DiscoveryUseCase;
import com.privonym.application.service.DiscoveryConnection;
import com.privonym.application.service.DiscoveryCreationResult;
import com.privonym.application.service.DiscoveryFinding;
import com.privonym.application.service.DiscoveryScan;
import com.privonym.application.service.DiscoverySelection;
import com.privonym.application.service.MaskingAlgorithmOption;
import com.privonym.application.service.EnvironmentOption;
import com.privonym.application.service.ConnectionCreationResult;
import com.privonym.application.service.ConnectionUpdateCommand;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class OracleDiscoveryAdapter implements DiscoveryUseCase, AutoCloseable {
    private final String jdbcUrl; private final String username; private final char[] password;
    private final byte[] localSecretKey;
    public OracleDiscoveryAdapter(String jdbcUrl, String username, char[] password) {
        this.jdbcUrl=Objects.requireNonNull(jdbcUrl); this.username=Objects.requireNonNull(username);
        this.password=Objects.requireNonNull(password).clone();
        this.localSecretKey=loadOrCreateLocalSecretKey();
        // Repository schema and seed data are installed explicitly through migrations.
    }

    @Override public List<DiscoveryConnection> listConnections() {
        String sql="""
            SELECT d.data_source_id,d.environment_id,d.name,e.code environment_code,c.technology_code,
                   c.host_name||':'||c.port||'/'||
                     CASE WHEN c.technology_code IN('POSTGRESQL','SQLSERVER')
                          THEN NVL(NULLIF(LOWER(TRIM(c.database_name)),'null'),NULLIF(LOWER(TRIM(c.service_name)),'null'))
                          ELSE NVL(c.service_name,c.database_name) END endpoint,
                   c.host_name,c.port,c.service_name,c.database_name,c.username_value,
                   sr.provider_type, sr.reference_key,
                   CASE WHEN c.encrypted_password IS NOT NULL THEN 1 ELSE 0 END password_configured,
                   d.enabled,
                   CASE WHEN EXISTS(SELECT 1 FROM ruleset r WHERE r.data_source_id=d.data_source_id) THEN 1 ELSE 0 END has_ruleset,
                   NVL(c.schema_name,(SELECT MIN(s.schema_name) FROM catalog_schema s WHERE s.data_source_id=d.data_source_id)) schema_name,
                   (SELECT COUNT(*) FROM catalog_schema s WHERE s.data_source_id=d.data_source_id) schema_count,
                   (SELECT COUNT(*) FROM catalog_column cc JOIN catalog_table ct ON ct.catalog_table_id=cc.catalog_table_id
                     JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id WHERE cs.data_source_id=d.data_source_id) column_count
              FROM data_source d JOIN connector c ON c.connector_id=d.connector_id
              JOIN environment e ON e.environment_id=d.environment_id
              LEFT JOIN secret_reference sr ON sr.secret_reference_id=c.secret_reference_id
             ORDER BY e.name,d.name
            """;
        List<DiscoveryConnection> result=new ArrayList<>();
        try(Connection c=open(true);Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){
            while(r.next()) result.add(new DiscoveryConnection(r.getLong("data_source_id"),r.getLong("environment_id"),r.getString("name"),r.getString("environment_code"),r.getString("technology_code"),
                    r.getString("endpoint"),r.getString("host_name"),r.getInt("port"),cleanOptionalValue(r.getString("service_name")),cleanOptionalValue(r.getString("database_name")),
                    r.getString("username_value"),r.getString("schema_name"),cleanOptionalValue(r.getString("provider_type")),cleanOptionalValue(r.getString("reference_key")),r.getInt("password_configured")==1,"Y".equals(r.getString("enabled")),
                    r.getInt("has_ruleset")==1,r.getInt("schema_count"),r.getInt("column_count")));
            return result;
        }catch(SQLException e){throw failure("list discovery connections",e);}
    }

    @Override public List<EnvironmentOption> listEnvironmentOptions(){List<EnvironmentOption> result=new ArrayList<>();try(Connection c=open(true);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT environment_id,code,name FROM environment WHERE status='ACTIVE' ORDER BY name")){while(r.next())result.add(new EnvironmentOption(r.getLong(1),r.getString(2),r.getString(3)));return result;}catch(SQLException e){throw failure("list environments",e);}}

    @Override public ConnectionCreationResult createConnection(long environmentId,String name,String technology,String schemaName,String host,int port,String service,String database,String user,String provider,String reference,char[] targetPassword){
        technology=technology.toUpperCase(Locale.ROOT);provider=provider==null?"":provider.trim().toUpperCase(Locale.ROOT);reference=reference==null?"":reference.trim();schemaName=schemaName.trim();service=cleanOptionalValue(service);database=cleanOptionalValue(database);if("ORACLE".equals(technology))schemaName=schemaName.toUpperCase(Locale.ROOT);if(environmentId<=0)throw new IllegalArgumentException("Environment is required");if(!List.of("ORACLE","POSTGRESQL","SQLSERVER").contains(technology))throw new IllegalArgumentException("Only Oracle, PostgreSQL and SQL Server technologies are supported");
        if(user.isBlank())throw new IllegalArgumentException("Username is required");if(targetPassword==null||targetPassword.length==0)throw new IllegalArgumentException("Password is required");if(schemaName.isBlank())throw new IllegalArgumentException("Schema name is required");if(host.isBlank())throw new IllegalArgumentException("Server is required");if(port<1||port>65535)throw new IllegalArgumentException("Port must be between 1 and 65535");if(service.isBlank()&&database.isBlank())throw new IllegalArgumentException("Service name or database name is required");if(!reference.isBlank()&&provider.isBlank())throw new IllegalArgumentException("Secret provider is required when a secret reference is specified");if(reference.isBlank()&&!provider.isBlank()&&!"ENCRYPTED_LOCAL".equals(provider))throw new IllegalArgumentException("Secret reference key is required when an external secret provider is selected");
        if("POSTGRESQL".equals(technology)&&database.isBlank())throw new IllegalArgumentException("Database name is required for PostgreSQL");
        if("SQLSERVER".equals(technology)&&database.isBlank())throw new IllegalArgumentException("Database name is required for SQL Server");
        boolean generatedName=name.isBlank();
        if(generatedName)name=generatedConnectionName(technology,schemaName,host,service,database);
        else if(name.length()>100)throw new IllegalArgumentException("Connection name cannot exceed 100 characters");
        try(Connection c=open(false)){try{if(generatedName)name=availableConnectionName(c,name);if("ENCRYPTED_LOCAL".equals(provider)&&reference.isBlank())reference="CONNECTION_"+name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+","_");Long secretId=null;if(!reference.isBlank()){if(!List.of("ORACLE_WALLET","VAULT","K8S_SECRET","CLOUD_SECRET","ENCRYPTED_LOCAL").contains(provider))throw new IllegalArgumentException("Invalid secret provider");try(PreparedStatement s=c.prepareStatement("MERGE INTO secret_reference t USING(SELECT ? provider_type,? reference_key FROM dual) x ON(t.provider_type=x.provider_type AND t.reference_key=x.reference_key) WHEN NOT MATCHED THEN INSERT(provider_type,reference_key,description) VALUES(x.provider_type,x.reference_key,'Created from Connections')")){s.setString(1,provider);s.setString(2,reference);s.executeUpdate();}try(PreparedStatement s=c.prepareStatement("SELECT secret_reference_id FROM secret_reference WHERE provider_type=? AND reference_key=?")){s.setString(1,provider);s.setString(2,reference);try(ResultSet r=s.executeQuery()){r.next();secretId=r.getLong(1);}}}
            try(PreparedStatement s=c.prepareStatement("INSERT INTO connector(name,technology_code,host_name,port,database_name,service_name,username_value,secret_reference_id,schema_name,encrypted_password,status) VALUES(?,?,?,?,?,?,?,?,?,?, 'ACTIVE')")){s.setString(1,name);s.setString(2,technology);s.setString(3,host);s.setInt(4,port);s.setString(5,database.isBlank()?null:database);s.setString(6,service.isBlank()?null:service);s.setString(7,user);if(secretId==null)s.setNull(8,java.sql.Types.NUMERIC);else s.setLong(8,secretId);s.setString(9,schemaName);s.setString(10,encrypt(targetPassword));s.executeUpdate();}
            long connectorId;try(PreparedStatement s=c.prepareStatement("SELECT connector_id FROM connector WHERE name=?")){s.setString(1,name);try(ResultSet r=s.executeQuery()){r.next();connectorId=r.getLong(1);}}
            try(PreparedStatement s=c.prepareStatement("INSERT INTO data_source(environment_id,connector_id,name,description,enabled) VALUES(?,?,?,?,'Y')")){s.setLong(1,environmentId);s.setLong(2,connectorId);s.setString(3,name);s.setString(4,"Schema "+schemaName+" · Created from Connections");s.executeUpdate();}
            long dataSourceId;try(PreparedStatement s=c.prepareStatement("SELECT data_source_id FROM data_source WHERE environment_id=? AND name=?")){s.setLong(1,environmentId);s.setString(2,name);try(ResultSet r=s.executeQuery()){r.next();dataSourceId=r.getLong(1);}}
            String targetUrl=targetJdbcUrl(technology,host,port,service,database);try(Connection target=DriverManager.getConnection(targetUrl,user,new String(targetPassword))){if(!target.isValid(10))throw new SQLException("Target connection validation failed");}
            c.commit();return new ConnectionCreationResult(connectorId,dataSourceId,name);
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}}catch(SQLException e){if(e.getErrorCode()==1)throw new IllegalArgumentException("Connection name already exists");throw failure("create connection",e);}}

    private static String generatedConnectionName(String technology,String schemaName,String host,
            String service,String database){
        String endpoint=database.isBlank()?service:database;
        String generated=(technology+"_"+schemaName+"_"+host+"_"+endpoint)
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+","_")
                .replaceAll("^_+|_+$","");
        if(generated.isBlank())generated="CONNECTION";
        return generated.length()>100?generated.substring(0,100):generated;
    }

    private static String availableConnectionName(Connection connection,String requested)
            throws SQLException{
        String base=requested.length()>95?requested.substring(0,95):requested;
        for(int suffix=1;suffix<10000;suffix++){
            String candidate=suffix==1?requested:base+"_"+suffix;
            try(PreparedStatement statement=connection.prepareStatement(
                    "SELECT 1 FROM connector WHERE UPPER(name)=UPPER(?)")){
                statement.setString(1,candidate);
                try(ResultSet row=statement.executeQuery()){if(!row.next())return candidate;}
            }
        }
        throw new IllegalStateException("Unable to generate a unique connection name");
    }

    @Override public void deleteConnection(long dataSourceId){try(Connection c=open(false)){try{long connectorId;try(PreparedStatement s=c.prepareStatement("SELECT connector_id FROM data_source WHERE data_source_id=? FOR UPDATE")){s.setLong(1,dataSourceId);try(ResultSet r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Connection not found");connectorId=r.getLong(1);}}if(rulesetExists(c,dataSourceId))throw new IllegalStateException("A connection linked to a Rule Set cannot be deleted");
        try(PreparedStatement s=c.prepareStatement("DELETE FROM catalog_relationship WHERE parent_table_id IN (SELECT ct.catalog_table_id FROM catalog_table ct JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id WHERE cs.data_source_id=?) OR child_table_id IN (SELECT ct.catalog_table_id FROM catalog_table ct JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id WHERE cs.data_source_id=?)")){s.setLong(1,dataSourceId);s.setLong(2,dataSourceId);s.executeUpdate();}
        try(PreparedStatement s=c.prepareStatement("DELETE FROM catalog_column WHERE catalog_table_id IN (SELECT ct.catalog_table_id FROM catalog_table ct JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id WHERE cs.data_source_id=?)")){s.setLong(1,dataSourceId);s.executeUpdate();}
        try(PreparedStatement s=c.prepareStatement("DELETE FROM catalog_table WHERE catalog_schema_id IN (SELECT catalog_schema_id FROM catalog_schema WHERE data_source_id=?)")){s.setLong(1,dataSourceId);s.executeUpdate();}
        try(PreparedStatement s=c.prepareStatement("DELETE FROM catalog_schema WHERE data_source_id=?")){s.setLong(1,dataSourceId);s.executeUpdate();}
        try(PreparedStatement s=c.prepareStatement("DELETE FROM data_source WHERE data_source_id=?")){s.setLong(1,dataSourceId);s.executeUpdate();}
        try(PreparedStatement s=c.prepareStatement("DELETE FROM connector WHERE connector_id=? AND NOT EXISTS(SELECT 1 FROM data_source WHERE connector_id=?)")){s.setLong(1,connectorId);s.setLong(2,connectorId);s.executeUpdate();}c.commit();
    }catch(SQLException|RuntimeException e){c.rollback();throw e;}}catch(SQLException e){throw failure("delete connection",e);}}

    private static void synchronizeCatalog(Connection repository,Connection target,long dataSourceId,String owner,String technology)throws SQLException{
        try(PreparedStatement s=repository.prepareStatement("MERGE INTO catalog_schema t USING(SELECT ? data_source_id,? schema_name FROM dual) x ON(t.data_source_id=x.data_source_id AND t.schema_name=x.schema_name) WHEN MATCHED THEN UPDATE SET t.synchronized_at=SYSTIMESTAMP WHEN NOT MATCHED THEN INSERT(data_source_id,schema_name) VALUES(x.data_source_id,x.schema_name)")){s.setLong(1,dataSourceId);s.setString(2,owner);s.executeUpdate();}
        long schemaId;try(PreparedStatement s=repository.prepareStatement("SELECT catalog_schema_id FROM catalog_schema WHERE data_source_id=? AND schema_name=?")){s.setLong(1,dataSourceId);s.setString(2,owner);try(ResultSet r=s.executeQuery()){r.next();schemaId=r.getLong(1);}}
        String safeOwner=owner.replace("'","''");
        String metadata=switch(technology){
            case "POSTGRESQL" -> "SELECT c.table_name,c.column_name,c.ordinal_position column_id,c.data_type,"+
                  "c.character_maximum_length data_length,c.numeric_precision data_precision,"+
                  "c.numeric_scale data_scale,CASE WHEN c.is_nullable='YES' THEN 'Y' ELSE 'N' END nullable,"+
                  "GREATEST(COALESCE(cls.reltuples,0),0)::bigint num_rows,CASE WHEN pk.column_name IS NULL THEN 'N' ELSE 'Y' END primary_key_member "+
                  "FROM information_schema.columns c LEFT JOIN pg_catalog.pg_namespace ns ON ns.nspname=c.table_schema "+
                  "LEFT JOIN pg_catalog.pg_class cls ON cls.relnamespace=ns.oid AND cls.relname=c.table_name AND cls.relkind IN ('r','p') "+
                  "LEFT JOIN (SELECT kcu.table_schema,kcu.table_name,kcu.column_name FROM information_schema.table_constraints tc "+
                  "JOIN information_schema.key_column_usage kcu ON kcu.constraint_name=tc.constraint_name AND kcu.table_schema=tc.table_schema "+
                  "WHERE tc.constraint_type='PRIMARY KEY') pk ON pk.table_schema=c.table_schema AND pk.table_name=c.table_name AND pk.column_name=c.column_name "+
                  "WHERE c.table_schema='"+safeOwner+"' ORDER BY c.table_name,c.ordinal_position";
            case "SQLSERVER" -> "SELECT t.name table_name,c.name column_name,c.column_id,ty.name data_type,"+
                  "CASE WHEN ty.name IN ('nvarchar','nchar') AND c.max_length>0 THEN c.max_length/2 WHEN c.max_length>0 THEN c.max_length ELSE NULL END data_length,"+
                  "c.precision data_precision,c.scale data_scale,CASE WHEN c.is_nullable=1 THEN 'Y' ELSE 'N' END nullable,"+
                  "COALESCE(rc.num_rows,0) num_rows,CASE WHEN pk.column_id IS NULL THEN 'N' ELSE 'Y' END primary_key_member "+
                  "FROM sys.tables t JOIN sys.schemas s ON s.schema_id=t.schema_id JOIN sys.columns c ON c.object_id=t.object_id "+
                  "JOIN sys.types ty ON ty.user_type_id=c.user_type_id LEFT JOIN (SELECT object_id,SUM(rows) num_rows FROM sys.partitions WHERE index_id IN(0,1) GROUP BY object_id) rc ON rc.object_id=t.object_id "+
                  "LEFT JOIN (SELECT ic.object_id,ic.column_id FROM sys.indexes i JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id WHERE i.is_primary_key=1) pk ON pk.object_id=c.object_id AND pk.column_id=c.column_id "+
                  "WHERE s.name=N'"+safeOwner+"' ORDER BY t.name,c.column_id";
            case "ORACLE" -> "SELECT c.table_name,c.column_name,c.column_id,c.data_type,CASE WHEN c.data_type IN ('NCHAR','NVARCHAR2') THEN c.char_length ELSE c.data_length END data_length,c.data_precision,c.data_scale,c.nullable,NVL(t.num_rows,0) num_rows,CASE WHEN pk.column_name IS NULL THEN 'N' ELSE 'Y' END primary_key_member FROM all_tab_columns c LEFT JOIN all_tables t ON t.owner=c.owner AND t.table_name=c.table_name LEFT JOIN (SELECT acc.owner,acc.table_name,acc.column_name FROM all_constraints ac JOIN all_cons_columns acc ON acc.owner=ac.owner AND acc.constraint_name=ac.constraint_name WHERE ac.constraint_type='P') pk ON pk.owner=c.owner AND pk.table_name=c.table_name AND pk.column_name=c.column_name WHERE c.owner='"+safeOwner+"' ORDER BY c.table_name,c.column_id";
            default -> throw new IllegalArgumentException("Unsupported target technology: "+technology);
        };
        try(Statement statement=target.createStatement();ResultSet row=statement.executeQuery(metadata)){while(row.next()){String table=row.getString("table_name");try(PreparedStatement s=repository.prepareStatement("MERGE INTO catalog_table t USING(SELECT ? catalog_schema_id,? table_name FROM dual) x ON(t.catalog_schema_id=x.catalog_schema_id AND t.table_name=x.table_name) WHEN MATCHED THEN UPDATE SET t.synchronized_at=SYSTIMESTAMP,t.estimated_row_count=? WHEN NOT MATCHED THEN INSERT(catalog_schema_id,table_name,object_type,estimated_row_count,enabled) VALUES(x.catalog_schema_id,x.table_name,'TABLE',?,'Y')")){s.setLong(1,schemaId);s.setString(2,table);s.setLong(3,row.getLong("num_rows"));s.setLong(4,row.getLong("num_rows"));s.executeUpdate();}long tableId;try(PreparedStatement s=repository.prepareStatement("SELECT catalog_table_id FROM catalog_table WHERE catalog_schema_id=? AND table_name=?")){s.setLong(1,schemaId);s.setString(2,table);try(ResultSet r=s.executeQuery()){r.next();tableId=r.getLong(1);}}String type=row.getString("data_type");try(PreparedStatement s=repository.prepareStatement("MERGE INTO catalog_column t USING(SELECT ? catalog_table_id,? column_name FROM dual) x ON(t.catalog_table_id=x.catalog_table_id AND t.column_name=x.column_name) WHEN MATCHED THEN UPDATE SET t.ordinal_position=?,t.native_data_type=?,t.normalized_data_type=?,t.data_length=?,t.numeric_precision=?,t.numeric_scale=?,t.nullable=?,t.primary_key_member=?,t.synchronized_at=SYSTIMESTAMP WHEN NOT MATCHED THEN INSERT(catalog_table_id,column_name,ordinal_position,native_data_type,normalized_data_type,data_length,numeric_precision,numeric_scale,nullable,primary_key_member) VALUES(x.catalog_table_id,x.column_name,?,?,?,?,?,?,?,?)")){int i=1;s.setLong(i++,tableId);s.setString(i++,row.getString("column_name"));for(int pass=0;pass<2;pass++){s.setInt(i++,row.getInt("column_id"));s.setString(i++,type);s.setString(i++,normalize(type));s.setObject(i++,row.getObject("data_length"));s.setObject(i++,row.getObject("data_precision"));s.setObject(i++,row.getObject("data_scale"));s.setString(i++,row.getString("nullable"));s.setString(i++,row.getString("primary_key_member"));}s.executeUpdate();}}}
        synchronizeRelationships(repository,target,dataSourceId,owner,technology);
    }

    private static void synchronizeRelationships(Connection repository,Connection target,
            long dataSourceId,String owner,String technology)throws SQLException{
        try(PreparedStatement s=repository.prepareStatement("DELETE FROM catalog_relationship_column WHERE relationship_id IN (SELECT cr.relationship_id FROM catalog_relationship cr JOIN catalog_table ct ON ct.catalog_table_id=cr.child_table_id JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id WHERE cs.data_source_id=?)")){s.setLong(1,dataSourceId);s.executeUpdate();}
        try(PreparedStatement s=repository.prepareStatement("DELETE FROM catalog_relationship WHERE child_table_id IN (SELECT ct.catalog_table_id FROM catalog_table ct JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id WHERE cs.data_source_id=?)")){s.setLong(1,dataSourceId);s.executeUpdate();}
        String safeOwner=owner.replace("'","''");
        String sql=switch(technology){
            case "ORACLE" -> "SELECT fk.constraint_name,pk.owner parent_schema,pk.table_name parent_table,fk.owner child_schema,fk.table_name child_table,pcc.column_name parent_column,fcc.column_name child_column,fcc.position position_number FROM all_constraints fk JOIN all_constraints pk ON pk.owner=fk.r_owner AND pk.constraint_name=fk.r_constraint_name JOIN all_cons_columns fcc ON fcc.owner=fk.owner AND fcc.constraint_name=fk.constraint_name JOIN all_cons_columns pcc ON pcc.owner=pk.owner AND pcc.constraint_name=pk.constraint_name AND pcc.position=fcc.position WHERE fk.constraint_type='R' AND fk.owner='"+safeOwner+"' AND pk.owner='"+safeOwner+"' ORDER BY fk.constraint_name,fcc.position";
            case "POSTGRESQL" -> "SELECT con.conname constraint_name,pn.nspname parent_schema,p.relname parent_table,cn.nspname child_schema,c.relname child_table,pa.attname parent_column,ca.attname child_column,k.position_number FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid JOIN pg_namespace cn ON cn.oid=c.relnamespace JOIN pg_class p ON p.oid=con.confrelid JOIN pg_namespace pn ON pn.oid=p.relnamespace JOIN LATERAL unnest(con.conkey,con.confkey) WITH ORDINALITY AS k(child_attnum,parent_attnum,position_number) ON true JOIN pg_attribute ca ON ca.attrelid=c.oid AND ca.attnum=k.child_attnum JOIN pg_attribute pa ON pa.attrelid=p.oid AND pa.attnum=k.parent_attnum WHERE con.contype='f' AND cn.nspname='"+safeOwner+"' AND pn.nspname='"+safeOwner+"' ORDER BY con.conname,k.position_number";
            case "SQLSERVER" -> "SELECT fk.name constraint_name,ps.name parent_schema,pt.name parent_table,cs.name child_schema,ct.name child_table,pc.name parent_column,cc.name child_column,fkc.constraint_column_id position_number FROM sys.foreign_keys fk JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id=fk.object_id JOIN sys.tables ct ON ct.object_id=fk.parent_object_id JOIN sys.schemas cs ON cs.schema_id=ct.schema_id JOIN sys.tables pt ON pt.object_id=fk.referenced_object_id JOIN sys.schemas ps ON ps.schema_id=pt.schema_id JOIN sys.columns cc ON cc.object_id=ct.object_id AND cc.column_id=fkc.parent_column_id JOIN sys.columns pc ON pc.object_id=pt.object_id AND pc.column_id=fkc.referenced_column_id WHERE cs.name=N'"+safeOwner+"' AND ps.name=N'"+safeOwner+"' ORDER BY fk.name,fkc.constraint_column_id";
            default -> throw new IllegalArgumentException("Unsupported target technology: "+technology);
        };
        try(Statement statement=target.createStatement();ResultSet row=statement.executeQuery(sql)){
            String previous="";long relationshipId=0;
            while(row.next()){
                long parentTableId=catalogTableId(repository,dataSourceId,row.getString("parent_schema"),row.getString("parent_table"));
                long childTableId=catalogTableId(repository,dataSourceId,row.getString("child_schema"),row.getString("child_table"));
                String constraint=row.getString("constraint_name");String key=childTableId+"\u0000"+constraint;
                if(!key.equals(previous)){
                    try(PreparedStatement s=repository.prepareStatement("INSERT INTO catalog_relationship(parent_table_id,child_table_id,constraint_name,relationship_type) VALUES(?,?,?,'FOREIGN_KEY')")){
                        s.setLong(1,parentTableId);s.setLong(2,childTableId);s.setString(3,constraint);s.executeUpdate();
                    }
                    try(PreparedStatement s=repository.prepareStatement("SELECT relationship_id FROM catalog_relationship WHERE child_table_id=? AND constraint_name=?")){
                        s.setLong(1,childTableId);s.setString(2,constraint);
                        try(ResultSet id=s.executeQuery()){if(!id.next())throw new SQLException("No relationship identity returned for "+constraint);relationshipId=id.getLong(1);}
                    }
                    previous=key;
                }
                try(PreparedStatement s=repository.prepareStatement("INSERT INTO catalog_relationship_column(relationship_id,position_number,parent_column_id,child_column_id) VALUES(?,?,?,?)")){
                    s.setLong(1,relationshipId);s.setInt(2,row.getInt("position_number"));
                    s.setLong(3,catalogColumnId(repository,parentTableId,row.getString("parent_column")));
                    s.setLong(4,catalogColumnId(repository,childTableId,row.getString("child_column")));s.executeUpdate();
                }
            }
        }
    }

    private static long catalogTableId(Connection repository,long dataSourceId,String schema,
            String table)throws SQLException{
        try(PreparedStatement s=repository.prepareStatement("SELECT ct.catalog_table_id FROM catalog_table ct JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id WHERE cs.data_source_id=? AND cs.schema_name=? AND ct.table_name=?")){
            s.setLong(1,dataSourceId);s.setString(2,schema);s.setString(3,table);
            try(ResultSet row=s.executeQuery()){if(!row.next())throw new SQLException("Catalog table not found for relationship: "+schema+"."+table);return row.getLong(1);}
        }
    }

    private static long catalogColumnId(Connection repository,long tableId,String column)
            throws SQLException{
        try(PreparedStatement s=repository.prepareStatement("SELECT catalog_column_id FROM catalog_column WHERE catalog_table_id=? AND column_name=?")){
            s.setLong(1,tableId);s.setString(2,column);
            try(ResultSet row=s.executeQuery()){if(!row.next())throw new SQLException("Catalog column not found for relationship: "+column);return row.getLong(1);}
        }
    }

    private static String resolvePostgresqlSchema(Connection target,String requested)throws SQLException{
        String sql="SELECT schema_name FROM information_schema.schemata " +
                "WHERE LOWER(schema_name)=LOWER(?) " +
                "ORDER BY CASE WHEN schema_name=? THEN 0 ELSE 1 END FETCH FIRST 1 ROW ONLY";
        try(PreparedStatement s=target.prepareStatement(sql)){
            s.setString(1,requested);s.setString(2,requested);
            try(ResultSet r=s.executeQuery()){
                if(r.next())return r.getString(1);
            }
        }
        throw new IllegalArgumentException("PostgreSQL schema does not exist: "+requested);
    }

    private static String resolveSqlServerSchema(Connection target,String requested)throws SQLException{
        String sql="SELECT TOP (1) name FROM sys.schemas WHERE LOWER(name)=LOWER(?) " +
                "ORDER BY CASE WHEN name=? THEN 0 ELSE 1 END";
        try(PreparedStatement s=target.prepareStatement(sql)){
            s.setString(1,requested);s.setString(2,requested);
            try(ResultSet r=s.executeQuery()){if(r.next())return r.getString(1);}
        }
        throw new IllegalArgumentException("SQL Server schema does not exist: "+requested);
    }

    private static void storeResolvedSchema(Connection repository,long dataSourceId,String schema)throws SQLException{
        try(PreparedStatement s=repository.prepareStatement("UPDATE connector SET schema_name=? WHERE connector_id=(SELECT connector_id FROM data_source WHERE data_source_id=?)")){
            s.setString(1,schema);s.setLong(2,dataSourceId);s.executeUpdate();
        }
        try(PreparedStatement s=repository.prepareStatement("DELETE FROM catalog_schema cs WHERE cs.data_source_id=? AND cs.schema_name<>? AND NOT EXISTS(SELECT 1 FROM catalog_table ct WHERE ct.catalog_schema_id=cs.catalog_schema_id)")){
            s.setLong(1,dataSourceId);s.setString(2,schema);s.executeUpdate();
        }
    }
    private static String normalize(String type){String t=type.toUpperCase(Locale.ROOT);if(t.contains("CHAR")||t.contains("VARCHAR")||t.equals("TEXT"))return "STRING";if(t.equals("NUMBER")||t.contains("FLOAT")||t.contains("DECIMAL")||t.contains("NUMERIC")||t.contains("INT"))return "DECIMAL";if(t.equals("DATE"))return "DATE";if(t.contains("TIMESTAMP"))return "TIMESTAMP";if(t.contains("CLOB")||t.contains("BLOB"))return "LOB";if(t.contains("RAW")||t.contains("BINARY")||t.equals("BYTEA"))return "BINARY";if(t.contains("JSON"))return "JSON";if(t.contains("XML"))return "XML";return "OTHER";}

    private static String cleanOptionalValue(String value){if(value==null)return "";String cleaned=value.trim();return "null".equalsIgnoreCase(cleaned)?"":cleaned;}

    private static String targetJdbcUrl(String technology,String host,int port,String service,String database){return switch(technology){case "ORACLE"->"jdbc:oracle:thin:@//"+host+":"+port+"/"+(service.isBlank()?database:service);case "POSTGRESQL"->"jdbc:postgresql://"+host+":"+port+"/"+(database.isBlank()?service:database);case "SQLSERVER"->"jdbc:sqlserver://"+host+":"+port+";databaseName="+(database.isBlank()?service:database)+";encrypt=true;trustServerCertificate=true;loginTimeout=15";default->throw new IllegalArgumentException("Unsupported target technology: "+technology);};}

    @Override
    public void updateConnection(long dataSourceId,ConnectionUpdateCommand command){
        if(command==null)throw new IllegalArgumentException("Connection definition is required");
        long environmentId=command.environmentId();
        String name=cleanOptionalValue(command.name());
        String technology=cleanOptionalValue(command.technology()).toUpperCase(Locale.ROOT);
        String schema=cleanOptionalValue(command.schemaName());
        String host=cleanOptionalValue(command.host());
        int port=command.port();
        String service=cleanOptionalValue(command.service());
        String database=cleanOptionalValue(command.database());
        String user=cleanOptionalValue(command.username());
        String provider=cleanOptionalValue(command.secretProvider()).toUpperCase(Locale.ROOT);
        String reference=cleanOptionalValue(command.secretReference());
        char[] submittedPassword=command.password();
        if("ORACLE".equals(technology))schema=schema.toUpperCase(Locale.ROOT);
        if(environmentId<=0)throw new IllegalArgumentException("Environment is required");
        if(name.isBlank())throw new IllegalArgumentException("Connection name is required");
        if(name.length()>100)throw new IllegalArgumentException("Connection name cannot exceed 100 characters");
        if(!List.of("ORACLE","POSTGRESQL","SQLSERVER").contains(technology))throw new IllegalArgumentException("Only Oracle, PostgreSQL and SQL Server technologies are supported");
        if(schema.isBlank())throw new IllegalArgumentException("Schema name is required");
        if(host.isBlank())throw new IllegalArgumentException("Server is required");
        if(port<1||port>65535)throw new IllegalArgumentException("Port must be between 1 and 65535");
        if(user.isBlank())throw new IllegalArgumentException("Username is required");
        if(service.isBlank()&&database.isBlank())throw new IllegalArgumentException("Service name or database name is required");
        if(("POSTGRESQL".equals(technology)||"SQLSERVER".equals(technology))&&database.isBlank())throw new IllegalArgumentException("Database name is required for "+("POSTGRESQL".equals(technology)?"PostgreSQL":"SQL Server"));
        if(!reference.isBlank()&&provider.isBlank())throw new IllegalArgumentException("Secret provider is required when a secret reference is specified");
        if(reference.isBlank()&&!provider.isBlank()&&!"ENCRYPTED_LOCAL".equals(provider))throw new IllegalArgumentException("Secret reference key is required when an external secret provider is selected");
        if(!provider.isBlank()&&!List.of("ORACLE_WALLET","VAULT","K8S_SECRET","CLOUD_SECRET","ENCRYPTED_LOCAL").contains(provider))throw new IllegalArgumentException("Invalid secret provider");
        if("ENCRYPTED_LOCAL".equals(provider)&&reference.isBlank())reference="CONNECTION_"+name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+","_");

        try(Connection repository=open(false)){
            char[] effectivePassword=new char[0];
            try{
                long connectorId;
                String encrypted;
                try(PreparedStatement statement=repository.prepareStatement(
                        "SELECT c.connector_id,c.encrypted_password FROM data_source d JOIN connector c ON c.connector_id=d.connector_id WHERE d.data_source_id=?")){
                    statement.setLong(1,dataSourceId);
                    try(ResultSet row=statement.executeQuery()){
                        if(!row.next())throw new IllegalArgumentException("Connection not found");
                        connectorId=row.getLong(1);encrypted=row.getString(2);
                    }
                }
                if(submittedPassword.length==0&&(encrypted==null||encrypted.isBlank()))throw new IllegalArgumentException("A database password is required because this connection has no saved password");
                effectivePassword=submittedPassword.length>0?submittedPassword.clone():decrypt(encrypted);
                String targetUrl=targetJdbcUrl(technology,host,port,service,database);
                try(Connection target=DriverManager.getConnection(targetUrl,user,new String(effectivePassword))){
                    if(!target.isValid(10))throw new SQLException("Target connection validation failed");
                    if("POSTGRESQL".equals(technology))schema=resolvePostgresqlSchema(target,schema);
                    else if("SQLSERVER".equals(technology))schema=resolveSqlServerSchema(target,schema);
                }

                Long secretId=null;
                if(!reference.isBlank()){
                    try(PreparedStatement statement=repository.prepareStatement(
                            "MERGE INTO secret_reference t USING(SELECT ? provider_type,? reference_key FROM dual) x ON(t.provider_type=x.provider_type AND t.reference_key=x.reference_key) WHEN NOT MATCHED THEN INSERT(provider_type,reference_key,description) VALUES(x.provider_type,x.reference_key,'Updated from Connections')")){
                        statement.setString(1,provider);statement.setString(2,reference);statement.executeUpdate();
                    }
                    try(PreparedStatement statement=repository.prepareStatement(
                            "SELECT secret_reference_id FROM secret_reference WHERE provider_type=? AND reference_key=?")){
                        statement.setString(1,provider);statement.setString(2,reference);
                        try(ResultSet row=statement.executeQuery()){if(!row.next())throw new IllegalStateException("Secret reference was not saved");secretId=row.getLong(1);}
                    }
                }
                try(PreparedStatement statement=repository.prepareStatement(
                        "UPDATE connector SET name=?,technology_code=?,host_name=?,port=?,database_name=?,service_name=?,username_value=?,secret_reference_id=?,schema_name=?,encrypted_password=? WHERE connector_id=?")){
                    statement.setString(1,name);statement.setString(2,technology);statement.setString(3,host);statement.setInt(4,port);
                    statement.setString(5,database.isBlank()?null:database);statement.setString(6,service.isBlank()?null:service);statement.setString(7,user);
                    if(secretId==null)statement.setNull(8,java.sql.Types.NUMERIC);else statement.setLong(8,secretId);
                    statement.setString(9,schema);statement.setString(10,submittedPassword.length>0?encrypt(submittedPassword):encrypted);statement.setLong(11,connectorId);
                    if(statement.executeUpdate()!=1)throw new IllegalArgumentException("Connection not found");
                }
                try(PreparedStatement statement=repository.prepareStatement(
                        "UPDATE data_source SET environment_id=?,name=?,description=? WHERE data_source_id=?")){
                    statement.setLong(1,environmentId);statement.setString(2,name);statement.setString(3,"Schema "+schema+" · Updated from Connections");statement.setLong(4,dataSourceId);
                    if(statement.executeUpdate()!=1)throw new IllegalArgumentException("Connection not found");
                }
                repository.commit();
            }catch(SQLException|RuntimeException error){repository.rollback();throw error;}
            finally{Arrays.fill(effectivePassword,'\0');Arrays.fill(submittedPassword,'\0');}
        }catch(SQLException error){
            Arrays.fill(submittedPassword,'\0');
            if(error.getErrorCode()==1)throw new IllegalArgumentException("Connection name already exists");
            throw failure("update connection",error);
        }
    }

    @Override public DiscoveryScan scan(long dataSourceId) {
        String connectionSql="SELECT c.technology_code,c.host_name,c.port,c.service_name,c.database_name,c.username_value,c.schema_name,c.encrypted_password FROM data_source d JOIN connector c ON c.connector_id=d.connector_id WHERE d.data_source_id=?";
        try(Connection repository=open(false)){try{String technology,host,service,database,user,schema,encrypted;int port;try(PreparedStatement s=repository.prepareStatement(connectionSql)){s.setLong(1,dataSourceId);try(ResultSet r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Connection not found");technology=r.getString(1);host=r.getString(2);port=r.getInt(3);service=r.getString(4);database=r.getString(5);user=r.getString(6);schema=r.getString(7);encrypted=r.getString(8);}}if(schema==null||schema.isBlank()||encrypted==null||encrypted.isBlank())throw new IllegalStateException("Connection credentials must be configured once from Connections");char[] targetPassword=decrypt(encrypted);try{String targetUrl=targetJdbcUrl(technology,host,port,service==null?"":service,database==null?"":database);try(Connection target=DriverManager.getConnection(targetUrl,user,new String(targetPassword))){String resolvedSchema=schema;if("POSTGRESQL".equals(technology))resolvedSchema=resolvePostgresqlSchema(target,schema);else if("SQLSERVER".equals(technology))resolvedSchema=resolveSqlServerSchema(target,schema);if(!resolvedSchema.equals(schema)){schema=resolvedSchema;storeResolvedSchema(repository,dataSourceId,schema);}synchronizeCatalog(repository,target,dataSourceId,schema,technology);}}finally{Arrays.fill(targetPassword,'\0');}repository.commit();}catch(SQLException|RuntimeException e){repository.rollback();throw e;}}catch(SQLException e){throw failure("scan target schema",e);}
        String source="SELECT name FROM data_source WHERE data_source_id=?";
        String columns="""
            SELECT cc.catalog_column_id,cs.schema_name,ct.table_name,cc.column_name,cc.native_data_type
              FROM catalog_schema cs JOIN catalog_table ct ON ct.catalog_schema_id=cs.catalog_schema_id
              JOIN catalog_column cc ON cc.catalog_table_id=ct.catalog_table_id
             WHERE cs.data_source_id=? AND ct.enabled='Y' ORDER BY cs.schema_name,ct.table_name,cc.ordinal_position
            """;
        try(Connection c=open(true)){
            String name; try(PreparedStatement s=c.prepareStatement(source)){s.setLong(1,dataSourceId);try(ResultSet r=s.executeQuery()){
                if(!r.next())throw new IllegalArgumentException("Connection not found");name=r.getString(1);}}
            boolean exists=rulesetExists(c,dataSourceId); List<MaskingAlgorithmOption> algorithms=algorithms(c);
            List<DiscoveryFinding> findings=new ArrayList<>();
            try(PreparedStatement s=c.prepareStatement(columns)){s.setLong(1,dataSourceId);try(ResultSet r=s.executeQuery()){
                while(r.next()){Match m=classify(r.getString(4),r.getString(5));if(m!=null) findings.add(new DiscoveryFinding(
                        r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),m.classification,
                        m.confidence,suggest(algorithms,m.hints)));}}}
            return new DiscoveryScan(dataSourceId,name,exists,algorithms,findings,List.of(),DiscoveryDraftWriter.context(c,dataSourceId));
        }catch(SQLException e){throw failure("scan catalog",e);}
    }

    @Override public DiscoveryScan analyzeContent(long dataSourceId) {
        DiscoveryScan metadata=scan(dataSourceId);
        try(Connection repository=open(true);PreparedStatement query=repository.prepareStatement(
                "SELECT c.technology_code,c.host_name,c.port,c.service_name,c.database_name,c.username_value,c.encrypted_password FROM data_source d JOIN connector c ON c.connector_id=d.connector_id WHERE d.data_source_id=?")) {
            query.setLong(1,dataSourceId);
            try(ResultSet row=query.executeQuery()) {
                if(!row.next())throw new IllegalArgumentException("Connection not found");
                String technology=row.getString(1);
                var details=new OracleConnectionDetails(technology,row.getString(2),row.getInt(3),row.getString(4),row.getString(5),row.getString(6));
                char[] targetPassword=decrypt(row.getString(7));
                try(Connection target=DriverManager.getConnection(details.jdbcUrl(),details.username(),new String(targetPassword))) {
                    target.setAutoCommit(false);target.setReadOnly(true);
                    DiscoveryScan result=DiscoveryContentScan.analyze(repository,target,dataSourceId,technology,metadata);
                    target.rollback();return result;
                } finally {Arrays.fill(targetPassword,'\0');}
            }
        }catch(SQLException error){throw failure("analyze Discovery text samples",error);}
    }

    @Override public DiscoveryCreationResult createDraftVersion(long rulesetId,List<DiscoverySelection> selections) {
        try(Connection repository=open(false)) {
            try {
                DiscoveryCreationResult result=DiscoveryDraftWriter.create(repository,rulesetId,selections,username);
                repository.commit();return result;
            }catch(SQLException|RuntimeException error){repository.rollback();throw error;}
        }catch(SQLException error){throw failure("create Discovery draft version",error);}
    }

    @Override public DiscoveryCreationResult createRuleset(long dataSourceId,String code,String name,List<DiscoverySelection> selections){
        if(selections.isEmpty())throw new IllegalArgumentException("Select at least one sensitive column");return createRulesetInternal(dataSourceId,code,name,selections);}
    private DiscoveryCreationResult createRulesetInternal(long dataSourceId,String code,String name,List<DiscoverySelection> selections){
        try(Connection c=open(false)){try{
            try(PreparedStatement lock=c.prepareStatement("SELECT data_source_id FROM data_source WHERE data_source_id=? FOR UPDATE")){
                lock.setLong(1,dataSourceId);try(ResultSet r=lock.executeQuery()){if(!r.next())throw new IllegalArgumentException("Connection not found");}}
            if(rulesetExists(c,dataSourceId))throw new IllegalStateException("A ruleset already exists for this connection");
            try(PreparedStatement s=c.prepareStatement("INSERT INTO ruleset(data_source_id,code,name,description,owner_name) VALUES(?,?,?,?,?)")){
                s.setLong(1,dataSourceId);s.setString(2,code);s.setString(3,name);s.setString(4,"Created by Discovery");s.setString(5,username);s.executeUpdate();}
            long rulesetId;try(PreparedStatement s=c.prepareStatement("SELECT ruleset_id FROM ruleset WHERE data_source_id=?")){s.setLong(1,dataSourceId);try(ResultSet r=s.executeQuery()){r.next();rulesetId=r.getLong(1);}}
            try(PreparedStatement s=c.prepareStatement("INSERT INTO ruleset_version(ruleset_id,version_number,status,description,created_by) VALUES(?, 1, 'DRAFT', 'Generated by Discovery', ?)")){
                s.setLong(1,rulesetId);s.setString(2,username);s.executeUpdate();}
            long versionId;try(PreparedStatement s=c.prepareStatement("SELECT ruleset_version_id FROM ruleset_version WHERE ruleset_id=? AND version_number=1")){s.setLong(1,rulesetId);try(ResultSet r=s.executeQuery()){r.next();versionId=r.getLong(1);}}
            int order=0;for(DiscoverySelection selection:selections){long algorithmId=algorithmId(c,selection.algorithmCode());requireColumn(c,dataSourceId,selection.columnId());
                try(PreparedStatement s=c.prepareStatement("INSERT INTO masking_rule(ruleset_version_id,catalog_column_id,algorithm_version_id,execution_order,enabled) VALUES(?,?,?,?, 'Y')")){
                    s.setLong(1,versionId);s.setLong(2,selection.columnId());s.setLong(3,algorithmId);s.setInt(4,order+=10);s.executeUpdate();}}
            c.commit();return new DiscoveryCreationResult(rulesetId,versionId,code,selections.size());
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}}
        catch(SQLException e){throw failure("create discovery ruleset",e);}
    }

    @Override public DiscoveryCreationResult createEmptyRuleset(long dataSourceId,String code,String name){return createRulesetInternal(dataSourceId,code,name,List.of());}

    private static boolean rulesetExists(Connection c,long id)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM ruleset WHERE data_source_id=?")){s.setLong(1,id);try(ResultSet r=s.executeQuery()){r.next();return r.getInt(1)>0;}}}
    private static void requireColumn(Connection c,long ds,long column)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM catalog_column cc JOIN catalog_table ct ON ct.catalog_table_id=cc.catalog_table_id JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id WHERE cc.catalog_column_id=? AND cs.data_source_id=?")){s.setLong(1,column);s.setLong(2,ds);try(ResultSet r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Column does not belong to the selected connection");}}}
    private static long algorithmId(Connection c,String code)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT av.algorithm_version_id FROM algorithm a JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id WHERE a.code=? AND a.status='ACTIVE' AND av.status='PUBLISHED' ORDER BY av.version_number DESC FETCH FIRST 1 ROW ONLY")){s.setString(1,code);try(ResultSet r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Published algorithm not found: "+code);return r.getLong(1);}}}
    private static List<MaskingAlgorithmOption> algorithms(Connection c)throws SQLException{String q="SELECT a.code,a.name,a.category FROM algorithm a JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id WHERE a.status='ACTIVE' AND av.status='PUBLISHED' ORDER BY a.code";Map<String,MaskingAlgorithmOption> m=new LinkedHashMap<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery(q)){while(r.next())m.putIfAbsent(r.getString(1),new MaskingAlgorithmOption(r.getString(1),r.getString(2),r.getString(3),"PUBLISHED",List.of()));}return List.copyOf(m.values());}
    static Match classify(String column,String type){
        String n=java.text.Normalizer.normalize(column,java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+","").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+","_");
        if(n.matches(".*(PASSWORD|PASSWD|SECRET|TOKEN|API_KEY|MOT_DE_PASSE|MDP|CLE_API|JETON|KENNWORT|PASSWORT|GEHEIMNIS|API_SCHLUSSEL).*"))
            return new Match("SECRET",99,List.of("REDACT","NULL"));
        if(n.matches(".*(EMAIL|E_MAIL|MAIL_ADDRESS|ADRESSE_MAIL|COURRIEL|MAILADRESSE|EMAIL_ADRESSE).*"))
            return new Match("EMAIL",97,List.of("EMAIL","HASH","REDACT"));
        if(n.matches(".*(PHONE|MOBILE|TELEPHONE|TELEPHONE_PORTABLE|NUMERO_TELEPHONE|TEL|FAX|TELEFON|TELEFONNUMMER|HANDY|MOBILNUMMER).*"))
            return new Match("PHONE",94,List.of("PHONE","HASH","REDACT"));
        if(n.matches(".*(IBAN|BIC|ACCOUNT_NUMBER|CARD_NUMBER|CREDIT_CARD|NUMERO_COMPTE|COMPTE_BANCAIRE|NUMERO_CARTE|CARTE_BANCAIRE|KONTONUMMER|BANKKONTO|KARTENNUMMER|KREDITKARTE).*"))
            return new Match("FINANCIAL",96,List.of("IBAN","CARD","HASH","REDACT"));
        if(n.matches(".*(COMPANY|COMPANY_NAME|EMPLOYER|EMPLOYER_NAME|CORPORATION|CORP_NAME|BUSINESS_NAME|ORGANIZATION_NAME|ORGANISATION_NAME|SOCIETE|ENTREPRISE|RAISON_SOCIALE|FIRMA|UNTERNEHMEN).*"))
            return new Match("ORGANIZATION_NAME",96,List.of("LOOKUP_COMPANY"));
        if(n.matches("(^|.*_)(DEPT|DEPARTMENT|BUSINESS_UNIT|ORG_UNIT|ORGANIZATION_UNIT|DIVISION|SERVICE|DEPARTEMENT|DIRECTION|ABTEILUNG|BEREICH)($|_NAME$|_LABEL$|_LIBELLE$|_DESCRIPTION$)"))
            return new Match("ORGANIZATION_DEPARTMENT",96,List.of("LOOKUP_DEPARTMENT"));
        if(n.matches(".*(FIRST_NAME|GIVEN_NAME|FORENAME|PRENOM|VORNAME).*"))
            return new Match("PERSON_FIRST_NAME",97,List.of("LOOKUP_FIRST_NAME"));
        if(n.matches(".*(FULL_NAME|CUSTOMER_NAME|CLIENT_NAME|NOM_COMPLET|NOM_CLIENT|VOLLSTANDIGER_NAME|KUNDENNAME).*"))
            return new Match("PERSON_FULL_NAME",91,List.of("CONSISTENT_LOOKUP","HASH"));
        if(n.matches(".*(LAST_NAME|SURNAME|FAMILY_NAME|NOM_FAMILLE|NACHNAME|FAMILIENNAME).*")
                || n.matches("(^|.*_)(NOM|NAME)($|_.*)"))
            return new Match("PERSON_LAST_NAME",96,List.of("LOOKUP_LAST_NAME"));
        if(n.matches(".*(CITY|TOWN|MUNICIPALITY|VILLE|COMMUNE|STADT|WOHNORT).*"))
            return new Match("CITY",95,List.of("LOOKUP_CITY"));
        if(n.matches(".*(ADDRESS|STREET|POSTAL_ADDRESS|ADDRESS_LINE|ADRESSE|RUE|ANSCHRIFT|STRASSE).*"))
            return new Match("POSTAL_ADDRESS",94,List.of("LOOKUP_ADDRESS"));
        if(n.matches(".*(POSTAL_CODE|ZIP_CODE|ZIP|CODE_POSTAL|POSTLEITZAHL|PLZ).*"))
            return new Match("POSTAL_CODE",88,List.of("MASK_STRING","HASH"));
        if(n.matches(".*(BIRTH|DOB|DATE_OF_BIRTH|DATE_NAISSANCE|NAISSANCE|GEBURT|GEBURTSDATUM).*"))
            return new Match("BIRTH_DATE",95,List.of("DATE","SHIFT","GENERAL"));
        if(n.matches(".*(SSN|NATIONAL_ID|PASSPORT|IDENTITY|TAX_ID|NUMERO_SECURITE_SOCIALE|NUM_SECU|IDENTITE|CARTE_IDENTITE|NUMERO_FISCAL|SOZIALVERSICHERUNGSNUMMER|PERSONALAUSWEIS|AUSWEISNUMMER|STEUER_ID|STEUERNUMMER).*"))
            return new Match("IDENTIFIER",97,List.of("HASH","TOKEN","REDACT"));
        return null;
    }
    private static String suggest(List<MaskingAlgorithmOption> algorithms,List<String> hints){for(String h:hints)for(MaskingAlgorithmOption a:algorithms)if((a.code()+" "+a.name()+" "+a.category()).toUpperCase(Locale.ROOT).contains(h))return a.code();return algorithms.isEmpty()?"":algorithms.get(0).code();}
    private Connection open(boolean readOnly)throws SQLException{Connection c=DriverManager.getConnection(jdbcUrl,username,new String(password));c.setReadOnly(readOnly);c.setAutoCommit(false);return c;}
    private void ensureCredentialColumns(){try(Connection c=open(false);Statement s=c.createStatement()){for(String ddl:List.of("ALTER TABLE connector ADD (schema_name VARCHAR2(128))","ALTER TABLE connector ADD (encrypted_password VARCHAR2(4000))")){try{s.executeUpdate(ddl);}catch(SQLException e){if(e.getErrorCode()!=1430)throw e;}}c.commit();}catch(SQLException e){throw failure("install connector credential columns",e);}}
    private void ensureCatalogRescanSupport(){try(Connection c=open(false);Statement s=c.createStatement()){try{s.executeUpdate("ALTER TABLE catalog_column DROP CONSTRAINT catalog_column_position_uk");}catch(SQLException e){if(e.getErrorCode()!=2443)throw e;}try{s.executeUpdate("CREATE INDEX catalog_column_position_ix ON catalog_column(catalog_table_id,ordinal_position)");}catch(SQLException e){if(e.getErrorCode()!=955)throw e;}c.commit();}catch(SQLException e){throw failure("install catalog rescan support",e);}}

    private void ensurePostgresqlAlgorithmImplementations(){
        try(Connection c=open(false)){ensurePostgresqlAlgorithmImplementations(c);c.commit();}catch(SQLException e){throw failure("publish PostgreSQL algorithm implementations",e);}
    }

    static void ensurePostgresqlAlgorithmImplementations(Connection c)throws SQLException{
        ensureAdvancedAlgorithms(c);
        ensureDepartmentLookup(c);
        ensureEnrichedLookupCatalogs(c);
        String insert="""
                INSERT INTO algorithm_implementation
                    (algorithm_version_id,technology_code,implementation_type,
                     implementation_code,code_checksum,status,published_at)
                SELECT av.algorithm_version_id,'POSTGRESQL',?,?,?,'PUBLISHED',SYSTIMESTAMP
                  FROM algorithm a JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id
                 WHERE a.code=? AND av.status='PUBLISHED'
                   AND NOT EXISTS(SELECT 1 FROM algorithm_implementation ai
                                   WHERE ai.algorithm_version_id=av.algorithm_version_id
                                     AND ai.technology_code='POSTGRESQL')
                """;
        try(PreparedStatement enabled=c.prepareStatement("UPDATE database_technology SET enabled='Y',adapter_key='postgresql' WHERE technology_code='POSTGRESQL'")){enabled.executeUpdate();}
        try(PreparedStatement s=c.prepareStatement(insert)){
            for(PostgresqlTemplate template:postgresqlTemplates()){
                s.setString(1,template.type());s.setString(2,template.sql());s.setString(3,sha256(template.sql()));s.setString(4,template.code());s.addBatch();
            }
            s.executeBatch();
        }
    }

    private void ensureSqlServerAlgorithmImplementations(){
        try(Connection c=open(false)){ensureSqlServerAlgorithmImplementations(c);c.commit();}catch(SQLException e){throw failure("publish SQL Server algorithm implementations",e);}
    }

    static void ensureSqlServerAlgorithmImplementations(Connection c)throws SQLException{
        ensureAdvancedAlgorithms(c);
        ensureDepartmentLookup(c);
        ensureEnrichedLookupCatalogs(c);
        String insert="""
                INSERT INTO algorithm_implementation
                    (algorithm_version_id,technology_code,implementation_type,
                     implementation_code,code_checksum,status,published_at)
                SELECT av.algorithm_version_id,'SQLSERVER',?,?,?,'PUBLISHED',SYSTIMESTAMP
                  FROM algorithm a JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id
                 WHERE a.code=? AND av.status='PUBLISHED'
                   AND NOT EXISTS(SELECT 1 FROM algorithm_implementation ai
                                   WHERE ai.algorithm_version_id=av.algorithm_version_id
                                     AND ai.technology_code='SQLSERVER')
                """;
        try(PreparedStatement enabled=c.prepareStatement("UPDATE database_technology SET enabled='Y',adapter_key='sqlserver' WHERE technology_code='SQLSERVER'")){enabled.executeUpdate();}
        try(PreparedStatement s=c.prepareStatement(insert)){
            for(SqlServerTemplate template:sqlServerTemplates()){
                s.setString(1,template.type());s.setString(2,template.sql());s.setString(3,sha256(template.sql()));s.setString(4,template.code());s.addBatch();
            }
            s.executeBatch();
        }
    }

    private static void ensureAdvancedAlgorithms(Connection c)throws SQLException{
        String insertAlgorithm="""
                INSERT INTO algorithm(code,name,category,description,deterministic,reversible,
                    preserve_null,preserve_format,preserve_length,preserve_uniqueness,status)
                SELECT ?,?,?,?,?,?,?,?,?,?,'ACTIVE' FROM dual
                 WHERE NOT EXISTS(SELECT 1 FROM algorithm WHERE code=?)
                """;
        String insertVersion="""
                INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at)
                SELECT ?,1,'PUBLISHED','PRIVONYM_AUTO',SYSTIMESTAMP FROM dual
                 WHERE NOT EXISTS(SELECT 1 FROM algorithm_version WHERE algorithm_id=? AND version_number=1)
                """;
        for(AdvancedAlgorithm algorithm:advancedAlgorithms()){
            try(PreparedStatement s=c.prepareStatement(insertAlgorithm)){
                int i=1;s.setString(i++,algorithm.code());s.setString(i++,algorithm.name());s.setString(i++,algorithm.category());s.setString(i++,algorithm.description());
                s.setString(i++,algorithm.deterministic()?"Y":"N");s.setString(i++,"N");s.setString(i++,"Y");s.setString(i++,algorithm.preserveFormat()?"Y":"N");s.setString(i++,"N");s.setString(i++,"N");s.setString(i,algorithm.code());s.executeUpdate();
            }
            long algorithmId;try(PreparedStatement s=c.prepareStatement("SELECT algorithm_id FROM algorithm WHERE code=?")){s.setString(1,algorithm.code());try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Algorithm was not created: "+algorithm.code());algorithmId=r.getLong(1);}}
            try(PreparedStatement s=c.prepareStatement(insertVersion)){s.setLong(1,algorithmId);s.setLong(2,algorithmId);s.executeUpdate();}
            long versionId;try(PreparedStatement s=c.prepareStatement("SELECT algorithm_version_id FROM algorithm_version WHERE algorithm_id=? AND version_number=1")){s.setLong(1,algorithmId);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Algorithm version was not created: "+algorithm.code());versionId=r.getLong(1);}}
            for(AdvancedParameter parameter:algorithm.parameters())ensureAdvancedParameter(c,versionId,parameter);
            for(AdvancedImplementation implementation:algorithm.implementations())ensureAdvancedImplementation(c,versionId,implementation);
            if("CONSISTENT_LOOKUP".equals(algorithm.code()))ensureNeutralLookupDataset(c,versionId);
            if("PERSISTENT_MAPPING".equals(algorithm.code()))ensurePersistentMappingDataset(c,versionId);
            if("GEOGRAPHIC_COHERENT_ADDRESS".equals(algorithm.code()))ensureGeographicDataset(c,versionId);
        }
    }

    private static void ensureAdvancedParameter(Connection c,long versionId,AdvancedParameter parameter)throws SQLException{
        String sql="""
                INSERT INTO algorithm_parameter_def(algorithm_version_id,parameter_name,data_type,
                    required,default_value,validation_expression,description)
                SELECT ?,?,?,?,?,?,? FROM dual
                 WHERE NOT EXISTS(SELECT 1 FROM algorithm_parameter_def
                                   WHERE algorithm_version_id=? AND parameter_name=?)
                """;
        try(PreparedStatement s=c.prepareStatement(sql)){s.setLong(1,versionId);s.setString(2,parameter.name());s.setString(3,parameter.type());s.setString(4,parameter.required()?"Y":"N");s.setString(5,parameter.defaultValue());s.setString(6,parameter.validation());s.setString(7,parameter.description());s.setLong(8,versionId);s.setString(9,parameter.name());s.executeUpdate();}
    }

    private static void ensureAdvancedImplementation(Connection c,long versionId,AdvancedImplementation implementation)throws SQLException{
        String sql="""
                INSERT INTO algorithm_implementation(algorithm_version_id,technology_code,
                    implementation_type,implementation_code,code_checksum,status,published_at)
                SELECT ?,?,?,?,?, 'PUBLISHED',SYSTIMESTAMP FROM dual
                 WHERE NOT EXISTS(SELECT 1 FROM algorithm_implementation
                                   WHERE algorithm_version_id=? AND technology_code=?)
                """;
        try(PreparedStatement s=c.prepareStatement(sql)){s.setLong(1,versionId);s.setString(2,implementation.technology());s.setString(3,implementation.type());s.setString(4,implementation.sql());s.setString(5,sha256(implementation.sql()));s.setLong(6,versionId);s.setString(7,implementation.technology());s.executeUpdate();}
    }

    private static void ensureNeutralLookupDataset(Connection c,long versionId)throws SQLException{
        String code="CONSISTENT_NEUTRAL_VALUES";
        try(PreparedStatement s=c.prepareStatement("INSERT INTO lookup_dataset(code,name,category,version_number,status,published_at) SELECT ?,'Consistent neutral values','GENERIC',1,'PUBLISHED',SYSTIMESTAMP FROM dual WHERE NOT EXISTS(SELECT 1 FROM lookup_dataset WHERE code=? AND version_number=1)")){s.setString(1,code);s.setString(2,code);s.executeUpdate();}
        long datasetId;try(PreparedStatement s=c.prepareStatement("SELECT lookup_dataset_id FROM lookup_dataset WHERE code=? AND version_number=1")){s.setString(1,code);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Consistent lookup dataset was not created");datasetId=r.getLong(1);}}
        try(PreparedStatement s=c.prepareStatement("INSERT INTO lookup_dataset_value(lookup_dataset_id,value_text,weight_value,enabled) SELECT ?,?,1,'Y' FROM dual WHERE NOT EXISTS(SELECT 1 FROM lookup_dataset_value WHERE lookup_dataset_id=? AND value_text=?)")){for(int i=1;i<=20;i++){String value=String.format(Locale.ROOT,"ANON_%03d",i);s.setLong(1,datasetId);s.setString(2,value);s.setLong(3,datasetId);s.setString(4,value);s.addBatch();}s.executeBatch();}
        try(PreparedStatement s=c.prepareStatement("INSERT INTO algorithm_lookup_config(algorithm_version_id,lookup_dataset_id) SELECT ?,? FROM dual WHERE NOT EXISTS(SELECT 1 FROM algorithm_lookup_config WHERE algorithm_version_id=?)")){s.setLong(1,versionId);s.setLong(2,datasetId);s.setLong(3,versionId);s.executeUpdate();}
    }

    private static void ensurePersistentMappingDataset(Connection c,long versionId)throws SQLException{
        String code="PERSISTENT_MAPPING_VALUES";
        try(PreparedStatement s=c.prepareStatement("INSERT INTO lookup_dataset(code,name,category,version_number,status,published_at) SELECT ?,'Persistent neutral tokens','GENERIC',1,'PUBLISHED',SYSTIMESTAMP FROM dual WHERE NOT EXISTS(SELECT 1 FROM lookup_dataset WHERE code=? AND version_number=1)")){s.setString(1,code);s.setString(2,code);s.executeUpdate();}
        long datasetId=datasetId(c,code);List<String> values=new ArrayList<>();for(int i=1;i<=100;i++)values.add(String.format(Locale.ROOT,"TOKEN_%06d",i));ensureDatasetValues(c,code,values);
        try(PreparedStatement s=c.prepareStatement("INSERT INTO algorithm_lookup_config(algorithm_version_id,lookup_dataset_id) SELECT ?,? FROM dual WHERE NOT EXISTS(SELECT 1 FROM algorithm_lookup_config WHERE algorithm_version_id=?)")){s.setLong(1,versionId);s.setLong(2,datasetId);s.setLong(3,versionId);s.executeUpdate();}
    }

    private static void ensureGeographicDataset(Connection c,long versionId)throws SQLException{
        String code="GEOGRAPHIC_US_ADDRESS_VALUES";
        try(PreparedStatement s=c.prepareStatement("INSERT INTO lookup_dataset(code,name,category,version_number,status,published_at) SELECT ?,'Coherent US postal addresses','ADDRESS',1,'PUBLISHED',SYSTIMESTAMP FROM dual WHERE NOT EXISTS(SELECT 1 FROM lookup_dataset WHERE code=? AND version_number=1)")){s.setString(1,code);s.setString(2,code);s.executeUpdate();}
        ensureDatasetValues(c,code,List.of("101 Main Street, New York, NY 10001","225 Market Street, San Francisco, CA 94105","310 Congress Avenue, Austin, TX 78701","455 Pine Street, Seattle, WA 98101","820 Lakeview Drive, Chicago, IL 60601","1420 Brickell Avenue, Miami, FL 33131","75 State Street, Boston, MA 02109","600 Peachtree Street, Atlanta, GA 30308","1700 Lincoln Street, Denver, CO 80203","900 SW Fifth Avenue, Portland, OR 97204","1200 McKinney Street, Houston, TX 77010","400 South Hope Street, Los Angeles, CA 90071","200 North Central Avenue, Phoenix, AZ 85004","1601 Market Street, Philadelphia, PA 19103","100 North Tryon Street, Charlotte, NC 28202","500 Woodward Avenue, Detroit, MI 48226","401 Union Street, Nashville, TN 37219","801 Grand Avenue, Des Moines, IA 50309","250 East Wisconsin Avenue, Milwaukee, WI 53202","1 East Washington Street, Indianapolis, IN 46204"));
        long datasetId=datasetId(c,code);try(PreparedStatement s=c.prepareStatement("INSERT INTO algorithm_lookup_config(algorithm_version_id,lookup_dataset_id) SELECT ?,? FROM dual WHERE NOT EXISTS(SELECT 1 FROM algorithm_lookup_config WHERE algorithm_version_id=?)")){s.setLong(1,versionId);s.setLong(2,datasetId);s.setLong(3,versionId);s.executeUpdate();}
    }

    private static void ensureDepartmentLookup(Connection c)throws SQLException{
        String code="LOOKUP_DEPARTMENT";
        try(PreparedStatement s=c.prepareStatement("""
                INSERT INTO algorithm(code,name,category,description,deterministic,reversible,
                    preserve_null,preserve_format,preserve_length,preserve_uniqueness,status)
                SELECT ?,'Lookup · Departments','SUBSTITUTION',
                    'Replaces organization department names with published neutral department values.',
                    'Y','N','Y','N','N','N','ACTIVE' FROM dual
                 WHERE NOT EXISTS(SELECT 1 FROM algorithm WHERE code=?)
                """)){s.setString(1,code);s.setString(2,code);s.executeUpdate();}
        long algorithmId;try(PreparedStatement s=c.prepareStatement("SELECT algorithm_id FROM algorithm WHERE code=?")){s.setString(1,code);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Department lookup algorithm was not created");algorithmId=r.getLong(1);}}
        try(PreparedStatement s=c.prepareStatement("INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at) SELECT ?,1,'PUBLISHED','PRIVONYM_AUTO',SYSTIMESTAMP FROM dual WHERE NOT EXISTS(SELECT 1 FROM algorithm_version WHERE algorithm_id=? AND version_number=1)")){s.setLong(1,algorithmId);s.setLong(2,algorithmId);s.executeUpdate();}
        long versionId;try(PreparedStatement s=c.prepareStatement("SELECT algorithm_version_id FROM algorithm_version WHERE algorithm_id=? AND version_number=1")){s.setLong(1,algorithmId);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Department lookup version was not created");versionId=r.getLong(1);}}
        ensureAdvancedImplementation(c,versionId,new AdvancedImplementation("ORACLE","LOOKUP_MAPPING","LOOKUP_DATASET"));
        String datasetCode="LOOKUP_DEPARTMENT_VALUES";
        try(PreparedStatement s=c.prepareStatement("INSERT INTO lookup_dataset(code,name,category,version_number,status,published_at) SELECT ?,'Department values','ORGANIZATION',1,'PUBLISHED',SYSTIMESTAMP FROM dual WHERE NOT EXISTS(SELECT 1 FROM lookup_dataset WHERE code=? AND version_number=1)")){s.setString(1,datasetCode);s.setString(2,datasetCode);s.executeUpdate();}
        long datasetId;try(PreparedStatement s=c.prepareStatement("SELECT lookup_dataset_id FROM lookup_dataset WHERE code=? AND version_number=1")){s.setString(1,datasetCode);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Department lookup dataset was not created");datasetId=r.getLong(1);}}
        List<String> values=List.of("FINANCE","INFORMATION TECHNOLOGY","RESEARCH AND DEVELOPMENT","HUMAN RESOURCES","OPERATIONS","RISK MANAGEMENT","COMPLIANCE","LEGAL","MARKETING","SALES","CUSTOMER SERVICE","INTERNAL AUDIT","PROCUREMENT");
        try(PreparedStatement s=c.prepareStatement("INSERT INTO lookup_dataset_value(lookup_dataset_id,value_text,weight_value,enabled) SELECT ?,?,1,'Y' FROM dual WHERE NOT EXISTS(SELECT 1 FROM lookup_dataset_value WHERE lookup_dataset_id=? AND value_text=?)")){for(String value:values){s.setLong(1,datasetId);s.setString(2,value);s.setLong(3,datasetId);s.setString(4,value);s.addBatch();}s.executeBatch();}
        try(PreparedStatement s=c.prepareStatement("INSERT INTO algorithm_lookup_config(algorithm_version_id,lookup_dataset_id) SELECT ?,? FROM dual WHERE NOT EXISTS(SELECT 1 FROM algorithm_lookup_config WHERE algorithm_version_id=?)")){s.setLong(1,versionId);s.setLong(2,datasetId);s.setLong(3,versionId);s.executeUpdate();}
    }

    private static void ensureEnrichedLookupCatalogs(Connection c)throws SQLException{
        ensureDatasetValues(c,"LOOKUP_LAST_NAME_VALUES",words("""
                SMITH JOHNSON WILLIAMS BROWN JONES GARCIA MILLER DAVIS RODRIGUEZ MARTINEZ
                HERNANDEZ LOPEZ GONZALEZ WILSON ANDERSON THOMAS TAYLOR MOORE JACKSON MARTIN
                LEE PEREZ THOMPSON WHITE HARRIS SANCHEZ CLARK RAMIREZ LEWIS ROBINSON WALKER
                YOUNG ALLEN KING WRIGHT SCOTT TORRES NGUYEN HILL FLORES GREEN ADAMS NELSON
                BAKER HALL RIVERA CAMPBELL MITCHELL CARTER ROBERTS GOMEZ PHILLIPS EVANS TURNER
                DIAZ PARKER CRUZ EDWARDS COLLINS REYES STEWART MORRIS MORALES MURPHY COOK
                ROGERS GUTIERREZ ORTIZ MORGAN COOPER PETERSON BAILEY REED KELLY HOWARD RAMOS
                KIM COX WARD RICHARDSON WATSON BROOKS CHAVEZ WOOD JAMES BENNETT GRAY MENDOZA
                RUIZ HUGHES PRICE ALVAREZ CASTILLO SANDERS PATEL MYERS LONG ROSS FOSTER JIMENEZ
                POWELL JENKINS PERRY RUSSELL SULLIVAN BELL COLEMAN BUTLER HENDERSON BARNES
                GONZALES FISHER VASQUEZ SIMMONS ROMERO JORDAN PATTERSON ALEXANDER HAMILTON
                GRAHAM REYNOLDS GRIFFIN WALLACE MORENO WEST COLE HAYES BRYANT HERRERA GIBSON
                ELLIS TRAN MEDINA AGUILAR STEVENS MURRAY FORD CASTRO MARSHALL OWENS HARRISON
                FERNANDEZ MCDONALD WOODS WASHINGTON KENNEDY WELLS VARGAS HENRY CHEN FREEMAN
                WEBB TUCKER GUZMAN BURNS CRAWFORD OLSON SIMPSON PORTER HUNTER GORDON MENDEZ
                SILVA SHAW SNYDER MASON DIXON MUNOZ HUNT HICKS HOLMES PALMER WAGNER BLACK
                ROBERTSON BOYD ROSE STONE SALAZAR FOX WARREN MILLS MEYER RICE SCHMIDT GARZA
                DANIELS FERGUSON NICHOLS STEPHENS SOTO WEAVER RYAN GARDNER PAYNE GRANT DUNN
                KELLEY SPENCER HAWKINS ARNOLD PIERCE VAZQUEZ HANSEN PETERS SANTOS HART BRADLEY
                KNIGHT ELLIOTT CUNNINGHAM DUNCAN ARMSTRONG HUDSON CARROLL LANE RILEY ANDREWS
                """));
        ensureDatasetValues(c,"LOOKUP_FIRST_NAME_VALUES",words("""
                JAMES JOHN ROBERT MICHAEL WILLIAM DAVID RICHARD JOSEPH THOMAS CHARLES
                CHRISTOPHER DANIEL MATTHEW ANTHONY MARK DONALD STEVEN PAUL ANDREW JOSHUA
                KENNETH KEVIN BRIAN GEORGE TIMOTHY RONALD EDWARD JASON JEFFREY RYAN JACOB
                GARY NICHOLAS ERIC JONATHAN STEPHEN LARRY JUSTIN SCOTT BRANDON BENJAMIN SAMUEL
                GREGORY ALEXANDER PATRICK FRANK RAYMOND JACK DENNIS JERRY TYLER AARON JOSE
                ADAM NATHAN HENRY DOUGLAS ZACHARY PETER KYLE WALTER ETHAN JEREMY HAROLD KEITH
                CHRISTIAN ROGER NOAH GERALD CARL TERRY SEAN AUSTIN ARTHUR LAWRENCE JESSE DYLAN
                BRYAN JOE JORDAN BILLY BRUCE ALBERT WILLIE GABRIEL LOGAN ALAN JUAN WAYNE ROY
                RALPH RANDY EUGENE VINCENT RUSSELL ELIJAH LOUIS BOBBY PHILIP JOHNNY MARY
                PATRICIA JENNIFER LINDA ELIZABETH BARBARA SUSAN JESSICA SARAH KAREN LISA NANCY
                BETTY MARGARET SANDRA ASHLEY KIMBERLY EMILY DONNA MICHELLE CAROL AMANDA DOROTHY
                MELISSA DEBORAH STEPHANIE REBECCA SHARON LAURA CYNTHIA KATHLEEN AMY SHIRLEY
                ANGELA HELEN ANNA BRENDA PAMELA NICOLE EMMA SAMANTHA KATHERINE CHRISTINE DEBRA
                RACHEL CAROLYN JANET CATHERINE MARIA HEATHER DIANE RUTH JULIE OLIVIA JOYCE
                VIRGINIA VICTORIA KELLY LAUREN CHRISTINA JOAN EVELYN JUDITH MEGAN ANDREA CHERYL
                HANNAH JACQUELINE MARTHA GLORIA TERESA ANN SARA MADISON FRANCES KATHRYN JANICE
                JEAN ABIGAIL ALICE JUDY SOPHIA GRACE DENISE AMBER DORIS MARILYN DANIELLE
                BEVERLY ISABELLA THERESA DIANA NATALIE BRITTANY CHARLOTTE MARIE KAYLA ALEXIS
                LORI VIVIAN BROOKE TIFFANY VIOLET CLAIRE ELLA LUCY ELEANOR MAYA ARIA CHLOE
                LILY ZOE STELLA AVERY SCARLETT PENELOPE LAYLA NORA RILEY AUBREY SAVANNAH
                """));
        disableDatasetValues(c,"LOOKUP_CITY_VALUES",List.of("PARIS","LYON","GENEVE","LAUSANNE","ZURICH"));
        ensureDatasetValues(c,"LOOKUP_CITY_VALUES",phrases("""
                New York|Los Angeles|Chicago|Houston|Phoenix|Philadelphia|San Antonio|San Diego|Dallas|Jacksonville
                Austin|Fort Worth|San Jose|Columbus|Charlotte|Indianapolis|Seattle|Denver|Washington|Nashville
                Oklahoma City|El Paso|Boston|Portland|Las Vegas|Detroit|Memphis|Louisville|Baltimore|Milwaukee
                Albuquerque|Tucson|Fresno|Sacramento|Mesa|Kansas City|Atlanta|Omaha|Colorado Springs|Raleigh
                Long Beach|Virginia Beach|Miami|Oakland|Minneapolis|Tulsa|Bakersfield|Wichita|Arlington|Aurora
                Tampa|New Orleans|Cleveland|Honolulu|Anaheim|Lexington|Stockton|Corpus Christi|Henderson|Riverside
                Newark|Saint Paul|Santa Ana|Cincinnati|Irvine|Orlando|Pittsburgh|St Louis|Greensboro|Jersey City
                Anchorage|Lincoln|Plano|Durham|Buffalo|Chandler|Chula Vista|Toledo|Madison|Gilbert
                Reno|Fort Wayne|North Las Vegas|St Petersburg|Lubbock|Irving|Laredo|Winston Salem|Chesapeake|Glendale
                Garland|Scottsdale|Norfolk|Boise|Fremont|Spokane|Santa Clarita|Baton Rouge|Richmond|Hialeah
                San Bernardino|Tacoma|Modesto|Huntsville|Des Moines|Yonkers|Rochester|Moreno Valley|Fayetteville|Fontana
                Columbus Georgia|Worcester|Port St Lucie|Little Rock|Augusta|Oxnard|Birmingham|Montgomery|Frisco|Amarillo
                Salt Lake City|Grand Rapids|Huntington Beach|Overland Park|Glendale Arizona|Tallahassee|Grand Prairie|McKinney|Cape Coral|Sioux Falls
                Peoria|Providence|Vancouver|Knoxville|Akron|Shreveport|Mobile|Brownsville|Newport News|Fort Lauderdale
                Chattanooga|Tempe|Oceanside|Garden Grove|Rancho Cucamonga|Santa Rosa|Ontario|Elk Grove|Salem|Pembroke Pines
                """));
        disableDatasetValues(c,"LOOKUP_ADDRESS_VALUES",List.of("1 RUE DES LILAS","10 AVENUE CENTRALE","25 RUE DU LAC","8 CHEMIN DES FLEURS","42 RUE DE LA GARE"));
        List<String> addresses=new ArrayList<>();
        List<String> streets=phrases("""
                Main Street|Oak Street|Maple Avenue|Cedar Street|Pine Street|Elm Street|Washington Avenue|Lakeview Drive|Park Avenue|Sunset Boulevard
                Highland Avenue|River Road|Hillcrest Drive|Lincoln Street|Jefferson Avenue|Madison Street|Jackson Avenue|Adams Street|Monroe Avenue|Franklin Street
                Church Street|Market Street|Broadway|Center Street|Walnut Street|Chestnut Street|Spruce Street|Willow Lane|Meadow Lane|Forest Drive
                Ridge Road|Valley View Drive|Pleasant Street|Spring Street|Mill Road|School Street|Depot Street|Garden Avenue|Cherry Lane|Magnolia Drive
                Dogwood Lane|Sycamore Street|Hickory Road|Aspen Court|Birch Lane|Cypress Avenue|Laurel Drive|Cambridge Road|Oxford Street|Liberty Avenue
                """);
        int[] numbers={101,125,148,203,227,254,309,336,412,445,518,572,604,689,725,808,864,917,1042,1185};
        for(int number:numbers)for(String street:streets)addresses.add(number+" "+street);
        ensureDatasetValues(c,"LOOKUP_ADDRESS_VALUES",addresses);
        ensureCompanyLookup(c,phrases("""
                Mapple Systems|Microhard Software|Tespi Beverages|Amazin Commerce|Googol Search
                Metaframe Networks|Netflick Studios|Nividia Graphics|Intellix Computing|Orackle Data
                Ciscora Networks|Adobee Creative|Salesforge Cloud|Paymate Payments|Masterlink Financial
                Vistra Payments|Americard Group|JPM Harbor Bank|Goldman Sacks Capital|Morgen Stanley Partners
                Wellstone Fargo|Citiwide Bank|Bank of Meridian|United Healthworks|Johnson and Jenson
                Procter and Gambleton|Coca Kola Foods|Starbrix Coffee|McDonnells Kitchen|Burger Crown
                Walmarket Retail|Home Harbor Depot|Targeton Stores|Costmore Wholesale|Nikey Sports
                Addidasia Apparel|Teslaire Motors|Fordham Automotive|General Motrix|Chevrolux Motors
                Boing Aerospace|Lockridge Martin|Northstar Grumman|Raytheonic Systems|Caterpillar Ridge
                Deerefield Equipment|Exxon Vale Energy|Chevronix Energy|Shellbrook Energy|Conoco Summit
                Verizone Telecom|ATN Communications|T Mobilis|Comcaston Media|Charterline Communications
                IBMax Technologies|Hewlett Packardson|Dellmont Systems|Lenovix Devices|Accenturex Consulting
                Deloitton Advisory|Price Water Cooper|Ernst and Younger|KPM Group|Capgemina Consulting
                Uberlane Mobility|Lyfted Transport|Airbed and Beyond|Booking Harbor|Expedion Travel
                Spotifly Audio|Twittora Social|Snaplink Media|Pinterestia Labs|Redditon Communities
                Dropboxer Storage|Zoomera Video|Slackline Workspaces|Atlassia Software|Shopifair Commerce
                Pfizeron Labs|Modernix Biotech|Merckland Health|Abbview Pharma|Bristol Meyer Health
                Northwind Harbor|Blue Mesa Industries|Silver Oak Holdings|Redwood Dynamics|Clearwater Enterprises
                """));
    }

    private static void ensureCompanyLookup(Connection c,List<String> values)throws SQLException{
        String code="LOOKUP_COMPANY";
        try(PreparedStatement s=c.prepareStatement("""
                INSERT INTO algorithm(code,name,category,description,deterministic,reversible,
                    preserve_null,preserve_format,preserve_length,preserve_uniqueness,status)
                SELECT ?,'Lookup · Fictional companies','SUBSTITUTION',
                    'Replaces organization names with deliberately altered fictional company names.',
                    'Y','N','Y','N','N','N','ACTIVE' FROM dual
                 WHERE NOT EXISTS(SELECT 1 FROM algorithm WHERE code=?)
                """)){s.setString(1,code);s.setString(2,code);s.executeUpdate();}
        long algorithmId;try(PreparedStatement s=c.prepareStatement("SELECT algorithm_id FROM algorithm WHERE code=?")){s.setString(1,code);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Company lookup algorithm was not created");algorithmId=r.getLong(1);}}
        try(PreparedStatement s=c.prepareStatement("INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at) SELECT ?,1,'PUBLISHED','PRIVONYM_AUTO',SYSTIMESTAMP FROM dual WHERE NOT EXISTS(SELECT 1 FROM algorithm_version WHERE algorithm_id=? AND version_number=1)")){s.setLong(1,algorithmId);s.setLong(2,algorithmId);s.executeUpdate();}
        long versionId;try(PreparedStatement s=c.prepareStatement("SELECT algorithm_version_id FROM algorithm_version WHERE algorithm_id=? AND version_number=1")){s.setLong(1,algorithmId);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Company lookup version was not created");versionId=r.getLong(1);}}
        ensureAdvancedImplementation(c,versionId,new AdvancedImplementation("ORACLE","LOOKUP_MAPPING","LOOKUP_DATASET"));
        String datasetCode="LOOKUP_COMPANY_VALUES";
        try(PreparedStatement s=c.prepareStatement("INSERT INTO lookup_dataset(code,name,category,version_number,status,published_at) SELECT ?,'Fictional company values','ORGANIZATION',1,'PUBLISHED',SYSTIMESTAMP FROM dual WHERE NOT EXISTS(SELECT 1 FROM lookup_dataset WHERE code=? AND version_number=1)")){s.setString(1,datasetCode);s.setString(2,datasetCode);s.executeUpdate();}
        ensureDatasetValues(c,datasetCode,values);
        long datasetId=datasetId(c,datasetCode);
        try(PreparedStatement s=c.prepareStatement("MERGE INTO algorithm_lookup_config t USING(SELECT ? algorithm_version_id,? lookup_dataset_id FROM dual) x ON(t.algorithm_version_id=x.algorithm_version_id) WHEN MATCHED THEN UPDATE SET t.lookup_dataset_id=x.lookup_dataset_id WHEN NOT MATCHED THEN INSERT(algorithm_version_id,lookup_dataset_id) VALUES(x.algorithm_version_id,x.lookup_dataset_id)")){s.setLong(1,versionId);s.setLong(2,datasetId);s.executeUpdate();}
    }

    private static void ensureDatasetValues(Connection c,String datasetCode,List<String> values)throws SQLException{
        long datasetId=datasetId(c,datasetCode);
        String sql="MERGE INTO lookup_dataset_value t USING(SELECT ? lookup_dataset_id,? value_text FROM dual) x ON(t.lookup_dataset_id=x.lookup_dataset_id AND UPPER(t.value_text)=UPPER(x.value_text)) WHEN MATCHED THEN UPDATE SET t.enabled='Y' WHEN NOT MATCHED THEN INSERT(lookup_dataset_id,value_text,weight_value,enabled) VALUES(x.lookup_dataset_id,x.value_text,1,'Y')";
        try(PreparedStatement s=c.prepareStatement(sql)){for(String value:values){s.setLong(1,datasetId);s.setString(2,value);s.addBatch();}s.executeBatch();}
    }

    private static void disableDatasetValues(Connection c,String datasetCode,List<String> values)throws SQLException{
        long datasetId=datasetId(c,datasetCode);
        try(PreparedStatement s=c.prepareStatement("UPDATE lookup_dataset_value SET enabled='N' WHERE lookup_dataset_id=? AND UPPER(value_text)=UPPER(?)")){for(String value:values){s.setLong(1,datasetId);s.setString(2,value);s.addBatch();}s.executeBatch();}
    }

    private static long datasetId(Connection c,String code)throws SQLException{
        try(PreparedStatement s=c.prepareStatement("SELECT lookup_dataset_id FROM lookup_dataset WHERE code=? AND version_number=1")){s.setString(1,code);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Lookup dataset was not found: "+code);return r.getLong(1);}}
    }

    private static List<String> words(String text){return List.of(text.strip().split("\\s+"));}
    private static List<String> phrases(String text){return java.util.Arrays.stream(text.strip().split("\\s*\\|\\s*|\\R+")).map(String::strip).filter(v->!v.isBlank()).toList();}

    private static List<AdvancedAlgorithm> advancedAlgorithms(){return List.of(
            new AdvancedAlgorithm("IBAN_GENERATOR","Deterministic checksum-valid IBAN generator","SYNTHETIC","Generates a deterministic IBAN with a recalculated MOD-97 checksum while preserving country and compact length.",true,false,
                    List.of(new AdvancedParameter("key_material","STRING",true,null,"non-empty secret up to 512 characters","Secret used to derive the replacement BBAN.")),
                    List.of(new AdvancedImplementation("ORACLE","DATABASE_FUNCTION","CASE WHEN ${column} IS NULL THEN NULL ELSE PRIVONYM_IBAN_GENERATE(TO_CHAR(${column}),${param.key_material}) END"),new AdvancedImplementation("POSTGRESQL","DATABASE_FUNCTION","CASE WHEN ${column} IS NULL THEN NULL ELSE privonym_iban_generate(CAST(${column} AS TEXT),${param.key_material}) END"),new AdvancedImplementation("SQLSERVER","DATABASE_FUNCTION","CASE WHEN ${column} IS NULL THEN NULL ELSE dbo.PRIVONYM_IBAN_GENERATE(CONVERT(nvarchar(128),${column}),${param.key_material}) END"))),
            new AdvancedAlgorithm("PERSISTENT_MAPPING","Stable persistent mapping token","TOKENIZATION","Maps identical values to a stable published neutral token set across Rule Sets and Jobs.",true,false,List.of(),
                    List.of(new AdvancedImplementation("ORACLE","LOOKUP_MAPPING","LOOKUP_DATASET"),new AdvancedImplementation("POSTGRESQL","LOOKUP_MAPPING","LOOKUP_DATASET"),new AdvancedImplementation("SQLSERVER","LOOKUP_MAPPING","LOOKUP_DATASET"))),
            new AdvancedAlgorithm("JSON_PATH_MASK","JSON path value replacement","MASKING","Replaces one configured JSON path with a fixed text value while preserving the remaining document.",true,true,
                    List.of(new AdvancedParameter("json_path","STRING",true,null,"JSON path up to 500 characters","Path such as $.customer.name."),new AdvancedParameter("replacement_value","STRING",false,"MASKED","string up to 4000 characters","Text written at the selected JSON path.")),
                    List.of(new AdvancedImplementation("ORACLE","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE JSON_TRANSFORM(${column},SET ${param.json_path}=${param.replacement_value}) END"),new AdvancedImplementation("POSTGRESQL","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE JSONB_SET(CAST(${column} AS JSONB),STRING_TO_ARRAY(REGEXP_REPLACE(${param.json_path},'^\\$\\.?',''),'.'),TO_JSONB(CAST(${param.replacement_value} AS TEXT)),true) END"),new AdvancedImplementation("SQLSERVER","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE JSON_MODIFY(${column},${param.json_path},${param.replacement_value}) END"))),
            new AdvancedAlgorithm("GEOGRAPHIC_COHERENT_ADDRESS","Coherent US postal address","SYNTHETIC","Replaces a complete address with a coherent US street, city, state and ZIP tuple.",true,true,List.of(),
                    List.of(new AdvancedImplementation("ORACLE","LOOKUP_MAPPING","LOOKUP_DATASET"),new AdvancedImplementation("POSTGRESQL","LOOKUP_MAPPING","LOOKUP_DATASET"),new AdvancedImplementation("SQLSERVER","LOOKUP_MAPPING","LOOKUP_DATASET"))),
            new AdvancedAlgorithm("RELATIONSHIP_AWARE_TOKEN","Relationship-aware deterministic token","TOKENIZATION","Produces the same namespaced token for identical parent and child values across tables.",true,false,
                    List.of(new AdvancedParameter("key_material","STRING",true,null,"non-empty secret up to 512 characters","Secret key shared by every rule in the relationship domain."),new AdvancedParameter("token_namespace","STRING",false,"GLOBAL","non-empty namespace up to 128 characters","Domain name separating unrelated identifiers that contain the same source value.")),
                    List.of(
                            new AdvancedImplementation("ORACLE","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE LOWER(RAWTOHEX(DBMS_CRYPTO.MAC(UTL_I18N.STRING_TO_RAW(${param.token_namespace}||':'||TO_CHAR(${column}),'AL32UTF8'),DBMS_CRYPTO.HMAC_SH256,UTL_I18N.STRING_TO_RAW(${param.key_material},'AL32UTF8')))) END"),
                            new AdvancedImplementation("POSTGRESQL","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE ENCODE(HMAC(CONVERT_TO(${param.token_namespace}||':'||CAST(${column} AS TEXT),'UTF8'),CONVERT_TO(${param.key_material},'UTF8'),'sha256'),'hex') END"),
                            new AdvancedImplementation("SQLSERVER","DATABASE_FUNCTION","CASE WHEN ${column} IS NULL THEN NULL ELSE dbo.PRIVONYM_HMAC_SHA256(CONCAT(${param.token_namespace},N':',CONVERT(nvarchar(max),${column})),${param.key_material}) END"))),
            new AdvancedAlgorithm("HMAC_SHA256","Keyed HMAC-SHA256 pseudonym","HASHING","Produces a deterministic keyed SHA-256 message authentication code.",true,false,
                    List.of(new AdvancedParameter("key_material","STRING",true,null,"non-empty secret up to 512 characters","Key material. Use a dedicated restricted repository account until external secret references are supported.")),
                    List.of(
                            new AdvancedImplementation("ORACLE","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE LOWER(RAWTOHEX(DBMS_CRYPTO.MAC(UTL_I18N.STRING_TO_RAW(TO_CHAR(${column}),'AL32UTF8'),DBMS_CRYPTO.HMAC_SH256,UTL_I18N.STRING_TO_RAW(${param.key_material},'AL32UTF8')))) END"),
                            new AdvancedImplementation("POSTGRESQL","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE ENCODE(HMAC(CONVERT_TO(CAST(${column} AS TEXT),'UTF8'),CONVERT_TO(${param.key_material},'UTF8'),'sha256'),'hex') END"),
                            new AdvancedImplementation("SQLSERVER","DATABASE_FUNCTION","CASE WHEN ${column} IS NULL THEN NULL ELSE dbo.PRIVONYM_HMAC_SHA256(CONVERT(nvarchar(max),${column}),${param.key_material}) END"))),
            new AdvancedAlgorithm("CONSISTENT_LOOKUP","Consistent lookup substitution","SUBSTITUTION","Maps identical input values to identical neutral lookup values.",true,false,List.of(),
                    List.of(new AdvancedImplementation("ORACLE","LOOKUP_MAPPING","LOOKUP_DATASET"),new AdvancedImplementation("POSTGRESQL","LOOKUP_MAPPING","LOOKUP_DATASET"),new AdvancedImplementation("SQLSERVER","LOOKUP_MAPPING","LOOKUP_DATASET"))),
            new AdvancedAlgorithm("DATE_SHIFT_DETERMINISTIC","Deterministic date shift","GENERALIZATION","Shifts every date by a configured fixed number of days while preserving NULL.",true,true,
                    List.of(new AdvancedParameter("shift_days","INTEGER",false,"30","integer between -36500 and 36500","Fixed number of days added to the source date.")),
                    List.of(new AdvancedImplementation("ORACLE","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE ${column}+${param.shift_days} END"),new AdvancedImplementation("POSTGRESQL","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE ${column}+(${param.shift_days}*INTERVAL '1 day') END"),new AdvancedImplementation("SQLSERVER","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE DATEADD(day,${param.shift_days},${column}) END"))),
            new AdvancedAlgorithm("REGEX_MASK","Regular-expression mask","MASKING","Replaces every substring matching a configured regular expression.",true,true,
                    List.of(new AdvancedParameter("pattern","STRING",true,null,"non-empty expression up to 500 characters","Regular expression evaluated by the target database."),new AdvancedParameter("replacement","STRING",false,"X","string up to 100 characters","Replacement text.")),
                    List.of(new AdvancedImplementation("ORACLE","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE REGEXP_REPLACE(TO_CHAR(${column}),${param.pattern},${param.replacement}) END"),new AdvancedImplementation("POSTGRESQL","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE REGEXP_REPLACE(CAST(${column} AS TEXT),${param.pattern},${param.replacement},'g') END"),new AdvancedImplementation("SQLSERVER","DATABASE_FUNCTION","CASE WHEN ${column} IS NULL THEN NULL ELSE dbo.PRIVONYM_REGEX_REPLACE(CONVERT(nvarchar(max),${column}),${param.pattern},${param.replacement}) END"))),
            new AdvancedAlgorithm("CONDITIONAL_MASK","Conditional string replacement","MASKING","Replaces a text value only when it equals a configured comparison value.",true,true,
                    List.of(new AdvancedParameter("condition_value","STRING",true,null,"string up to 1000 characters","Value that activates masking."),new AdvancedParameter("replacement_value","STRING",false,"MASKED","string up to 1000 characters","Replacement returned when the condition matches.")),
                    List.of(new AdvancedImplementation("ORACLE","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL WHEN ${column}=${param.condition_value} THEN ${param.replacement_value} ELSE ${column} END"),new AdvancedImplementation("POSTGRESQL","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL WHEN ${column}=${param.condition_value} THEN ${param.replacement_value} ELSE ${column} END"),new AdvancedImplementation("SQLSERVER","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL WHEN ${column}=${param.condition_value} THEN ${param.replacement_value} ELSE ${column} END"))));}

    private static List<PostgresqlTemplate> postgresqlTemplates(){return List.of(
            new PostgresqlTemplate("NULLIFY","SQL_EXPRESSION","NULL"),
            new PostgresqlTemplate("ZERO_NUMBER","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE 0 END"),
            new PostgresqlTemplate("MASK_STRING","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE ${param.replacement_value} END"),
            new PostgresqlTemplate("MASK_PAYMENT_CARD","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL WHEN ${param.visible_suffix_length}=0 THEN REGEXP_REPLACE(CAST(${column} AS TEXT), '[[:digit:]]', '*', 'g') ELSE REGEXP_REPLACE(LEFT(CAST(${column} AS TEXT),GREATEST(LENGTH(CAST(${column} AS TEXT))-${param.visible_suffix_length},0)),'[[:digit:]]','*','g')||RIGHT(CAST(${column} AS TEXT),LEAST(${param.visible_suffix_length},LENGTH(CAST(${column} AS TEXT)))) END"),
            new PostgresqlTemplate("GENERALIZE_DATE_YEAR","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE MAKE_DATE(EXTRACT(YEAR FROM ${column})::INT,${param.month_number},${param.day_number}) END"),
            new PostgresqlTemplate("GENERALIZE_DATE_MONTH","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE (DATE_TRUNC('month',${column})+(${param.day_number}-1)*INTERVAL '1 day')::DATE END"),
            new PostgresqlTemplate("HASH_SHA256","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE ENCODE(SHA256(CONVERT_TO(CAST(${column} AS TEXT),'UTF8')),'hex') END"),
            new PostgresqlTemplate("RANDOM_LOWER_STRING","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE LOWER(SUBSTRING(REPEAT(MD5(RANDOM()::TEXT),CEIL(LENGTH(CAST(${column} AS TEXT))/32.0)::INT),1,LENGTH(CAST(${column} AS TEXT)))) END"),
            new PostgresqlTemplate("RANDOM_UPPER_STRING","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE UPPER(SUBSTRING(REPEAT(MD5(RANDOM()::TEXT),CEIL(LENGTH(CAST(${column} AS TEXT))/32.0)::INT),1,LENGTH(CAST(${column} AS TEXT)))) END"),
            new PostgresqlTemplate("RANDOM_EMAIL_LOCAL_PART","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE SUBSTRING(MD5(RANDOM()::TEXT),1,${param.local_part_length})||CASE WHEN STRPOS(CAST(${column} AS TEXT),'@')>1 THEN SUBSTRING(CAST(${column} AS TEXT) FROM STRPOS(CAST(${column} AS TEXT),'@')) ELSE '@invalid.example' END END"),
            new PostgresqlTemplate("RANDOM_PHONE_FR","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE ${param.prefix}||LPAD(FLOOR(RANDOM()*100000000)::BIGINT::TEXT,8,'0') END"),
            new PostgresqlTemplate("PERTURB_NUMBER_PERCENT","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE ROUND((${column}*(1+(RANDOM()*2-1)*${param.max_ratio}))::NUMERIC,${param.scale}) END"),
            new PostgresqlTemplate("TRUNCATE_TABLE","PLSQL_BLOCK","TRUNCATE TABLE ${qualified_table}"),
            new PostgresqlTemplate("DELETE_TABLE_ROWS","PLSQL_BLOCK","DELETE FROM ${qualified_table}"),
            new PostgresqlTemplate("LOOKUP_FROM_DATASET","LOOKUP_MAPPING","LOOKUP_DATASET"),
            new PostgresqlTemplate("LOOKUP_LAST_NAME","LOOKUP_MAPPING","LOOKUP_DATASET"),
            new PostgresqlTemplate("LOOKUP_FIRST_NAME","LOOKUP_MAPPING","LOOKUP_DATASET"),
            new PostgresqlTemplate("LOOKUP_CITY","LOOKUP_MAPPING","LOOKUP_DATASET"),
            new PostgresqlTemplate("LOOKUP_ADDRESS","LOOKUP_MAPPING","LOOKUP_DATASET"),
            new PostgresqlTemplate("LOOKUP_DEPARTMENT","LOOKUP_MAPPING","LOOKUP_DATASET"),
            new PostgresqlTemplate("LOOKUP_COMPANY","LOOKUP_MAPPING","LOOKUP_DATASET"));}

    private static List<SqlServerTemplate> sqlServerTemplates(){return List.of(
            new SqlServerTemplate("NULLIFY","SQL_EXPRESSION","NULL"),
            new SqlServerTemplate("ZERO_NUMBER","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE 0 END"),
            new SqlServerTemplate("MASK_STRING","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE ${param.replacement_value} END"),
            new SqlServerTemplate("MASK_PAYMENT_CARD","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL WHEN ${param.visible_suffix_length}=0 THEN REPLICATE('*',LEN(CONVERT(nvarchar(max),${column}))) ELSE REPLICATE('*',CASE WHEN LEN(CONVERT(nvarchar(max),${column}))>${param.visible_suffix_length} THEN LEN(CONVERT(nvarchar(max),${column}))-${param.visible_suffix_length} ELSE 0 END)+RIGHT(CONVERT(nvarchar(max),${column}),${param.visible_suffix_length}) END"),
            new SqlServerTemplate("GENERALIZE_DATE_YEAR","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE DATEFROMPARTS(YEAR(${column}),${param.month_number},${param.day_number}) END"),
            new SqlServerTemplate("GENERALIZE_DATE_MONTH","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE DATEFROMPARTS(YEAR(${column}),MONTH(${column}),${param.day_number}) END"),
            new SqlServerTemplate("HASH_SHA256","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE LOWER(CONVERT(varchar(64),HASHBYTES('SHA2_256',CONVERT(nvarchar(max),${column})),2)) END"),
            new SqlServerTemplate("RANDOM_LOWER_STRING","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE LOWER(LEFT(REPLICATE(REPLACE(CONVERT(varchar(36),NEWID()),'-',''),CEILING(LEN(CONVERT(nvarchar(max),${column}))/32.0)),LEN(CONVERT(nvarchar(max),${column})))) END"),
            new SqlServerTemplate("RANDOM_UPPER_STRING","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE UPPER(LEFT(REPLICATE(REPLACE(CONVERT(varchar(36),NEWID()),'-',''),CEILING(LEN(CONVERT(nvarchar(max),${column}))/32.0)),LEN(CONVERT(nvarchar(max),${column})))) END"),
            new SqlServerTemplate("RANDOM_EMAIL_LOCAL_PART","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE LEFT(REPLACE(CONVERT(varchar(36),NEWID()),'-',''),${param.local_part_length})+CASE WHEN CHARINDEX('@',CONVERT(nvarchar(max),${column}))>1 THEN SUBSTRING(CONVERT(nvarchar(max),${column}),CHARINDEX('@',CONVERT(nvarchar(max),${column})),LEN(CONVERT(nvarchar(max),${column}))) ELSE '@invalid.example' END END"),
            new SqlServerTemplate("RANDOM_PHONE_FR","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE ${param.prefix}+RIGHT('00000000'+CONVERT(varchar(8),ABS(CONVERT(bigint,CHECKSUM(NEWID())))%100000000),8) END"),
            new SqlServerTemplate("PERTURB_NUMBER_PERCENT","SQL_EXPRESSION","CASE WHEN ${column} IS NULL THEN NULL ELSE ROUND(${column}*(1+((RAND(CHECKSUM(NEWID()))*2-1)*${param.max_ratio})),${param.scale}) END"),
            new SqlServerTemplate("TRUNCATE_TABLE","PLSQL_BLOCK","TRUNCATE TABLE ${qualified_table}"),
            new SqlServerTemplate("DELETE_TABLE_ROWS","PLSQL_BLOCK","DELETE FROM ${qualified_table}"),
            new SqlServerTemplate("LOOKUP_FROM_DATASET","LOOKUP_MAPPING","LOOKUP_DATASET"),
            new SqlServerTemplate("LOOKUP_LAST_NAME","LOOKUP_MAPPING","LOOKUP_DATASET"),
            new SqlServerTemplate("LOOKUP_FIRST_NAME","LOOKUP_MAPPING","LOOKUP_DATASET"),
            new SqlServerTemplate("LOOKUP_CITY","LOOKUP_MAPPING","LOOKUP_DATASET"),
            new SqlServerTemplate("LOOKUP_ADDRESS","LOOKUP_MAPPING","LOOKUP_DATASET"),
            new SqlServerTemplate("LOOKUP_DEPARTMENT","LOOKUP_MAPPING","LOOKUP_DATASET"),
            new SqlServerTemplate("LOOKUP_COMPANY","LOOKUP_MAPPING","LOOKUP_DATASET"));}

    private static String sha256(String value){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));return java.util.HexFormat.of().formatHex(digest);}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private record PostgresqlTemplate(String code,String type,String sql){}
    private record SqlServerTemplate(String code,String type,String sql){}
    private record AdvancedAlgorithm(String code,String name,String category,String description,
            boolean deterministic,boolean preserveFormat,List<AdvancedParameter> parameters,
            List<AdvancedImplementation> implementations){}
    private record AdvancedParameter(String name,String type,boolean required,String defaultValue,
            String validation,String description){}
    private record AdvancedImplementation(String technology,String type,String sql){}
    private static byte[] loadOrCreateLocalSecretKey(){try{Path path=Path.of(System.getProperty("privonym.secret.key.file","config/privonym-secret.key")).toAbsolutePath();if(Files.exists(path)){byte[] key=Base64.getDecoder().decode(Files.readString(path).trim());if(key.length!=32)throw new IllegalStateException("Invalid Privonym local secret key");return key;}Files.createDirectories(path.getParent());byte[] key=new byte[32];new SecureRandom().nextBytes(key);Files.writeString(path,Base64.getEncoder().encodeToString(key),StandardOpenOption.CREATE_NEW);return key;}catch(Exception e){throw new IllegalStateException("Unable to initialize encrypted local credential store: "+e.getMessage(),e);}}
    private String encrypt(char[] clear){try{byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(localSecretKey,"AES"),new GCMParameterSpec(128,nonce));byte[] plain=new String(clear).getBytes(java.nio.charset.StandardCharsets.UTF_8),encrypted=cipher.doFinal(plain),payload=new byte[nonce.length+encrypted.length];System.arraycopy(nonce,0,payload,0,nonce.length);System.arraycopy(encrypted,0,payload,nonce.length,encrypted.length);Arrays.fill(plain,(byte)0);return Base64.getEncoder().encodeToString(payload);}catch(Exception e){throw new IllegalStateException("Unable to encrypt connection password",e);}}
    private char[] decrypt(String encoded){try{byte[] payload=Base64.getDecoder().decode(encoded),nonce=Arrays.copyOfRange(payload,0,12);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(localSecretKey,"AES"),new GCMParameterSpec(128,nonce));byte[] plain=cipher.doFinal(payload,12,payload.length-12);char[] result=new String(plain,java.nio.charset.StandardCharsets.UTF_8).toCharArray();Arrays.fill(plain,(byte)0);return result;}catch(Exception e){throw new IllegalStateException("Unable to decrypt connection password",e);}}
    private static IllegalStateException failure(String action,SQLException e){return new IllegalStateException("Unable to "+action+": "+e.getMessage(),e);}
    @Override public void close(){Arrays.fill(password,'\0');Arrays.fill(localSecretKey,(byte)0);}
    record Match(String classification,int confidence,List<String> hints){}
}
