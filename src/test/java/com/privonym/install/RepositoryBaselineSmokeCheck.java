package com.privonym.install;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
final class RepositoryBaselineSmokeCheck {
 private RepositoryBaselineSmokeCheck() { }
 static void verify()throws Exception {
  Path f=Files.createTempFile("privonym-baseline-",".sql");
  try {
   Files.writeString(f,"SELECT 1 FROM dual;\r\n",StandardCharsets.UTF_8);String windows=OracleRepositoryUpgrader.sha256(f);
   Files.writeString(f,"SELECT 1 FROM dual;\n",StandardCharsets.UTF_8);
   if(!OracleRepositoryUpgrader.matchesChecksum(f,windows))throw new AssertionError("Git line endings must not break history");
   Files.writeString(f,"SELECT 2 FROM dual;\n",StandardCharsets.UTF_8);
   if(OracleRepositoryUpgrader.matchesChecksum(f,windows))throw new AssertionError("SQL content changes must remain rejected");
  }finally{Files.deleteIfExists(f);}
  System.out.println("Lot 3 baseline checks passed: Windows/Linux line endings accepted; modified SQL rejected.");
 }
}
