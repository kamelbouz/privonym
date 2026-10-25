package com.privonym.repository.oracle;

/** Snapshot of current checkpoints, without summing previous execution attempts. */
final class ExecutionFailureSummary {
    private ExecutionFailureSummary() { }

    static final String SQL = """
            SELECT j.job_reference,j.status,j.started_at,NVL(j.ended_at,SYSTIMESTAMP) report_end_at,
                   (SELECT COUNT(*) FROM job_step s
                     WHERE s.job_id=j.job_id AND s.step_type='ANONYMIZE_TABLE'
                       AND (s.status='SUCCEEDED' OR
                         (EXISTS(SELECT 1 FROM job_chunk c WHERE c.job_step_id=s.job_step_id)
                          AND NOT EXISTS(SELECT 1 FROM job_chunk c
                                          WHERE c.job_step_id=s.job_step_id AND c.status<>'SUCCEEDED')
                          AND (SELECT SUM(c.rows_processed) FROM job_chunk c
                                WHERE c.job_step_id=s.job_step_id AND c.status='SUCCEEDED')
                              =(SELECT MAX(m.metric_value) FROM job_metric m
                                 WHERE m.job_step_id=s.job_step_id AND m.metric_name='ROWS_EXPECTED'))))
                       succeeded_steps,
                   (SELECT NVL(SUM(
                      CASE WHEN EXISTS(SELECT 1 FROM job_chunk c WHERE c.job_step_id=s.job_step_id)
                           THEN (SELECT NVL(SUM(c.rows_processed),0) FROM job_chunk c
                                  WHERE c.job_step_id=s.job_step_id AND c.status='SUCCEEDED')
                           ELSE NVL((SELECT a.rows_processed FROM job_step_attempt a
                                      WHERE a.job_step_id=s.job_step_id
                                        AND a.attempt_number=(SELECT MAX(x.attempt_number)
                                              FROM job_step_attempt x WHERE x.job_step_id=s.job_step_id)),0)
                      END),0)
                      FROM job_step s WHERE s.job_id=j.job_id AND s.step_type='ANONYMIZE_TABLE')
                       rows_processed
              FROM anonymization_job j WHERE j.job_id=?
            """;
}
