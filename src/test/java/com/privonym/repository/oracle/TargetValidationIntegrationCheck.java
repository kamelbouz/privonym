package com.privonym.repository.oracle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import java.sql.ResultSet;
import java.util.Arrays;
import java.util.HashSet;

import java.util.Properties;
import java.util.Set;

/** Optional synthetic SELECTs only: no application scan, DDL or business-table reads. */
public final class TargetValidationIntegrationCheck {
    private TargetValidationIntegrationCheck() { }
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
            TargetValidationSmokeCheck.verifyLive(repository,"ORACLE");
            Set<String> technologies=new HashSet<>();
            try(var query=repository.createStatement();ResultSet rows=query.executeQuery("SELECT technology_code,host_name,port,service_name,database_name,username_value,encrypted_password FROM connector WHERE technology_code IN('POSTGRESQL','SQLSERVER') AND status='ACTIVE'")) {
                while(rows.next()) {
                    String technology=rows.getString(1);
                    var details=new OracleConnectionDetails(technology,rows.getString(2),rows.getInt(3),rows.getString(4),rows.getString(5),rows.getString(6));
                    char[] password=LocalCredentialStore.decrypt(rows.getString(7));
                    try(Connection target=DriverManager.getConnection(details.jdbcUrl(),details.username(),new String(password))) {
                        target.setAutoCommit(false);target.setReadOnly(true);TargetValidationSmokeCheck.verifyLive(target,technology);target.rollback();technologies.add(technology);
                    } finally {Arrays.fill(password,'\0');}
                }
            }
            if(!technologies.containsAll(Set.of("POSTGRESQL","SQLSERVER")))throw new IllegalStateException("Both targets required");
            repository.rollback();
        }
    }
}