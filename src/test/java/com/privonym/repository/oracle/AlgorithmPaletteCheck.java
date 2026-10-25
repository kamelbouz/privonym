package com.privonym.repository.oracle;

import com.privonym.adapter.oracle.AlgorithmParameter;
import com.privonym.adapter.oracle.OracleQualifiedTable;
import com.privonym.adapter.oracle.OracleTemplateRenderer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Synthetic integration checks; --apply installs the qualified helper and additive catalog metadata. */
public final class AlgorithmPaletteCheck {
    private static final List<String> CODES = List.of("MASK_PARTIAL","ROUND_NUMBER","NUMBER_BUCKET","TEXT_REDACT_COMMON");
    private static final List<String> PROFILES = List.of("TEXT_REDACT_EMAIL","TEXT_REDACT_PHONE_FR",
            "TEXT_REDACT_PHONE_CH","TEXT_REDACT_IBAN_FR_CH");
    private static final String PREFIX = "CASE WHEN ${column} IS NULL THEN NULL ELSE ";
    private AlgorithmPaletteCheck() { }

    static Map<String,Map<String,String>> templates(Path migration) throws Exception {
        String source = Files.readString(migration);
        var starts = Pattern.compile("(?m)^  algorithm\\('([A-Z0-9_]+)'").matcher(source);
        List<String> codes = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        while (starts.find()) { codes.add(starts.group(1)); offsets.add(starts.start()); }
        require(codes.equals(CODES), "expected four new palette algorithms");
        require(!Pattern.compile("(?im)^\\s*(UPDATE|DELETE|ALTER|DROP|TRUNCATE)\\s").matcher(source).find(),
                "palette migration must only add definitions");
        Map<String,Map<String,String>> result = new LinkedHashMap<>();
        for (int index = 0; index < codes.size(); index++) {
            String block = source.substring(offsets.get(index), index+1 < codes.size() ? offsets.get(index+1) : source.length());
            var literals = Pattern.compile("implementation\\('([^']+)',q'~(.*?)~'\\);",Pattern.DOTALL).matcher(block);
            Map<String,String> byTechnology = new LinkedHashMap<>();
            while (literals.find()) byTechnology.put(literals.group(1),literals.group(2));
            if (index < 3) require(byTechnology.size()==3,"three native dialects required");
            result.put(codes.get(index),byTechnology);
        }
        return result;
    }

    private static List<AlgorithmParameter> parameters(String code) {
        return switch(code) {
            case "MASK_PARTIAL" -> List.of(
                OracleParameterPolicy.create(code,"visible_prefix_length","INTEGER",false,"0"),
                OracleParameterPolicy.create(code,"visible_suffix_length","INTEGER",false,"4"),
                OracleParameterPolicy.create(code,"mask_character","ENUM",false,"*"));
            case "ROUND_NUMBER" -> List.of(OracleParameterPolicy.create(code,"scale","INTEGER",false,"0"));
            case "NUMBER_BUCKET" -> List.of(OracleParameterPolicy.create(code,"bucket_size","DECIMAL",false,"10"));
            default -> List.of(OracleParameterPolicy.create(code,"replacement","STRING",false,"ANONYMIZED"));
        };
    }

    private static String render(String code,String expression,Map<String,String> values) {
        return new OracleTemplateRenderer().render(expression,"VALUE",new OracleQualifiedTable("DEMO","T"),parameters(code),values);
    }

    public static void verify(Path migration) throws Exception {
        var definitions=templates(migration);
        for (String technology:List.of("ORACLE","POSTGRESQL","SQLSERVER")) {
            for (String code:CODES.subList(0,3)) {
                require(!render(code,definitions.get(code).get(technology),Map.of()).contains("${"),
                        "all native templates must render with validated defaults");
            }
        }
        for (var invalid:List.of(
                Map.of("visible_prefix_length","-1"),Map.of("visible_suffix_length","101"),
                Map.of("mask_character","XX"),Map.of("mask_character","好"))) {
            boolean rejected=false;
            try { render("MASK_PARTIAL",definitions.get("MASK_PARTIAL").get("ORACLE"),invalid); }
            catch(com.privonym.adapter.oracle.OracleTemplateException | IllegalArgumentException expected){rejected=true;}
            require(rejected,"invalid partial-mask parameters must be rejected");
        }
        for (String value:List.of("0","-1","0.0000001","1000000000001")) {
            boolean rejected=false;
            try {render("NUMBER_BUCKET",definitions.get("NUMBER_BUCKET").get("ORACLE"),Map.of("bucket_size",value));}
            catch(com.privonym.adapter.oracle.OracleTemplateException | IllegalArgumentException expected){rejected=true;}
            require(rejected,"invalid bucket width must be rejected");
        }
        for(String value:List.of("-7","7")) {
            boolean rejected=false;
            try {render("ROUND_NUMBER",definitions.get("ROUND_NUMBER").get("ORACLE"),Map.of("scale",value));}
            catch(com.privonym.adapter.oracle.OracleTemplateException | IllegalArgumentException expected){rejected=true;}
            require(rejected,"invalid rounding scale must be rejected");
        }
        System.out.println("Algorithm palette offline checks passed.");
    }

