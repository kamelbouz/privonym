package com.privonym.application.service;

import com.privonym.application.port.in.ManageMaskingInventoryUseCase;
import com.privonym.application.port.out.MaskingInventoryManagementRepository;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class MaskingInventoryManagementService implements ManageMaskingInventoryUseCase {
    private final MaskingInventoryManagementRepository repository;
    private final String createdBy;

    public MaskingInventoryManagementService(MaskingInventoryManagementRepository repository) {
        this(repository,"PRIVONYM");
    }

    public MaskingInventoryManagementService(MaskingInventoryManagementRepository repository,String createdBy) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.createdBy=Objects.requireNonNull(createdBy,"createdBy");
    }

    @Override
    public List<MaskingAlgorithmOption> listAlgorithms() {
        return repository.listAlgorithms();
    }

    @Override
    public InventoryMutationResult resolveMapping(long rulesetVersionId, long mappingId,
            String algorithmCode, String parameters) {
        validateIds(rulesetVersionId, mappingId);
        String code = algorithmCode == null ? "" : algorithmCode.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_]{1,99}")) {
            throw new IllegalArgumentException("A valid algorithm code is required");
        }
        String values = parameters == null ? "" : parameters.trim();
        if (values.length() > 4000) throw new IllegalArgumentException("Algorithm parameters are too long");
        return repository.resolve(rulesetVersionId, mappingId, code, values);
    }

    @Override
    public InventoryMutationResult acceptMapping(long rulesetVersionId, long mappingId) {
        validateIds(rulesetVersionId, mappingId);
        return repository.accept(rulesetVersionId, mappingId);
    }

    @Override
    public InventoryMutationResult resolveAllMappings(long rulesetVersionId) {
        if (rulesetVersionId <= 0) throw new IllegalArgumentException("Invalid inventory version");
        return repository.resolveAll(rulesetVersionId);
    }

    @Override
    public InventoryMutationResult publish(long rulesetVersionId) {
        if (rulesetVersionId <= 0) throw new IllegalArgumentException("Invalid inventory version");
        return repository.publish(rulesetVersionId);
    }

    private static void validateIds(long rulesetVersionId, long mappingId) {
        if (rulesetVersionId <= 0 || mappingId <= 0) {
            throw new IllegalArgumentException("Invalid inventory mapping");
        }
    }

    @Override public AlgorithmCreationResult cloneAlgorithm(String templateCode,String code,String name,List<String> lookupValues){
        String template=normalizeCode(templateCode,"Template code");
        String normalized=normalizeCode(code,"Algorithm code");
        if(name==null||name.isBlank())throw new IllegalArgumentException("Algorithm name is required");
        List<String> values=lookupValues==null?List.of():lookupValues.stream().map(String::trim).filter(v->!v.isBlank()).distinct().toList();
        return repository.cloneAlgorithm(template,normalized,name.trim(),values,createdBy);
    }

    private static String normalizeCode(String value,String label){String normalized=value==null?"":value.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]+","_");if(!normalized.matches("[A-Z][A-Z0-9_]{2,99}"))throw new IllegalArgumentException(label+" must contain 3 to 100 letters, digits or underscores");return normalized;}
}
