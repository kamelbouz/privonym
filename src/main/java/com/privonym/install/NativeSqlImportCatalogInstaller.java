package com.privonym.install;
import java.nio.file.*;
import java.sql.*;
import java.util.Properties;
/** Applies only the sealed, INSERT-only V2_027 catalog extension, never a general schema upgrade. */
public final class NativeSqlImportCatalogInstaller {
 private static final String NAME="V2_027_native_sql_import_oracle.sql";
 private static final String SHA256="6eb61115cc2fbb59e05214e85fcde8526125628c304b34d0a3c5b7abcee3a668";
 private NativeSqlImportCatalogInstaller() { }
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("Expected repository properties and V2_027 migration path");
  Properties p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
  String url=p.getProperty("repository.url");if(url==null||url.isBlank())url="jdbc:oracle:thin:@//"+p.getProperty("repository.host")+":"+p.getProperty("repository.port","1521")+"/"+p.getProperty("repository.service");
  String user=p.getProperty("repository.user"),password=p.getProperty("repository.password");if(password==null){if(System.console()==null)throw new IllegalStateException("Repository credentials required");password=new String(System.console().readPassword("Repository password: "));}
  try(Connection c=DriverManager.getConnection(url,user,password)){c.setAutoCommit(false);try{install(c,Path.of(args[1]),user);}catch(Exception e){c.rollback();throw e;}}
 }
 static void verifyAsset(Path path)throws Exception {
  if(!path.getFileName().toString().equals(NAME)||!OracleRepositoryUpgrader.matchesChecksum(path,SHA256))throw new IllegalArgumentException("Only the unchanged bundled INSERT-only V2_027 catalog extension is permitted");
 }
 static void install(Connection c,Path path,String actor)throws Exception {
  verifyAsset(path);String recorded=OracleRepositoryUpgrader.recordedChecksum(c,NAME);
  if(recorded!=null){if(!OracleRepositoryUpgrader.matchesChecksum(path,recorded))throw new IllegalStateException("Recorded V2_027 checksum differs");System.out.println("Exact import catalog already installed; no changes.");return;}
  Path previous=path.resolveSibling("V2_026_exact_oracle_import_oracle.sql");String baseline=OracleRepositoryUpgrader.recordedChecksum(c,previous.getFileName().toString());
  if(baseline==null||!OracleRepositoryUpgrader.matchesChecksum(previous,baseline))throw new IllegalStateException("Verified V2_026 repository is required; use the normal upgrade procedure first");
  try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM algorithm WHERE code IN('SQLSERVER_EXPRESSION_EXACT','POSTGRESQL_EXPRESSION_EXACT')")){r.next();if(r.getLong(1)!=0)throw new IllegalStateException("Unrecorded existing exact-expression algorithm; no replacement or adoption is performed");}
  // The sealed asset creates a fresh algorithm/version/parameter/implementation only.
  // It has no DDL, UPDATE, DELETE, COMMIT, or changes to existing Rule Sets/Jobs/lookups.
  OracleRepositoryUpgrader.apply(c,path);
  try(PreparedStatement q=c.prepareStatement("INSERT INTO privonym_schema_migration(migration_name,checksum_sha256,applied_by) VALUES(?,?,?)")){q.setString(1,NAME);q.setString(2,SHA256);q.setString(3,actor);q.executeUpdate();}
  c.commit();System.out.println("V2_027 installed: new exact-expression catalog entries only; existing Jobs, Rule Sets, lookups and schema unchanged.");
 }
}