    private static String compose(Connection repository,String technology) throws Exception {
        String expression="${column}";
        try(PreparedStatement query=repository.prepareStatement("""
                SELECT ai.implementation_code FROM algorithm a
                  JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id
                  JOIN algorithm_implementation ai ON ai.algorithm_version_id=av.algorithm_version_id
                 WHERE a.code=? AND av.status='PUBLISHED' AND ai.status='PUBLISHED' AND ai.technology_code=?
                 ORDER BY av.version_number DESC FETCH FIRST 1 ROW ONLY
                """)) {
            for(String profile:PROFILES) {
                query.setString(1,profile);query.setString(2,technology);
                try(ResultSet row=query.executeQuery()) {
                    require(row.next(),"source profile missing: "+profile+" / "+technology);
                    String body=row.getString(1);
                    require(body.startsWith(PREFIX)&&body.endsWith(" END"),"unexpected profile shape");
                    body=body.substring(PREFIX.length(),body.length()-4);
                    require(body.indexOf("${column}")==body.lastIndexOf("${column}"),"source must be referenced once");
                    expression=body.replace("${column}",expression);
                }
            }
        }
        return PREFIX+expression+" END";
    }

    private static Object evaluate(Connection target,String technology,String code,String template,
            Object input,Map<String,String> parameters) throws Exception {
        if(template.startsWith("CANDIDATE:")) {
            String value=input==null?null:input.toString();
            String body=template.substring("CANDIDATE:".length());
            for(String profile:List.of("EMAIL","PHONE_FR","PHONE_CH","IBAN_FR_CH")) {
                try(PreparedStatement query=target.prepareStatement(
                        "DECLARE @value nvarchar(max)=?, @profile varchar(32)=?, @replacement nvarchar(100)=?; "+body)) {
                    query.setQueryTimeout(60);query.setNString(1,value);query.setString(2,profile);
                    query.setNString(3,parameters.getOrDefault("replacement","ANONYMIZED"));
                    try(ResultSet row=query.executeQuery()){require(row.next(),"missing candidate result");value=row.getString(1);}
                }
            }
            return value;
        }
        boolean numeric=code.equals("ROUND_NUMBER")||code.equals("NUMBER_BUCKET");
        String inputSql=numeric ? (technology.equals("ORACLE")?"CAST(? AS NUMBER)":"CAST(? AS decimal(28,6))")
                : switch(technology){case "ORACLE"->"TO_CLOB(?)";case "SQLSERVER"->"CAST(? AS nvarchar(max))";default->"CAST(? AS TEXT)";};
        String sql="SELECT "+render(code,template,parameters)+" FROM (SELECT "+inputSql+" \"VALUE\""
                +(technology.equals("ORACLE")?" FROM DUAL":"")+") source";
        try(PreparedStatement query=target.prepareStatement(sql)) {
            query.setQueryTimeout(60);
            if(input==null) query.setNull(1,numeric?Types.DECIMAL:technology.equals("ORACLE")?Types.CLOB:Types.VARCHAR);
            else if(numeric) query.setBigDecimal(1,new BigDecimal(input.toString()));
            else if(technology.equals("ORACLE")) query.setClob(1,new java.io.StringReader(input.toString()));
            else if(technology.equals("SQLSERVER")) query.setNString(1,input.toString());
            else query.setString(1,input.toString());
            try(ResultSet row=query.executeQuery()) {
                require(row.next(),"missing synthetic result");
                return numeric?row.getBigDecimal(1):row.getString(1);
            }
        }
    }

