package com.privonym.application.service;

import com.privonym.application.port.in.RulesetAdministrationUseCase;
import com.privonym.application.port.out.RulesetAdministrationRepository;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class RulesetAdministrationService implements RulesetAdministrationUseCase {
    private final RulesetAdministrationRepository repository;
    private final String requestedBy;

    public RulesetAdministrationService(RulesetAdministrationRepository repository,
            String requestedBy) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
    }

    @Override
    public List<RulesetSummary> listRulesets() {
        return repository.list();
    }

    @Override
    public RulesetDetails getRuleset(long rulesetId, Long versionId) {
        if (rulesetId <= 0 || versionId != null && versionId <= 0) {
            throw new IllegalArgumentException("Invalid ruleset or version identifier");
        }
        return repository.get(rulesetId, versionId);
    }

    @Override
    public RulesetVersionView createDraft(long rulesetId, long sourceVersionId) {
        if (rulesetId <= 0 || sourceVersionId <= 0) {
            throw new IllegalArgumentException("Invalid ruleset or version identifier");
        }
        return repository.createDraft(rulesetId, sourceVersionId, requestedBy);
    }

    @Override
    public RulesetCloneResult cloneRuleset(long sourceRulesetId, long targetDataSourceId,
            String name) {
        if (sourceRulesetId <= 0 || targetDataSourceId <= 0) {
            throw new IllegalArgumentException("A source Rule Set and target connection are required");
        }
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty() || normalizedName.length() > 200) {
            throw new IllegalArgumentException("Rule Set name must contain 1 to 200 characters");
        }
        return repository.cloneRuleset(sourceRulesetId, targetDataSourceId,
                normalizedName, requestedBy);
    }

    @Override
    public void deleteVersion(long rulesetId, long versionId) {
        if (rulesetId <= 0 || versionId <= 0) {
            throw new IllegalArgumentException("Invalid ruleset or version identifier");
        }
        repository.deleteVersion(rulesetId, versionId);
    }

    @Override
    public void deleteRuleset(long rulesetId) {
        if (rulesetId <= 0) throw new IllegalArgumentException("Invalid ruleset identifier");
        repository.delete(rulesetId);
    }

    @Override
    public JobCreationResult createJob(long rulesetId, long versionId,
            String reference, int parallelDegree, String executionMode,
            int chunkSize, long chunkThreshold) {
        String normalized = reference == null ? ""
                : reference.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_-]{2,99}")) {
            throw new IllegalArgumentException(
                    "Job reference must contain 3 to 100 letters, digits, hyphens or underscores");
        }
        if (parallelDegree < 1 || parallelDegree > 32) {
            throw new IllegalArgumentException("Worker threads must be between 1 and 32");
        }
        String normalizedMode = executionMode == null ? ""
                : executionMode.trim().toUpperCase(Locale.ROOT);
        if (!List.of("AUTO", "DIRECT", "CHUNKED").contains(normalizedMode)) {
            throw new IllegalArgumentException("Execution mode must be AUTO, DIRECT or CHUNKED");
        }
        if (chunkSize < 1_000 || chunkSize > 1_000_000) {
            throw new IllegalArgumentException("Chunk size must be between 1,000 and 1,000,000 rows");
        }
        if (chunkThreshold < chunkSize || chunkThreshold > 999_999_999_999_999_999L) {
            throw new IllegalArgumentException("Chunk threshold must be greater than or equal to chunk size");
        }
        return repository.createJob(rulesetId, versionId, normalized,
                parallelDegree, normalizedMode, chunkSize, chunkThreshold, requestedBy);
    }

    @Override public List<RulesetCatalogColumn> listCatalog(long rulesetId,long versionId){return repository.listCatalog(rulesetId,versionId);}
    @Override public int saveInventory(long rulesetId,long versionId,List<RulesetInventorySelection> selections){return repository.saveInventory(rulesetId,versionId,selections);}
    @Override public void deleteJob(String reference) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("Job reference is required");
        repository.deleteJob(reference.trim().toUpperCase(Locale.ROOT));
    }
    @Override public JobUpdateResult updateJob(String currentReference, String newReference,
            long rulesetId, int parallelDegree, String executionMode,
            int chunkSize, long chunkThreshold) {
        if (currentReference == null || currentReference.isBlank()) {
            throw new IllegalArgumentException("Current Job reference is required");
        }
        String normalized = newReference == null ? ""
                : newReference.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_-]{2,99}")) {
            throw new IllegalArgumentException(
                    "Job reference must contain 3 to 100 letters, digits, hyphens or underscores");
        }
        if (rulesetId <= 0) throw new IllegalArgumentException("Rule Set is required");
        if (parallelDegree < 1 || parallelDegree > 32) {
            throw new IllegalArgumentException("Worker threads must be between 1 and 32");
        }
        String normalizedMode = executionMode == null ? ""
                : executionMode.trim().toUpperCase(Locale.ROOT);
        if (!List.of("AUTO", "DIRECT", "CHUNKED").contains(normalizedMode)) {
            throw new IllegalArgumentException("Execution mode must be AUTO, DIRECT or CHUNKED");
        }
        if (chunkSize < 1_000 || chunkSize > 1_000_000) {
            throw new IllegalArgumentException("Chunk size must be between 1,000 and 1,000,000 rows");
        }
        if (chunkThreshold < chunkSize || chunkThreshold > 999_999_999_999_999_999L) {
            throw new IllegalArgumentException(
                    "Chunk threshold must be greater than or equal to chunk size");
        }
        return repository.updateJob(currentReference.trim().toUpperCase(Locale.ROOT),
                normalized, rulesetId, parallelDegree, normalizedMode,
                chunkSize, chunkThreshold);
    }
    @Override public ExecuteJobResult executeJob(String reference) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("Job reference is required");
        return repository.executeJob(reference.trim().toUpperCase(Locale.ROOT));
    }
    @Override public ExecuteJobResult executeJob(long jobId) {
        if (jobId <= 0) throw new IllegalArgumentException("Job identifier must be positive");
        return repository.executeJob(jobId);
    }

    @Override public void saveJobScript(String reference, String phase, String fileName, String content) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("Job reference is required");
        String normalizedPhase = phase == null ? "" : phase.trim().toUpperCase(Locale.ROOT);
        if (!normalizedPhase.equals("PRE") && !normalizedPhase.equals("POST"))
            throw new IllegalArgumentException("Script phase must be PRE or POST");
        String normalizedName = fileName == null ? "" : fileName.trim();
        if (!normalizedName.isEmpty() && !normalizedName.toLowerCase(Locale.ROOT).endsWith(".sql"))
            throw new IllegalArgumentException("Only .sql scripts are accepted");
        if (normalizedName.length() > 255) throw new IllegalArgumentException("Script file name is too long");
        String sql = content == null ? "" : content;
        if (sql.length() > 1_000_000) throw new IllegalArgumentException("SQL script exceeds the 1 MB limit");
        repository.saveJobScript(reference.trim().toUpperCase(Locale.ROOT), normalizedPhase,
                normalizedName, sql);
    }
    @Override public void cancelJob(long jobId) {
        if (jobId <= 0) throw new IllegalArgumentException("Job identifier must be positive");
        repository.cancelJob(jobId);
    }
    @Override public JobRecoveryView recovery(long jobId){if(jobId<=0)throw new IllegalArgumentException("Invalid Job");return repository.recovery(jobId);}
    @Override public void reconcile(long jobId,long chunkId,String decision,long rows,String note,String confirmation,String actor){
        if(jobId<=0)throw new IllegalArgumentException("Invalid Job");repository.reconcile(jobId,chunkId,decision,rows,note,confirmation,actor);
    }
    @Override public RulesetSqlExport exportSql(long rulesetId, long versionId) {
        if (rulesetId <= 0 || versionId <= 0) {
            throw new IllegalArgumentException("Invalid ruleset or version identifier");
        }
        return repository.exportSql(rulesetId, versionId);
    }
    @Override public long currentRulesetId(long source){return repository.currentRulesetId(source);}
}
