package com.privonym.application.service;

public record AlgorithmCreationResult(long algorithmId, long versionId, String code,
        String name, String implementationType, String status, int lookupValueCount) { }