    private static void same(Object expected,Object actual,String message) {
        boolean equal=expected instanceof BigDecimal number ? actual instanceof BigDecimal result&&number.compareTo(result)==0
                : Objects.equals(expected,actual);
        if(!equal && expected instanceof String wanted && actual instanceof String found) {
            int index=0;while(index<Math.min(wanted.length(),found.length())&&wanted.charAt(index)==found.charAt(index))index++;
            throw new IllegalStateException(message+" at "+index+"; lengths "+wanted.length()+" / "+found.length()
                    +"; expected: "+wanted.substring(Math.max(0,index-20),Math.min(wanted.length(),index+150))
                    +"; actual: "+found.substring(Math.max(0,index-20),Math.min(found.length(),index+150)));
        }
        require(equal,message);
    }

    private static void check(Connection target,String technology,Map<String,Map<String,String>> definitions) throws Exception {
        String mask=definitions.get("MASK_PARTIAL").get(technology);
        Map<String,String> partial=Map.of("visible_prefix_length","2","visible_suffix_length","3");
        same("AB*****HIJ",evaluate(target,technology,"MASK_PARTIAL",mask,"ABCDEFGHIJ",partial),"partial mask mismatch");
        same("***",evaluate(target,technology,"MASK_PARTIAL",mask,"ABC",Map.of()),"short values must be fully masked");
        same("***ce  ",evaluate(target,technology,"MASK_PARTIAL",mask,"Alice  ",Map.of()),"trailing spaces must count");
        same("Él****中文",evaluate(target,technology,"MASK_PARTIAL",mask,"Élodie中文",
                Map.of("visible_prefix_length","2","visible_suffix_length","2")),"Unicode characters changed");
        same("#".repeat(70000),evaluate(target,technology,"MASK_PARTIAL",mask,"A".repeat(70000),
                Map.of("visible_suffix_length","0","mask_character","#")),"long mask truncated");
        same(null,evaluate(target,technology,"MASK_PARTIAL",mask,null,Map.of()),"NULL text changed");
        if(!technology.equals("ORACLE"))
            same("",evaluate(target,technology,"MASK_PARTIAL",mask,"",Map.of()),"empty text changed");
        for(String value:List.of("123.456","-123.456","0")) {
            same(new BigDecimal(value).setScale(2,java.math.RoundingMode.HALF_UP),
                    evaluate(target,technology,"ROUND_NUMBER",definitions.get("ROUND_NUMBER").get(technology),
                            value,Map.of("scale","2")),"numeric rounding mismatch");
        }
        same(new BigDecimal("1200"),evaluate(target,technology,"ROUND_NUMBER",definitions.get("ROUND_NUMBER").get(technology),
                "1234.56",Map.of("scale","-2")),"negative rounding scale mismatch");
        for(String value:List.of("27.5","-0.000001","-10","-10.01","0","19.999999")) {
            BigDecimal number=new BigDecimal(value);
            BigDecimal width=new BigDecimal("10");
            same(number.divide(width,0,java.math.RoundingMode.FLOOR).multiply(width),
                    evaluate(target,technology,"NUMBER_BUCKET",definitions.get("NUMBER_BUCKET").get(technology),
                            value,Map.of()),"negative/boundary numeric bucket mismatch");
        }
        same(new BigDecimal("-0.25"),evaluate(target,technology,"NUMBER_BUCKET",definitions.get("NUMBER_BUCKET").get(technology),
                "-0.01",Map.of("bucket_size","0.25")),"fractional bucket mismatch");
        for(String code:List.of("ROUND_NUMBER","NUMBER_BUCKET"))
            same(null,evaluate(target,technology,code,definitions.get(code).get(technology),null,Map.of()),"NULL number changed");
        String raw="alice@example.com; +33 6 12 34 56 78; +41 79 123 45 67; CH93 0076 2011 6238 5295 7";
        String replacement="中文 O'Brien \\1";
        String expected=String.join("; ",Collections.nCopies(4,replacement));
        String longInput="Élodie ".repeat(10000)+raw+" FIN "+raw;
        String longExpected="Élodie ".repeat(10000)+expected+" FIN "+expected;
        String common=definitions.get("TEXT_REDACT_COMMON").get(technology);
        same("a,ANONYMIZED; FIN",evaluate(target,technology,"TEXT_REDACT_COMMON",common,
                "a,alice@example.com; FIN",Map.of()),"email delimiters must remain outside replacement");
        same(longExpected,evaluate(target,technology,"TEXT_REDACT_COMMON",common,longInput,
                Map.of("replacement",replacement)),"combined text profile mismatch");
        same("Texte inchangé.  ",evaluate(target,technology,"TEXT_REDACT_COMMON",common,"Texte inchangé.  ",Map.of()),
                "unmatched text changed");
        same(null,evaluate(target,technology,"TEXT_REDACT_COMMON",common,null,Map.of()),"NULL common text changed");
        System.out.println(technology+": four algorithms passed; NULL, bounds, negatives, Unicode, spaces, mixed profiles and >65K text.");
    }

