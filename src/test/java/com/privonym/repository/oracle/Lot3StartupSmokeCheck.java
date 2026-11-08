package com.privonym.repository.oracle;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.nio.file.*;
import java.util.Base64;
final class Lot3StartupSmokeCheck {
 private Lot3StartupSmokeCheck() { }
 static void verify()throws Exception {
  Connection c=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{throw new AssertionError("Planning constructor must not issue SQL or commit: "+m.getName());});
  new OraclePlanningAdapter(c);
  Path key=Files.createTempFile("privonym-startup-key-",".key");String previous=System.getProperty("privonym.secret.key.file");
  try{Files.writeString(key,Base64.getEncoder().encodeToString(new byte[32]));System.setProperty("privonym.secret.key.file",key.toString());try(var discovery=new OracleDiscoveryAdapter("jdbc:no-startup-connection","synthetic",new char[0])){if(discovery==null)throw new AssertionError();}}
  finally{if(previous==null)System.clearProperty("privonym.secret.key.file");else System.setProperty("privonym.secret.key.file",previous);Files.deleteIfExists(key);}
  System.out.println("Lot 3 startup checks passed: no repository connection, DDL, seed update or hidden commit during adapter construction.");
 }
}
