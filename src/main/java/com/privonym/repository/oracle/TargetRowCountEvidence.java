package com.privonym.repository.oracle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Population evidence captured before mutation; never reconstructed from a partially committed retry. */
final class TargetRowCountEvidence {
    static final String METRIC="TARGET_ROWS_EXPECTED";
    private TargetRowCountEvidence() { }

    static void capture(Connection repository,Connection target,long stepId,String table,
            String operation,long affectedExpected,boolean filtered,boolean hasCommittedChunks)throws SQLException {
        if(hasCommittedChunks)return;
        long expectedAfter;
        if(operation.equals("TRUNCATE"))expectedAfter=0;
        else {
            long before=affectedExpected;
            if(filtered) {
                try(PreparedStatement query=target.prepareStatement("SELECT COUNT(*) FROM "+table);
                        ResultSet rows=query.executeQuery()) {
                    if(!rows.next())throw new SQLException("Missing target population count");before=rows.getLong(1);
                }
            }
            expectedAfter=operation.equals("DELETE")?Math.subtractExact(before,affectedExpected):before;
            if(expectedAfter<0)throw new SQLException("Target population changed while preparing execution");
        }
        try(PreparedStatement delete=repository.prepareStatement("DELETE FROM job_metric WHERE job_step_id=? AND metric_name='TARGET_ROWS_EXPECTED'")) {
            delete.setLong(1,stepId);delete.executeUpdate();
        }
        try(PreparedStatement insert=repository.prepareStatement("""
                INSERT INTO job_metric(job_id,job_step_id,metric_name,metric_value,metric_unit)
                SELECT job_id,?,'TARGET_ROWS_EXPECTED',?,'ROWS' FROM job_step WHERE job_step_id=?
                """)) {
            insert.setLong(1,stepId);insert.setLong(2,expectedAfter);insert.setLong(3,stepId);
            if(insert.executeUpdate()!=1)throw new SQLException("Execution step was not found for target count evidence");
        }
    }
}
