package com.privonym.application.port.in;

import com.privonym.application.service.JobCreationResult;
import com.privonym.application.service.JobUpdateResult;
import com.privonym.application.service.RulesetDetails;
import com.privonym.application.service.RulesetSummary;
import com.privonym.application.service.RulesetCatalogColumn;
import com.privonym.application.service.RulesetInventorySelection;
import com.privonym.application.service.RulesetVersionView;
import com.privonym.application.service.ExecuteJobResult;
import com.privonym.application.service.RulesetSqlExport;
import com.privonym.application.service.RulesetCloneResult;
import java.util.List;

public interface RulesetAdministrationUseCase {
    List<RulesetSummary> listRulesets();
    RulesetDetails getRuleset(long rulesetId, Long versionId);
    RulesetVersionView createDraft(long rulesetId, long sourceVersionId);
    RulesetCloneResult cloneRuleset(long sourceRulesetId, long targetDataSourceId, String name);
    void deleteVersion(long rulesetId, long versionId);
    void deleteRuleset(long rulesetId);
    JobCreationResult createJob(long rulesetId, long versionId,
            String reference, int parallelDegree, String executionMode,
            int chunkSize, long chunkThreshold);
    List<RulesetCatalogColumn> listCatalog(long rulesetId, long versionId);
    int saveInventory(long rulesetId, long versionId, List<RulesetInventorySelection> selections);
    void deleteJob(String reference);
    JobUpdateResult updateJob(String currentReference, String newReference, long rulesetId,
            int parallelDegree, String executionMode, int chunkSize, long chunkThreshold);
    ExecuteJobResult executeJob(String reference);
    ExecuteJobResult executeJob(long jobId);
    void cancelJob(long jobId);
    void saveJobScript(String reference, String phase, String fileName, String content);
    RulesetSqlExport exportSql(long rulesetId, long versionId);
}
