package com.privonym.repository.oracle;

import com.privonym.application.service.OracleSqlInventoryPreviewService;
import com.privonym.adapter.oracle.SqlChecksum;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Versioned import source is the provenance; a Job receives an immutable script snapshot. */
final class ImportedPhaseScripts {
    private ImportedPhaseScripts() { }
    static String source(Connection c,long version)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("""
                SELECT ti.source_sql FROM transformation_import ti
                JOIN ruleset_version v ON v.ruleset_version_id=ti.ruleset_version_id
                JOIN ruleset r ON r.ruleset_id=v.ruleset_id JOIN data_source d ON d.data_source_id=r.data_source_id
                JOIN connector co ON co.connector_id=d.connector_id
                WHERE ti.ruleset_version_id=? AND co.technology_code='ORACLE'
                ORDER BY ti.transformation_import_id DESC FETCH FIRST 1 ROW ONLY
                """)) {
            q.setLong(1,version);try(ResultSet rows=q.executeQuery()) {
                if(!rows.next())return "";
                String sql=rows.getString(1);
                return sql==null?"":sql;
            }
        }
    }
    static String phase(Connection c,long version,String phase)throws SQLException {
        String sql=source(c,version);if(!hasStructuralSql(sql))return "";
        var preview=new OracleSqlInventoryPreviewService().parse(sql);
        return phase.equals("PRE")?preview.preScript():preview.postScript();
    }
    static String boundSource(Connection c,long version,String phase)throws SQLException {
        return phase(c,version,phase);
    }
    static void attach(Connection c,long job,long version)throws SQLException {
        attach(c,job,version,"PRE");attach(c,job,version,"POST");
    }
    private static void attach(Connection c,long job,long version,String phase)throws SQLException {
        String previous=null;
        try(PreparedStatement q=c.prepareStatement("SELECT file_name FROM job_script WHERE job_id=? AND script_phase=?")) {
            q.setLong(1,job);q.setString(2,phase);try(ResultSet rows=q.executeQuery()){if(rows.next())previous=rows.getString(1);}
        }
        if(previous!=null&&!previous.startsWith("transformer-"+phase.toLowerCase(java.util.Locale.ROOT)+"-v"))return;
        String post=phase(c,version,phase);
        if(previous!=null)try(PreparedStatement q=c.prepareStatement("DELETE FROM job_script WHERE job_id=? AND script_phase=?")){q.setLong(1,job);q.setString(2,phase);q.executeUpdate();}
        if(post.isBlank())return;
        try(PreparedStatement q=c.prepareStatement("INSERT INTO job_script(job_script_id,job_id,script_phase,file_name,script_content,created_at,updated_at) VALUES(job_script_seq.NEXTVAL,?,?,?,?,SYSTIMESTAMP,SYSTIMESTAMP)")) {
            q.setLong(1,job);q.setString(2,phase);q.setString(3,"transformer-"+phase.toLowerCase(java.util.Locale.ROOT)+"-v"+version+".sql");q.setString(4,post);q.executeUpdate();
        }
    }
    static void copy(Connection c,long sourceVersion,long targetVersion,String actor)throws SQLException {
        String post=source(c,sourceVersion);if(!hasStructuralSql(post))return;
        var preview=new OracleSqlInventoryPreviewService().parse(post);
        if(preview.preScript().isBlank()&&preview.postScript().isBlank())return;
        try(PreparedStatement q=c.prepareStatement("SELECT r.data_source_id FROM ruleset_version v JOIN ruleset r ON r.ruleset_id=v.ruleset_id WHERE v.ruleset_version_id IN (?,?)")) {
            q.setLong(1,sourceVersion);q.setLong(2,targetVersion);Long source=null;boolean crossConnection=false;
            try(ResultSet rows=q.executeQuery()){while(rows.next()){long id=rows.getLong(1);if(source==null)source=id;else if(source!=id)crossConnection=true;}}
            if(crossConnection&&Pattern.compile("(?im)^ALTER\\s+TABLE\\s+(?:\"(?:\"\"|[^\"])+\"|[A-Za-z][A-Za-z0-9_$#]*)\\s*\\.").matcher(preview.preScript()+"\n"+preview.postScript()).find())
                throw new IllegalArgumentException("POST script contains a literal schema owner; review its target before cloning to another connection");
        }
        try(PreparedStatement q=c.prepareStatement("""
                INSERT INTO transformation_import(data_source_id,ruleset_version_id,import_reference,source_type,
                    source_name,source_sql,source_checksum,status,statement_count,matched_count,warning_count,
                    unresolved_count,created_by,analyzed_at,draft_created_at)
                SELECT r.data_source_id,v.ruleset_version_id,?,'INHERITED_SQL',?, ?,?,'DRAFT_CREATED',?,0,0,0,?,SYSTIMESTAMP,SYSTIMESTAMP
                FROM ruleset_version v JOIN ruleset r ON r.ruleset_id=v.ruleset_id WHERE v.ruleset_version_id=?
                """)) {
            q.setString(1,"PHASE-"+UUID.randomUUID());q.setString(2,"PRE/POST source inherited from version "+sourceVersion);
            q.setString(3,post);q.setString(4,SqlChecksum.sha256(post));
            q.setInt(5,preview.statementCount());q.setString(6,actor);q.setLong(7,targetVersion);
            if(q.executeUpdate()!=1)throw new SQLException("Target version missing while copying POST source");
        }
    }
    private static boolean hasStructuralSql(String sql) {
        return sql!=null&&Pattern.compile("(?i)\\b(?:ALTER|CREATE|DROP|TRUNCATE|RENAME|COMMENT|GRANT|REVOKE|AUDIT|NOAUDIT|FLASHBACK|PURGE)\\b").matcher(sql).find();
    }
}
