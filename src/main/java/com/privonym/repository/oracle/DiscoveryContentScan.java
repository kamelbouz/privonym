package com.privonym.repository.oracle;

import com.privonym.application.service.DiscoveryFinding;
import com.privonym.application.service.DiscoveryScan;
import com.privonym.application.service.MaskingAlgorithmOption;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded source SELECTs, invoked only by the explicit Discovery action. */
final class DiscoveryContentScan {
    private DiscoveryContentScan() { }
    static final int ROWS=20, MAX_COLUMNS=100, TOTAL_CHARACTERS=2_000_000;

    static DiscoveryScan analyze(Connection repository,Connection target,long dataSourceId,
            String technology,DiscoveryScan metadata)throws SQLException {
        List<MaskingAlgorithmOption> available=new ArrayList<>();
        try(PreparedStatement query=repository.prepareStatement("""
                SELECT a.code,a.name,a.category FROM algorithm a
                JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id
                JOIN algorithm_implementation ai ON ai.algorithm_version_id=av.algorithm_version_id
                WHERE a.status='ACTIVE' AND av.status='PUBLISHED' AND ai.status='PUBLISHED'
                  AND ai.technology_code=? AND av.version_number=(SELECT MAX(v.version_number)
                    FROM algorithm_version v WHERE v.algorithm_id=a.algorithm_id AND v.status='PUBLISHED')
                ORDER BY a.code
                """)) {
            query.setString(1,technology);
            try(ResultSet row=query.executeQuery()) {
                while(row.next())available.add(new MaskingAlgorithmOption(row.getString(1),row.getString(2),row.getString(3),"PUBLISHED",List.of()));
            }
        }
        Map<Long,DiscoveryFinding> findings=new LinkedHashMap<>();
        for(DiscoveryFinding old:metadata.findings()) {
            String suggestion=choose(available,metadataAlgorithm(old.classification(),old.dataType()));
            findings.put(old.columnId(),new DiscoveryFinding(old.columnId(),old.schema(),old.table(),old.column(),
                    old.dataType(),old.classification(),old.confidence(),suggestion,
                    "Column name heuristic; content may not have been sampled. Review required.",List.of(),0));
        }
        List<String> warnings=new ArrayList<>();
        warnings.add("Local format/context heuristics on bounded samples; scores are not probabilities. Names without context may be missed. No sample text is returned or stored.");
        int scanned=0,remaining=TOTAL_CHARACTERS;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
        try(PreparedStatement query=repository.prepareStatement("""
                SELECT cc.catalog_column_id,cs.schema_name,ct.table_name,cc.column_name,cc.native_data_type
                FROM catalog_column cc JOIN catalog_table ct ON ct.catalog_table_id=cc.catalog_table_id
                JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                WHERE cs.data_source_id=? AND ct.enabled='Y'
                ORDER BY cs.schema_name,ct.table_name,cc.ordinal_position
                """)) {
            query.setLong(1,dataSourceId);
            try(ResultSet columns=query.executeQuery()) {
                while(columns.next()) {
                    String type=columns.getString(5);
                    if(!textType(type))continue;
                    if(scanned>=MAX_COLUMNS||remaining<=0||System.nanoTime()>=deadline) {
                        warnings.add("Content sampling limit reached; remaining columns were assessed by name only.");break;
                    }
                    scanned++;
                    long id=columns.getLong(1);
                    String schema=columns.getString(2),table=columns.getString(3),column=columns.getString(4);
                    List<String> samples=new ArrayList<>();
                    try(PreparedStatement sample=target.prepareStatement(sampleSql(technology,schema,table,column))) {
                        sample.setMaxRows(ROWS);sample.setFetchSize(ROWS);sample.setQueryTimeout(5);
                        try(ResultSet rows=sample.executeQuery()) {
                            while(remaining>0&&samples.size()<ROWS&&System.nanoTime()<deadline&&rows.next()) {
                                try(Reader reader=rows.getCharacterStream(1)) {
                                    if(reader==null)continue;
                                    char[] buffer=new char[Math.min(remaining,DiscoveryTextAnalyzer.MAX_CHARACTERS)];
                                    int length=0,count;
                                    while(length<buffer.length&&(count=reader.read(buffer,length,buffer.length-length))!=-1)length+=count;
                                    samples.add(new String(buffer,0,length));remaining-=length;
                                }
                            }
                        }
                    } catch(SQLException|IOException failure) {
                        target.rollback();
                        // Do not expose driver messages: they can include values, SQL or credentials.
                        warnings.add("Content sampling unavailable for "+schema+"."+table+"."+column+"; name-based assessment retained.");
                        continue;
                    }
                    var analysis=DiscoveryTextAnalyzer.analyze(samples);
                    DiscoveryFinding old=findings.get(id);
                    if(analysis.entities().isEmpty()) {
                        if(old!=null)findings.put(id,new DiscoveryFinding(id,schema,table,column,type,old.classification(),old.confidence(),
                                old.suggestedAlgorithm(),"Name heuristic; no sensitive pattern found in the sampled prefix. This does not prove absence.",List.of(),analysis.sampledValues()));
                        continue;
                    }
                    boolean keepSecret=old!=null&&old.classification().equals("SECRET");
                    String proposed=keepSecret?"NULLIFY":analysis.algorithm();
                    String selected=choose(available,proposed);
                    String reason=keepSecret?"Secret column name; whole-value removal takes priority over detected text formats.":analysis.reason();
                    if(selected.isEmpty())reason+=" Recommended algorithm is not published for this target; select a suitable alternative manually.";
                    findings.put(id,new DiscoveryFinding(id,schema,table,column,type,keepSecret?"SECRET":"TEXT_SENSITIVE",
                            keepSecret?99:analysis.score(),selected,reason,analysis.entities(),analysis.sampledValues()));
                }
            }
        }
        warnings.add("Sampled "+scanned+" text column(s), at most 20 non-NULL values and the first 16384 characters per value. Review column constraints and target function prerequisites before using suggestions.");
        return new DiscoveryScan(dataSourceId,metadata.connectionName(),metadata.rulesetExists(),available,List.copyOf(findings.values()),warnings);
    }

