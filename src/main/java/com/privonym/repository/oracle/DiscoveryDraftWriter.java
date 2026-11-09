package com.privonym.repository.oracle;

import com.privonym.application.service.DiscoveryCreationResult;
import com.privonym.application.service.DiscoveryRulesetContext;
import com.privonym.application.service.DiscoverySelection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;

/** All changes belong to the caller transaction; published rules and jobs are never updated. */
final class DiscoveryDraftWriter {
    private DiscoveryDraftWriter() { }

    static DiscoveryRulesetContext context(Connection c,long dataSourceId)throws SQLException {
        try(PreparedStatement query=c.prepareStatement("""
                SELECT r.ruleset_id,r.code,r.name,NVL(MAX(v.version_number),0),
                       MAX(CASE WHEN v.status='DRAFT' THEN v.ruleset_version_id END)
                  FROM ruleset r LEFT JOIN ruleset_version v ON v.ruleset_id=r.ruleset_id
                 WHERE r.data_source_id=? AND (NOT EXISTS(SELECT 1 FROM connection_ruleset_binding b WHERE b.data_source_id=r.data_source_id) OR EXISTS(SELECT 1 FROM connection_ruleset_binding b WHERE b.data_source_id=r.data_source_id AND b.ruleset_id=r.ruleset_id)) GROUP BY r.ruleset_id,r.code,r.name
                """)) {
            query.setLong(1,dataSourceId);
            try(ResultSet row=query.executeQuery()) {
                if(!row.next())return null;
                long draft=row.getLong(5);Long draftId=row.wasNull()?null:draft;
                var result=new DiscoveryRulesetContext(row.getLong(1),row.getString(2),row.getString(3),row.getInt(4),Math.addExact(row.getInt(4),1),draftId);
                if(row.next())throw new IllegalStateException("Several historical Rule Sets exist for this connection; designate its current Rule Set before Discovery");
                return result;
            }
        }
    }

