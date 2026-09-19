package com.privonym.domain.job;

import com.privonym.domain.shared.DomainException;

public final class InvalidJobTransition extends DomainException {
    private static final long serialVersionUID = 1L;

    public InvalidJobTransition(JobStatus source, JobStatus target) {
        super("Invalid job transition: " + source + " -> " + target);
    }
}
