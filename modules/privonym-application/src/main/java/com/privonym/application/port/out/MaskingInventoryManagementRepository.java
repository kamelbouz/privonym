package com.privonym.application.port.out;

import com.privonym.application.service.InventoryMutationResult;
import com.privonym.application.service.MaskingAlgorithmOption;
import com.privonym.application.service.AlgorithmCreationResult;
import java.util.List;

public interface MaskingInventoryManagementRepository {
    List<MaskingAlgorithmOption> listAlgorithms();
    InventoryMutationResult resolve(long rulesetVersionId, long mappingId,
            String algorithmCode, String parameters);
    InventoryMutationResult resolveAll(long rulesetVersionId);
    InventoryMutationResult accept(long rulesetVersionId, long mappingId);
    InventoryMutationResult publish(long rulesetVersionId);
    AlgorithmCreationResult cloneAlgorithm(String templateCode, String code, String name,
            List<String> lookupValues, String createdBy);
}
