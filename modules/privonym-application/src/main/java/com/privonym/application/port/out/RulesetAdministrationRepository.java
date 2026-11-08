package com.privonym.application.port.out;

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

public interface RulesetAdministrationRepository {
    List<RulesetSummary> list();
    RulesetDetails get(long rulesetId, Long versionId);
    RulesetVersionView createDraft(long rulesetId, long sourceVersionId, String createdBy);
    RulesetCloneResult cloneRuleset(long sourceRulesetId, long targetDataSourceId,
            String name, String createdBy);
    void deleteVersion(long rulesetId, long versionId);
    void delete(long rulesetId);
    JobCreationResult createJob(long rulesetId, long versionId,
            String reference, int parallelDegree, String executionMode,
            int chunkSize, long chunkThreshold, String requestedBy);
    List<RulesetCatalogColumn> listCatalog(long rulesetId, long versionId);
    int saveInventory(long rulesetId, long versionId, List<RulesetInventorySelection> selections);
    void deleteJob(String reference);
    JobUpdateResult updateJob(String currentReference, String newReference, long rulesetId,
            int parallelDegree, String executionMode, int chunkSize, long chunkThreshold);
    ExecuteJobResult executeJob(String reference);
    ExecuteJobResult executeJob(long jobId);
    void cancelJob(long jobId);
    default com.privonym.application.service.JobRecoveryView recovery(long jobId){throw new UnsupportedOperationException("Job recovery unavailable");}
    default void reconcile(long jobId,long chunkId,String decision,long rows,String note,String confirmation,String actor){throw new UnsupportedOperationException("Job recovery unavailable");}
    void saveJobScript(String reference, String phase, String fileName, String content);
    RulesetSqlExport exportSql(long rulesetId, long versionId);
    default long currentRulesetId(long source) {var candidates=list().stream().filter(r->r.dataSourceId()==source).toList();if(candidates.size()>1)throw new IllegalStateException("Designate the current Rule Set for this connection");return candidates.isEmpty()?0:candidates.get(0).id();}
}
