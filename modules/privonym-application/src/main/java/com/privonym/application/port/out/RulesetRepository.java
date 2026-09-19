package com.privonym.application.port.out;

import com.privonym.domain.policy.RulesetVersionId;

public interface RulesetRepository {
    void requirePublished(RulesetVersionId id);
}
