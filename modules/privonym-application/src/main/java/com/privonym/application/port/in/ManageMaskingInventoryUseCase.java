package com.privonym.application.port.in;

import com.privonym.application.service.InventoryMutationResult;
import com.privonym.application.service.MaskingAlgorithmOption;
import com.privonym.application.service.AlgorithmCreationResult;
import java.util.List;

public interface ManageMaskingInventoryUseCase {
    List<MaskingAlgorithmOption> listAlgorithms();
    InventoryMutationResult resolveMapping(long rulesetVersionId, long mappingId,
            String algorithmCode, String parameters);
    InventoryMutationResult resolveAllMappings(long rulesetVersionId);
    InventoryMutationResult acceptMapping(long rulesetVersionId, long mappingId);
    InventoryMutationResult publish(long rulesetVersionId);
    AlgorithmCreationResult cloneAlgorithm(String templateCode, String code, String name,
            List<String> lookupValues);
}
