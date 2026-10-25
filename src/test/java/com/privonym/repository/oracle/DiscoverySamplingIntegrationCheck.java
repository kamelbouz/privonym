package com.privonym.repository.oracle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Optional synthetic SELECTs only: no application scan, DDL or business-table reads. */
public final class DiscoverySamplingIntegrationCheck {
    private DiscoverySamplingIntegrationCheck() { }
    public static void main(String[] args)throws Exception {
        Properties p=new Properties();
        try(var input=Files.newInputStream(Path.of(args[0]))){p.load(input);}
        String url=p.getProperty("repository.url");
        if(url==null||url.isBlank())url="jdbc:oracle:thin:@//"+p.getProperty("repository.host")+":"
                +p.getProperty("repository.port","1521")+"/"+p.getProperty("repository.service");
        Properties credentials=new Properties();
        credentials.setProperty("user",p.getProperty("repository.user"));credentials.setProperty("password",p.getProperty("repository.password"));
        credentials.setProperty("oracle.net.CONNECT_TIMEOUT","10000");credentials.setProperty("oracle.jdbc.ReadTimeout","15000");
        try(Connection repository=DriverManager.getConnection(url,credentials)) {
            repository.setAutoCommit(false);
            try(var read=repository.createStatement()){read.execute("SET TRANSACTION READ ONLY");}
            check(repository,"ORACLE");
            Set<String> technologies=new HashSet<>();
            try(var query=repository.createStatement();ResultSet rows=query.executeQuery("SELECT technology_code,host_name,port,service_name,database_name,username_value,encrypted_password FROM connector WHERE technology_code IN('POSTGRESQL','SQLSERVER') AND status='ACTIVE'")) {
                while(rows.next()) {
                    String technology=rows.getString(1);
                    var details=new OracleConnectionDetails(technology,rows.getString(2),rows.getInt(3),rows.getString(4),rows.getString(5),rows.getString(6));
                    char[] password=LocalCredentialStore.decrypt(rows.getString(7));
                    try(Connection target=DriverManager.getConnection(details.jdbcUrl(),details.username(),new String(password))) {
                        target.setAutoCommit(false);target.setReadOnly(true);check(target,technology);target.rollback();technologies.add(technology);
                    } finally {Arrays.fill(password,'\0');}
                }
            }
            if(!technologies.containsAll(Set.of("POSTGRESQL","SQLSERVER")))throw new IllegalStateException("Both targets required");
            repository.rollback();
        }
    }
    private static void check(Connection connection,String technology)throws Exception {
        String q=technology.equals("SQLSERVER")?"[DISCOVERY_SYNTHETIC]":"\"DISCOVERY_SYNTHETIC\"";
        String qualified=technology.equals("SQLSERVER")?"[S].[DISCOVERY_SYNTHETIC]":"\"S\".\"DISCOVERY_SYNTHETIC\"";
        String rows=switch(technology) {
            case "ORACLE" -> "SELECT TO_CLOB(?) \"BODY\" FROM DUAL CONNECT BY LEVEL<=25";
            case "POSTGRESQL" -> "SELECT CAST(? AS TEXT) \"BODY\" FROM generate_series(1,25)";
            default -> "SELECT CONVERT(nvarchar(max),?) [BODY] FROM (VALUES "+String.join(",",java.util.Collections.nCopies(25,"(1)"))+") numbers(n)";
        };
        String sql="WITH "+q+" AS ("+rows+") "+DiscoveryContentScan.sampleSql(technology,"S","DISCOVERY_SYNTHETIC","BODY").replace(qualified,q);
        String prefix="Mme Dupont, 12 rue des Lilas. alice@example.com; +41 79 123 45 67. ";
        String input=prefix+"É中文 ".repeat(20000);
        try(PreparedStatement query=connection.prepareStatement(sql)) {
            query.setQueryTimeout(15);query.setMaxRows(20);query.setFetchSize(20);
            if(technology.equals("ORACLE"))query.setClob(1,new java.io.StringReader(input));
            else if(technology.equals("SQLSERVER"))query.setNString(1,input);
            else query.setString(1,input);
            int count=0;
            try(ResultSet result=query.executeQuery()) {
                while(result.next()) {
                    String text;
                    try(var reader=result.getCharacterStream(1)) {
                        var buffer=new java.io.StringWriter();reader.transferTo(buffer);text=buffer.toString();
                    }
                    if(text.length()!=16384||!text.equals(input.substring(0,16384)))throw new IllegalStateException("Sample Unicode/prefix changed");
                    var analysis=DiscoveryTextAnalyzer.analyze(List.of(text));
                    if(!analysis.entities().containsAll(List.of("EMAIL","PHONE_CH","PERSON_CONTEXT","ADDRESS_CONTEXT"))
                            ||!analysis.algorithm().equals("NULLIFY"))throw new IllegalStateException("Context recommendation mismatch");
                    count++;
                }
            }
            if(count!=20)throw new IllegalStateException("Source row limit changed: "+count);
        }
        System.out.println(technology+": synthetic >65K source, Unicode prefix, 20-row SQL limit, streamed text and Discovery-only recommendations passed; no business table read or modified.");
    }
}
