package com.privonym.repository.oracle;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
final class WorkerShutdownSmokeCheck {
 private WorkerShutdownSmokeCheck() { }
 static void verify()throws Exception {
  var shutdown=OracleParallelPlanExecutor.class.getDeclaredMethod("shutdownAndAwait",ExecutorService.class);shutdown.setAccessible(true);
  for(boolean interruptedCaller:new boolean[]{false,true}) {
   ExecutorService pool=Executors.newSingleThreadExecutor();CountDownLatch cleaning=new CountDownLatch(1);AtomicBoolean journaled=new AtomicBoolean();
   try(OracleParallelPlanExecutor executor=new OracleParallelPlanExecutor("jdbc:synthetic","test",new char[0],"jdbc:postgresql:synthetic","test",new char[0],1,"DIRECT",1000,1000)) {
    pool.submit(()->{cleaning.countDown();try{Thread.sleep(250);journaled.set(!Thread.currentThread().isInterrupted());}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
    if(!cleaning.await(2,TimeUnit.SECONDS))throw new AssertionError("Worker did not start cleanup");
    if(interruptedCaller)Thread.currentThread().interrupt();
    shutdown.invoke(executor,pool);
    if(!journaled.get()||!pool.isTerminated())throw new AssertionError("Worker rollback/journal cleanup was interrupted");
    if(interruptedCaller&&!Thread.currentThread().isInterrupted())throw new AssertionError("Caller interrupt must be preserved");
   }finally{Thread.interrupted();pool.shutdownNow();}
  }
  System.out.println("Worker shutdown checks passed: rollback/journal cleanup completes, caller interruption is preserved.");
 }
}