    static DiscoveryCreationResult create(Connection c,long rulesetId,List<DiscoverySelection> selections,String user)throws SQLException {
        var distinct=new HashSet<Long>();
        for(var selection:selections)
            if(!distinct.add(selection.columnId()))throw new IllegalArgumentException("Duplicate selected column");
        long dataSourceId;
        try(PreparedStatement query=c.prepareStatement("SELECT data_source_id FROM ruleset WHERE ruleset_id=?")) {
            query.setLong(1,rulesetId);
            try(ResultSet row=query.executeQuery()){if(!row.next())throw new IllegalArgumentException("Rule Set not found");dataSourceId=row.getLong(1);}
        }
        try(PreparedStatement lock=c.prepareStatement("SELECT data_source_id FROM data_source WHERE data_source_id=? FOR UPDATE")) {
            lock.setLong(1,dataSourceId);try(ResultSet row=lock.executeQuery()){if(!row.next())throw new IllegalArgumentException("Connection not found");}
        }
        try(PreparedStatement lock=c.prepareStatement("SELECT ruleset_id FROM ruleset WHERE ruleset_id=? AND data_source_id=? FOR UPDATE")) {
            lock.setLong(1,rulesetId);lock.setLong(2,dataSourceId);
            try(ResultSet row=lock.executeQuery()){if(!row.next())throw new IllegalArgumentException("Rule Set changed; analyze again");}
        }
        DiscoveryRulesetContext state=context(c,dataSourceId);
        if(state==null||state.rulesetId()!=rulesetId)throw new IllegalArgumentException("Rule Set changed; analyze again");
        if(state.draftVersionId()!=null)throw new IllegalStateException("This Rule Set already has a DRAFT version. Open it before creating another version.");
        long sourceId;
        try(PreparedStatement query=c.prepareStatement("SELECT ruleset_version_id FROM ruleset_version WHERE ruleset_id=? AND status='PUBLISHED' ORDER BY version_number DESC FETCH FIRST 1 ROW ONLY")) {
            query.setLong(1,rulesetId);
            try(ResultSet row=query.executeQuery()){if(!row.next())throw new IllegalStateException("A published version is required to create a Discovery draft");sourceId=row.getLong(1);}
        }
        try(PreparedStatement insert=c.prepareStatement("INSERT INTO ruleset_version(ruleset_id,version_number,status,description,created_by) VALUES(?,?,'DRAFT',?,?)")) {
            insert.setLong(1,rulesetId);insert.setInt(2,state.nextVersion());
            insert.setString(3,"Discovery draft from published version "+sourceId);insert.setString(4,user);insert.executeUpdate();
        }
        long draftId;
        try(PreparedStatement query=c.prepareStatement("SELECT ruleset_version_id FROM ruleset_version WHERE ruleset_id=? AND version_number=?")) {
            query.setLong(1,rulesetId);query.setInt(2,state.nextVersion());
            try(ResultSet row=query.executeQuery()){if(!row.next())throw new SQLException("Draft version was not saved");draftId=row.getLong(1);}
        }
        copy(c,"""
                INSERT INTO masking_rule(ruleset_version_id,catalog_column_id,algorithm_version_id,execution_order,filter_expression,enabled)
                SELECT ?,catalog_column_id,algorithm_version_id,execution_order,filter_expression,enabled
                  FROM masking_rule WHERE ruleset_version_id=?
                """,draftId,sourceId);
        copy(c,"""
                INSERT INTO rule_parameter(rule_id,parameter_definition_id,parameter_value)
                SELECT target_rule.rule_id,p.parameter_definition_id,p.parameter_value
                FROM masking_rule source_rule JOIN rule_parameter p ON p.rule_id=source_rule.rule_id
                JOIN masking_rule target_rule ON target_rule.ruleset_version_id=?
                 AND target_rule.catalog_column_id=source_rule.catalog_column_id
                WHERE source_rule.ruleset_version_id=?
                """,draftId,sourceId);
        copy(c,"""
                INSERT INTO table_masking_rule(ruleset_version_id,catalog_table_id,algorithm_version_id,execution_order,filter_expression,enabled)
                SELECT ?,catalog_table_id,algorithm_version_id,execution_order,filter_expression,enabled
                  FROM table_masking_rule WHERE ruleset_version_id=?
                """,draftId,sourceId);
        int order;
        try(PreparedStatement query=c.prepareStatement("SELECT NVL(MAX(execution_order),0) FROM masking_rule WHERE ruleset_version_id=?")) {
            query.setLong(1,draftId);try(ResultSet row=query.executeQuery()){row.next();order=row.getInt(1);}
        }
        for(var selection:selections) {
            long algorithm=algorithmForColumn(c,dataSourceId,selection);
            Long ruleId=null;String existingCode=null;
            try(PreparedStatement query=c.prepareStatement("SELECT mr.rule_id,a.code FROM masking_rule mr JOIN algorithm_version av ON av.algorithm_version_id=mr.algorithm_version_id JOIN algorithm a ON a.algorithm_id=av.algorithm_id WHERE mr.ruleset_version_id=? AND mr.catalog_column_id=?")) {
                query.setLong(1,draftId);query.setLong(2,selection.columnId());
                try(ResultSet row=query.executeQuery()){if(row.next()){ruleId=row.getLong(1);existingCode=row.getString(2);}}
            }
            if(ruleId==null) {
                order=Math.addExact(order,10);
                try(PreparedStatement insert=c.prepareStatement("INSERT INTO masking_rule(ruleset_version_id,catalog_column_id,algorithm_version_id,execution_order,enabled) VALUES(?,?,?,?,'Y')")) {
                    insert.setLong(1,draftId);insert.setLong(2,selection.columnId());insert.setLong(3,algorithm);insert.setInt(4,order);insert.executeUpdate();
                }
            } else if(!selection.algorithmCode().equals(existingCode)) {
                try(PreparedStatement delete=c.prepareStatement("DELETE FROM rule_parameter WHERE rule_id=?")) {
                    delete.setLong(1,ruleId);delete.executeUpdate();
                }
                try(PreparedStatement update=c.prepareStatement("UPDATE masking_rule SET algorithm_version_id=? WHERE rule_id=? AND ruleset_version_id=?")) {
                    update.setLong(1,algorithm);update.setLong(2,ruleId);update.setLong(3,draftId);update.executeUpdate();
                }
            }
        }
        ImportedPhaseScripts.copy(c,sourceId,draftId,user);
        return new DiscoveryCreationResult(rulesetId,draftId,state.code(),selections.size(),state.nextVersion(),false);
    }

    private static long algorithmForColumn(Connection c,long dataSourceId,DiscoverySelection selection)throws SQLException {
        try(PreparedStatement query=c.prepareStatement("""
                SELECT av.algorithm_version_id FROM algorithm a
                JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id
                JOIN algorithm_implementation ai ON ai.algorithm_version_id=av.algorithm_version_id
                JOIN connector conn ON conn.technology_code=ai.technology_code
                JOIN data_source ds ON ds.connector_id=conn.connector_id
                JOIN catalog_schema cs ON cs.data_source_id=ds.data_source_id
                JOIN catalog_table ct ON ct.catalog_schema_id=cs.catalog_schema_id
                JOIN catalog_column cc ON cc.catalog_table_id=ct.catalog_table_id
                WHERE ds.data_source_id=? AND cc.catalog_column_id=? AND a.code=?
                 AND a.status='ACTIVE' AND av.status='PUBLISHED' AND ai.status='PUBLISHED'
                 AND av.version_number=(SELECT MAX(v.version_number) FROM algorithm_version v
                      WHERE v.algorithm_id=a.algorithm_id AND v.status='PUBLISHED')
                """)) {
            query.setLong(1,dataSourceId);query.setLong(2,selection.columnId());query.setString(3,selection.algorithmCode());
            try(ResultSet row=query.executeQuery()){if(!row.next())throw new IllegalArgumentException("Selected column or target algorithm is unavailable");return row.getLong(1);}
        }
    }
    private static void copy(Connection c,String sql,long target,long source)throws SQLException {
        try(PreparedStatement query=c.prepareStatement(sql)){query.setLong(1,target);query.setLong(2,source);query.executeUpdate();}
    }
}