    static boolean textType(String type) {
        String normalized=type.toUpperCase(Locale.ROOT);
        return normalized.contains("CHAR")||normalized.contains("CLOB")||normalized.equals("TEXT")||normalized.equals("NTEXT")||normalized.equals("CITEXT");
    }

    static String sampleSql(String technology,String schema,String table,String column) {
        String q=quote(technology,column),from=quote(technology,schema)+"."+quote(technology,table);
        return switch(technology) {
            case "ORACLE" -> "SELECT SUBSTR("+q+",1,16384) FROM "+from+" WHERE "+q+" IS NOT NULL AND ROWNUM<=20";
            case "POSTGRESQL" -> "SELECT LEFT(CAST("+q+" AS TEXT),16384) FROM "+from+" WHERE "+q+" IS NOT NULL LIMIT 20";
            case "SQLSERVER" -> "SELECT TOP (20) LEFT(CONVERT(nvarchar(max),"+q+"),16384) FROM "+from+" WHERE "+q+" IS NOT NULL";
            default -> throw new IllegalArgumentException("Unsupported Discovery technology");
        };
    }
    private static String quote(String technology,String name) {
        if(name==null||name.isBlank()||name.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid catalog identifier");
        return technology.equals("SQLSERVER")?"["+name.replace("]","]]")+"]":"\""+name.replace("\"","\"\"")+"\"";
    }
    static String choose(List<MaskingAlgorithmOption> algorithms,String requested) {
        return algorithms.stream().anyMatch(a->a.code().equals(requested))?requested:"";
    }
    static String metadataAlgorithm(String classification,String type) {
        if(!textType(type)) {
            return classification.equals("BIRTH_DATE")&&(type.toUpperCase(Locale.ROOT).contains("DATE")
                    ||type.toUpperCase(Locale.ROOT).contains("TIMESTAMP"))?"DATE_SHIFT_DETERMINISTIC":"NULLIFY";
        }
        return switch(classification) {
            case "SECRET" -> "NULLIFY";
            case "EMAIL" -> "RANDOM_EMAIL_LOCAL_PART";
            case "PHONE" -> "RANDOM_PHONE_FR";
            case "FINANCIAL" -> "HASH_SHA256";
            case "PERSON_FIRST_NAME" -> "LOOKUP_FIRST_NAME";
            case "PERSON_LAST_NAME" -> "LOOKUP_LAST_NAME";
            case "PERSON_FULL_NAME" -> "CONSISTENT_LOOKUP";
            case "ORGANIZATION_NAME" -> "LOOKUP_COMPANY";
            case "ORGANIZATION_DEPARTMENT" -> "LOOKUP_DEPARTMENT";
            case "CITY" -> "LOOKUP_CITY";
            case "POSTAL_ADDRESS" -> "LOOKUP_ADDRESS";
            case "BIRTH_DATE" -> "NULLIFY";
            default -> "HASH_SHA256";
        };
    }
}
