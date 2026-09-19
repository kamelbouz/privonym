package com.privonym.domain.execution;

import com.privonym.domain.shared.DomainException;

public final class RestartNotSafe extends DomainException {
    private static final long serialVersionUID = 1L;

    public RestartNotSafe() {
        super("Restart requires a restored target, no committed transformation, "
                + "or proof that all committed transformations are idempotent");
    }
}
