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
 static void verifyExactImportAsset(Path directory)throws Exception {
  Path asset=directory.resolve("V2_026_exact_oracle_import_oracle.sql");ExactOracleImportCatalogInstaller.verifyAsset(asset);
  Path temporary=Files.createTempDirectory("privonym-import-asset-");Path modified=temporary.resolve(asset.getFileName());
  try{Files.writeString(modified,Files.readString(asset)+"\n-- changed asset");boolean rejected=false;try{ExactOracleImportCatalogInstaller.verifyAsset(modified);}catch(IllegalArgumentException expected){rejected=true;}if(!rejected)throw new AssertionError("Changed catalog migration must not be activated during a running Job");}
  finally{Files.deleteIfExists(modified);Files.deleteIfExists(temporary);}
  System.out.println("Exact import catalog asset passed: sealed migration accepted; modified asset rejected before connection/mutation.");
 }

}