    public static void main(String[] args) throws Exception {
        if(args.length<2||args.length>3)throw new IllegalArgumentException("Expected properties, V2_024 path, optional --apply or --qualify-helper");
        boolean apply=args.length==3&&args[2].equals("--apply");
        boolean qualifyHelper=args.length==3&&args[2].equals("--qualify-helper");
        if(args.length==3&&!apply&&!qualifyHelper)throw new IllegalArgumentException("Unknown option");
        Path migration=Path.of(args[1]);
        verify(migration);
        var definitions=templates(migration);
        Properties config=new Properties();
        try(var input=Files.newInputStream(Path.of(args[0]))){config.load(input);}
        String url=config.getProperty("repository.url");
        if(url==null||url.isBlank())url="jdbc:oracle:thin:@//"+config.getProperty("repository.host")+":"
                +config.getProperty("repository.port","1521")+"/"+config.getProperty("repository.service");
        Properties credentials=new Properties();
        credentials.setProperty("user",config.getProperty("repository.user"));
        credentials.setProperty("password",config.getProperty("repository.password"));
        credentials.setProperty("oracle.net.CONNECT_TIMEOUT","10000");
        credentials.setProperty("oracle.jdbc.ReadTimeout","15000");
        DriverManager.setLoginTimeout(15);
        try(Connection repository=DriverManager.getConnection(url,credentials)) {
            repository.setAutoCommit(false);
            try(Statement read=repository.createStatement()){read.execute("SET TRANSACTION READ ONLY");}
            String source=Files.readString(migration);
            String block=source.substring(source.indexOf("DECLARE"),source.lastIndexOf("\n/"));
            try(CallableStatement parse=repository.prepareCall("DECLARE c INTEGER; BEGIN c:=DBMS_SQL.OPEN_CURSOR; "
                    +"BEGIN DBMS_SQL.PARSE(c,?,DBMS_SQL.NATIVE); EXCEPTION WHEN OTHERS THEN DBMS_SQL.CLOSE_CURSOR(c); RAISE; END;"
                    +"DBMS_SQL.CLOSE_CURSOR(c); END;")){parse.setString(1,block);parse.execute();}
            for(String technology:List.of("ORACLE","POSTGRESQL","SQLSERVER"))
                definitions.get("TEXT_REDACT_COMMON").put(technology,compose(repository,technology));
            check(repository,"ORACLE",definitions);
            repository.rollback();
            if(apply) requireIdle(repository);
            try(Statement query=repository.createStatement();ResultSet rows=query.executeQuery("""
                    SELECT connector_id,technology_code,host_name,port,service_name,database_name,
                           username_value,encrypted_password FROM connector
                     WHERE technology_code IN('POSTGRESQL','SQLSERVER') AND status='ACTIVE' ORDER BY connector_id
                    """)){
                Set<String> checked=new HashSet<>();
                while(rows.next()){
                    String technology=rows.getString(2);
                    char[] password=LocalCredentialStore.decrypt(rows.getString(8));
                    var details=new OracleConnectionDetails(technology,rows.getString(3),rows.getInt(4),
                            rows.getString(5),rows.getString(6),rows.getString(7));
                    try(Connection target=DriverManager.getConnection(details.jdbcUrl(),details.username(),new String(password))){
                        target.setAutoCommit(false);
                        if(technology.equals("POSTGRESQL"))target.setReadOnly(true);
                        if(technology.equals("SQLSERVER")&&(apply||qualifyHelper)) {
                            Path helper=migration.toAbsolutePath().getParent().getParent()
                                    .resolve("target-functions/sqlserver/privonym_text_redact.sql");
                            String helperSql=Files.readString(helper);
                            if(qualifyHelper) {
                                String body=helperSql.substring(helperSql.indexOf("\nBEGIN")+6,helperSql.lastIndexOf("END;"));
                                body=body.replace("IF @value IS NULL RETURN NULL;",
                                        "IF @value IS NULL BEGIN SELECT CAST(NULL AS nvarchar(max)); RETURN; END;")
                                        .replace("RETURN @result+SUBSTRING", "SELECT @result+SUBSTRING");
                                definitions.get("TEXT_REDACT_COMMON").put(technology,"CANDIDATE:"+body);
                            } else try(Statement ddl=target.createStatement()) {
                                ddl.setQueryTimeout(30);ddl.execute("SET QUOTED_IDENTIFIER ON");
                                try(ResultSet current=ddl.executeQuery("SELECT OBJECT_DEFINITION(OBJECT_ID('dbo.PRIVONYM_TEXT_REDACT'))")) {
                                    require(current.next(),"helper definition unavailable");
                                    String installed=current.getString(1);
                                    require(installed!=null,"existing helper must be readable before replacement");
                                    String previous=helperSql.replace("[-A-Za-z0-9_.%+]","[A-Za-z0-9_.%+-]")
                                            .replace("[-A-Za-z0-9.]","[A-Za-z0-9.-]");
                                    require(canonicalHelper(installed).equals(canonicalHelper(previous))
                                            ||canonicalHelper(installed).equals(canonicalHelper(helperSql)),
                                            "Unrecognized SQL Server helper; refusing replacement.");
                                    Path backup=Path.of("audit/algorithms/helper-backup-"+UUID.randomUUID()+".sql");
                                    Files.createDirectories(backup.getParent());Files.writeString(backup,installed);
                                }
                                ddl.execute(helperSql);
                            }
                        }
                        check(target,technology,definitions);
                        if(technology.equals("SQLSERVER")&&apply) {
                            target.commit();System.out.println("SQL Server text helper correction installed after qualification.");
                        }
                        target.rollback();checked.add(technology);
                    }finally{Arrays.fill(password,'\0');}
                }
                require(checked.containsAll(Set.of("POSTGRESQL","SQLSERVER")),"both portable targets must be tested");
            }
            repository.rollback();
            if(!apply){System.out.println(qualifyHelper
                    ? "Qualification completed; helper body evaluated without DDL; no catalog or business data modified."
                    : "SELECT-only qualification completed; no catalog or business data modified.");return;}
            install(repository,migration,block,config.getProperty("repository.user"),definitions);
        }
    }

