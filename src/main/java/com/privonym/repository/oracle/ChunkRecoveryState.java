package com.privonym.repository.oracle;
/** A manual FAILED label without terminal evidence is not a confirmed rollback. */
final class ChunkRecoveryState {
 private ChunkRecoveryState() { }
 static boolean resolved(String status,String error,boolean ended) {
  return "SUCCEEDED".equals(status)||"FAILED".equals(status)&&ended&&error!=null&&!error.isBlank()
   ||"CANCELLED".equals(status)&&error!=null&&error.startsWith("PRIVONYM-ROLLBACK-CONFIRMED:");
 }
 static String unresolvedSql(String prefix){String p=prefix.isEmpty()?"":prefix+".";return "("+p+"status NOT IN ('SUCCEEDED','FAILED') AND NOT("+p+"status='CANCELLED' AND COALESCE("+p+"error_message,'UNKNOWN') LIKE 'PRIVONYM-ROLLBACK-CONFIRMED:%') OR "+p+"status='FAILED' AND ("+p+"ended_at IS NULL OR "+p+"error_message IS NULL))";}
}
