package com.privonym.application.service;

/** Signals an operator-requested interruption of an active Job execution. */
public final class JobExecutionCancelledException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JobExecutionCancelledException(long jobId) {
        super("Job " + jobId + " execution was cancelled by an operator");
    }

    public JobExecutionCancelledException(long jobId, Throwable cause) {
        super("Job " + jobId + " execution was cancelled by an operator", cause);
    }
}
