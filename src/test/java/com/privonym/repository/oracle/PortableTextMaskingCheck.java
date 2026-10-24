package com.privonym.repository.oracle;

import java.nio.file.*;
import java.util.*;
import java.sql.*;

public final class PortableTextMaskingCheck {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Expected job properties, SQL Server helper script, optional --install-sqlserver-helper");
        boolean install = args.length == 3 && args[2].equals("--install-sqlserver-helper");
        Properties config = new Properties();
        try (var in = Files.newInputStream(Path.of(args[0]))) {
            config.load(in);
        }
        String url = config.getProperty("repository.url");
        if (url == null || url.isBlank()) {
            url = "jdbc:oracle:thin:@//" + config.getProperty("repository.host") + ":"
                    + config.getProperty("repository.port", "1521") + "/"
                    + config.getProperty("repository.service");
        }
        Properties credentials = new Properties();
        credentials.setProperty("user", config.getProperty("repository.user"));
        credentials.setProperty("password", config.getProperty("repository.password"));
        credentials.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        credentials.setProperty("oracle.jdbc.ReadTimeout", "15000");
        try (Connection connection = DriverManager.getConnection(url, credentials)) {
            connection.setAutoCommit(false);
            try (var read = connection.createStatement()) { read.execute("SET TRANSACTION READ ONLY"); }
            var decrypt = Class.forName("com.privonym.repository.oracle.LocalCredentialStore").getDeclaredMethod("decrypt",String.class);
            decrypt.setAccessible(true);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT DISTINCT c.connector_id,c.technology_code,c.host_name,c.port,c.service_name,c.database_name,c.username_value,c.encrypted_password FROM connector c WHERE c.technology_code IN ('POSTGRESQL','SQLSERVER') AND c.status='ACTIVE' ORDER BY c.connector_id")) {
                while (rows.next()) {
                    String technology = rows.getString(2);
                    char[] password = (char[]) decrypt.invoke(null,rows.getString(8));
                    try {
                        var details = new com.privonym.repository.oracle.OracleConnectionDetails(technology,rows.getString(3),rows.getInt(4),rows.getString(5),rows.getString(6),rows.getString(7));
                        DriverManager.setLoginTimeout(10);
                        try (Connection target = DriverManager.getConnection(details.jdbcUrl(),details.username(),new String(password))) {
                            target.setAutoCommit(false);
                            if (technology.equals("POSTGRESQL")) target.setReadOnly(true);
                            if (technology.equals("SQLSERVER") && install) {
                                try (var ddl = target.createStatement()) {
                                    ddl.execute("SET QUOTED_IDENTIFIER ON");
                                    try (var found = ddl.executeQuery("SELECT OBJECT_ID('dbo.PRIVONYM_TEXT_REDACT')")) {
                                        found.next();
                                        if (found.getObject(1) != null) throw new IllegalStateException("Target text helper already exists; refusing to replace it");
                                    }
                                    ddl.execute(Files.readString(Path.of(args[1])));
                                }
                            }
                            Map<String,String> samples = new LinkedHashMap<>();
                            samples.put("REGEX_MASK","alice@example.com");
                            samples.put("TEXT_REDACT_EMAIL","alice@example.com");
                            samples.put("TEXT_REDACT_PHONE_FR","+33 6 12 34 56 78");
                            samples.put("TEXT_REDACT_PHONE_CH","+41 79 123 45 67");
                            samples.put("TEXT_REDACT_IBAN_FR_CH","CH93 0076 2011 6238 5295 7");
                            for (var sample : samples.entrySet()) {
                                String code=sample.getKey();
                                if(technology.equals("SQLSERVER") && code.equals("REGEX_MASK")) continue;
                                String profile=code.startsWith("TEXT_REDACT_")?code.substring("TEXT_REDACT_".length()):code;
                                String expression;
                                if (technology.equals("POSTGRESQL") || technology.equals("SQLSERVER")) {
                                    try (var metadata = connection.prepareStatement("SELECT ai.implementation_code FROM algorithm a JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id JOIN algorithm_implementation ai ON ai.algorithm_version_id=av.algorithm_version_id WHERE a.code=? AND av.version_number=CASE WHEN a.code='REGEX_MASK' THEN 2 ELSE 1 END AND ai.technology_code=?")) {
                                        metadata.setString(1,code); metadata.setString(2,technology);
                                        try (var definition=metadata.executeQuery()) { definition.next(); expression=definition.getString(1); }
                                    }
                                    expression=expression.replace("${column}","\"BODY\"").replace("N${param.replacement}","?").replace("${param.replacement}","?").replace("${param.pattern}","?");
                                } else expression="dbo.PRIVONYM_TEXT_REDACT(\"BODY\",'"+profile+"',?)";
                                String padding="Texte épargné. ".repeat(5000);
                                String input="Début "+sample.getValue()+". "+padding+"Fin "+sample.getValue()+" !  ";
                                String replacement="[masqué d'Élodie \\1 匿名]";
                                if(code.equals("REGEX_MASK")) replacement="[masqué Unicode 匿名]";
                                String expected=input.replace(sample.getValue(),replacement);
                                String cast=technology.equals("POSTGRESQL")?"text":"nvarchar(max)";
                                try(var query=target.prepareStatement("SELECT "+expression+" FROM (SELECT CAST(? AS "+cast+") AS \"BODY\") s")) {
                                    query.setQueryTimeout(60); int parameter=1;
                                    if(code.equals("REGEX_MASK")) query.setString(parameter++,"[[:alnum:]_.%+-]+@[[:alnum:].-]+[.][[:alpha:]]{2,}");
                                    query.setString(parameter++,replacement); int sourceParameter=parameter; query.setString(sourceParameter,input);
                                    try(var result=query.executeQuery()) { result.next(); if(!expected.equals(result.getString(1))) throw new IllegalStateException(code+" long-text result mismatch"); }
                                    query.setNull(sourceParameter,technology.equals("POSTGRESQL")?Types.VARCHAR:Types.NVARCHAR);
                                    try(var result=query.executeQuery()) { result.next(); if(result.getString(1)!=null) throw new IllegalStateException(code+" NULL changed"); }
                                    query.setString(sourceParameter,"Texte inchangé.  ");
                                    try(var result=query.executeQuery()) { result.next(); if(!"Texte inchangé.  ".equals(result.getString(1))) throw new IllegalStateException(code+" unmatched text changed"); }
                                }
                                System.out.println(technology+" "+code+": >65K, repeated/end matches, Unicode/literal replacement, NULL and no-match OK");
                            }
                            if(technology.equals("SQLSERVER") && install) {
                                target.commit();
                                System.out.println("SQL Server helper installed. No business table modified; no CLR/security setting changed.");
                            }
                            target.rollback();
                        }
                    } catch (Exception error) { throw new IllegalStateException(technology + " checks failed", error); }
                    finally { Arrays.fill(password,'\0'); }
                }
            }
            connection.rollback();
        }
    }
}