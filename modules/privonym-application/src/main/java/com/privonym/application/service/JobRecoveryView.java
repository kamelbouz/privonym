package com.privonym.application.service;
import java.util.List;
public record JobRecoveryView(long jobId,String reference,String status,boolean canReconcile,boolean canConfirmStopped,
        List<Step> steps,List<Chunk> chunks) {
    public JobRecoveryView {steps=List.copyOf(steps);chunks=List.copyOf(chunks);}
    public record Step(long stepId,String table,String requestedMode,String effectiveMode,String reason) { }
    public record Chunk(long chunkId,long stepId,int number,String status,long rows,String detail,boolean unresolved) { }
}
