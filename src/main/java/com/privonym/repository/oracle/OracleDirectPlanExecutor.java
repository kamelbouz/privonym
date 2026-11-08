package com.privonym.repository.oracle;

import com.privonym.adapter.oracle.SqlChecksum;
import com.privonym.application.port.out.TargetPlanExecutor;
import com.privonym.application.service.ExecutablePlan;
import com.privonym.application.service.ExecutableStep;
import com.privonym.application.service.ExecutionOutcome;
import com.privonym.application.service.StepExecutionResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class OracleDirectPlanExecutor implements TargetPlanExecutor {
    private final Connection target;

    public OracleDirectPlanExecutor(Connection target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public ExecutionOutcome execute(ExecutablePlan plan) {
        List<StepExecutionResult> results = new ArrayList<>();
        boolean commitAttempted=false;
        try {
            for (ExecutableStep step : plan.steps()) {
                validate(step);
                try (PreparedStatement statement = target.prepareStatement(step.generatedCode())) {
                    results.add(new StepExecutionResult(
                            step.jobStepId(), statement.executeUpdate()));
                }
            }
            commitAttempted=true;
            target.commit();
            return new ExecutionOutcome(results);
        } catch (SQLException | RuntimeException error) {
            boolean rolledBack=true;
            try {
                target.rollback();
            } catch (SQLException rollbackError) {
                rolledBack=false;error.addSuppressed(rollbackError);
            }
            if(commitAttempted||!rolledBack)throw new TargetOutcomeUncertainException("DIRECT target outcome is uncertain; verify the target before retry",error);
            throw new IllegalStateException(
                    "DIRECT target execution was rolled back: " + error.getMessage(), error);
        }
    }

    private static void validate(ExecutableStep step) {
        String code = step.generatedCode().strip();
        if (!SqlChecksum.sha256(step.generatedCode()).equals(step.checksum().value())) {
            throw new IllegalStateException("SQL checksum mismatch at step " + step.stepNumber());
        }
        if (!code.toUpperCase(Locale.ROOT).startsWith("UPDATE ") || code.contains(";")) {
            throw new IllegalStateException(
                    "DIRECT mode accepts one generated UPDATE statement per step");
        }
    }
}