    private static void install(Connection repository,Path migration,String block,String user,
            Map<String,Map<String,String>> definitions)throws Exception{
        String checksum=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(migration)));
        try{
            requireIdle(repository);
            try(Statement statement=repository.createStatement()){statement.execute(block);}
            try(PreparedStatement query=repository.prepareStatement("""
                    SELECT ai.technology_code,ai.implementation_code FROM algorithm a
                      JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id
                      JOIN algorithm_implementation ai ON ai.algorithm_version_id=av.algorithm_version_id
                     WHERE a.code=? AND av.version_number=1 AND ai.status='PUBLISHED'
                    """)){
                for(String code:CODES){
                    query.setString(1,code);int count=0;
                    try(ResultSet rows=query.executeQuery()){
                        while(rows.next()){same(definitions.get(code).get(rows.getString(1)),rows.getString(2),"installed template differs");count++;}
                    }
                    require(count==3,"three installed dialects required");
                }
            }
            try(PreparedStatement query=repository.prepareStatement(
                    "SELECT checksum_sha256 FROM privonym_schema_migration WHERE migration_name=?")){
                query.setString(1,migration.getFileName().toString());
                try(ResultSet row=query.executeQuery()){
                    if(row.next())same(checksum,row.getString(1),"recorded migration checksum mismatch");
                    else try(PreparedStatement insert=repository.prepareStatement(
                            "INSERT INTO privonym_schema_migration(migration_name,checksum_sha256,applied_by) VALUES(?,?,?)")){
                        insert.setString(1,migration.getFileName().toString());insert.setString(2,checksum);insert.setString(3,user);insert.executeUpdate();
                    }
                }
            }
            repository.commit();
            System.out.println("V2_024 installed: four new algorithms, twelve implementations. Existing rules and business tables unchanged.");
        }catch(Exception failure){repository.rollback();throw failure;}
    }

    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}

    private static String canonicalHelper(String sql) {
        return sql.substring(sql.indexOf("CREATE")).replace("\r\n","\n")
                .replaceFirst("CREATE\\s+(?:OR\\s+ALTER\\s+)?FUNCTION", "CREATE FUNCTION").strip();
    }

    private static void requireIdle(Connection repository)throws SQLException {
        try(Statement query=repository.createStatement();ResultSet row=query.executeQuery(
                "SELECT COUNT(*) FROM anonymization_job WHERE status IN('RUNNING','RETRYING','PAUSING','CANCELLING','VALIDATING')")) {
            row.next();require(row.getInt(1)==0,"Active jobs exist; activation deferred.");
        }
    }
}
