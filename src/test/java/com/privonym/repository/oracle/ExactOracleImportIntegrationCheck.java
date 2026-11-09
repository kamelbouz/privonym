package com.privonym.repository.oracle;
import com.privonym.application.service.*;
import com.privonym.adapter.oracle.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
/** Optional native-script vs persisted Rule Set equivalence check on dedicated synthetic tables only. */
public final class ExactOracleImportIntegrationCheck {
 private static final String[] BUSINESS={"TBL_STL_PARTY","TBL_STL_NARRATIVE","TBL_STL_SWIFT_MSG","TBL_STL_MESSAGE_LOB","TBL_STL_INSTR_NOTE"};
 public static void main(String[] args)throws Exception {
  Properties p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
  String jdbc=p.getProperty("repository.url");if(jdbc==null||jdbc.isBlank())jdbc="jdbc:oracle:thin:@//"+p.getProperty("repository.host")+":"+p.getProperty("repository.port","1521")+"/"+p.getProperty("repository.service");
  String user=p.getProperty("repository.user"),password=p.getProperty("repository.password"),schema=user.toUpperCase(Locale.ROOT),tag=UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(Locale.ROOT);String[] names=new String[5];for(int i=0;i<5;i++)names[i]="PV_STL_"+tag+"_"+i;
  if(schema.equals("STL_LOC"))throw new IllegalStateException("Use an isolated fixture schema, not the native parallel branch schema");
  String source=Files.readString(Path.of(args[1]));for(int i=0;i<5;i++)source=source.replaceAll("(?i)\\b"+BUSINESS[i]+"\\b",names[i]);
  if(java.util.regex.Pattern.compile("(?i)\\b(DROP|ALTER|INSERT|DELETE|GRANT|REVOKE)\\b").matcher(source).find())throw new IllegalStateException("This qualification accepts the reviewed STL UPDATE script only");
  try(Connection metadata=DriverManager.getConnection(jdbc,user,password);Connection target=DriverManager.getConnection(jdbc,user,password)) {
   metadata.setAutoCommit(false);target.setAutoCommit(false);List<String> created=new ArrayList<>();FixtureDriver driver=null;
   try {
    String[] columns={"ID_PARTY_BOOKING_ACCOUNT VARCHAR2(40),NM_STL_PARTY VARCHAR2(160),DE_PARTY VARCHAR2(160),CD_PARTY_ROLE VARCHAR2(20)","TXT_NARRATIVE_FREE_TEXT CLOB","CD_CALLER VARCHAR2(40),CLB_MSG_BODY CLOB","CLB_MSG_BODY CLOB","TXT_NOTE_VALUE VARCHAR2(40)"};
    for(int i=0;i<5;i++){try(Statement q=target.createStatement()){q.execute("CREATE TABLE "+names[i]+"(FID NUMBER PRIMARY KEY,"+columns[i]+")");created.add(names[i]);}}
    reset(target,names);
    String nativeSql=source.replace("&schema..",schema+".").replace("'&schema'","'"+schema+"'");
    for(String sql:statements(nativeSql))try(Statement q=target.createStatement()){q.execute(sql);}target.commit();
    List<List<String>> expected=snapshot(target,names);reset(target,names);
    // Catalog migration DML is transaction-local here, never deployed or committed.
    for(String sql:statements(Files.readString(Path.of(args[2]))))try(Statement q=metadata.createStatement()){q.execute(sql);}
    long environment=number(metadata,"SELECT MIN(environment_id) FROM environment");
    execute(metadata,"INSERT INTO connector(name,technology_code,host_name,port,service_name,username_value,schema_name,encrypted_password,status) VALUES(?,'ORACLE',?,?,?,?,?,?,'ACTIVE')",tag,p.getProperty("repository.host"),Integer.valueOf(p.getProperty("repository.port","1521")),p.getProperty("repository.service"),user,schema,LocalCredentialStore.encrypt(password));
    long connector=number(metadata,"SELECT connector_id FROM connector WHERE name=?",tag);
    execute(metadata,"INSERT INTO data_source(environment_id,connector_id,name,enabled) VALUES(?,?,?,'Y')",environment,connector,tag);
    long ds=number(metadata,"SELECT data_source_id FROM data_source WHERE name=?",tag);
    execute(metadata,"INSERT INTO catalog_schema(data_source_id,schema_name) VALUES(?,?)",ds,schema);long catalogSchema=number(metadata,"SELECT catalog_schema_id FROM catalog_schema WHERE data_source_id=?",ds);
    for(String name:names){execute(metadata,"INSERT INTO catalog_table(catalog_schema_id,table_name) VALUES(?,?)",catalogSchema,name);long table=number(metadata,"SELECT catalog_table_id FROM catalog_table WHERE catalog_schema_id=? AND table_name=?",catalogSchema,name);try(ResultSet c=target.getMetaData().getColumns(null,schema,name,null)){while(c.next())execute(metadata,"INSERT INTO catalog_column(catalog_table_id,column_name,ordinal_position,native_data_type,normalized_data_type,data_length,nullable,primary_key_member) VALUES(?,?,?,?,?,?,?,?)",table,c.getString("COLUMN_NAME"),c.getInt("ORDINAL_POSITION"),c.getString("TYPE_NAME"),c.getString("TYPE_NAME").contains("NUMBER")?"DECIMAL":c.getString("TYPE_NAME").contains("LOB")?"LOB":"STRING",c.getInt("COLUMN_SIZE"),c.getInt("NULLABLE")==0?"N":"Y",c.getString("COLUMN_NAME").equals("FID")?"Y":"N");}}
    var preview=new OracleSqlInventoryPreviewService().parse(source);require(preview.tables().size()==5&&preview.columnCount()==7,"Source preview differs from seven-rule contract");
    driver=new FixtureDriver(metadata);DriverManager.registerDriver(driver);
    DraftInventoryResult result;try(var importer=new OracleDraftInventoryAdapter(FixtureDriver.URL,"synthetic",new char[0])){result=importer.createAuthorized(ds,"STL_"+tag,"Synthetic faithful STL import",source,preview,"synthetic",0,0);}
    require(result.columnRulesCreated()==7&&result.reviewItems()==0&&result.status().equals("DRAFT_CREATED"),"Import contains unresolved/review mappings: "+result.reviewItems());
    long ruleset=number(metadata,"SELECT ruleset_id FROM ruleset_version WHERE ruleset_version_id=?",result.rulesetVersionId());
    var plans=new OracleJobPlanRepository().loadRuleset(metadata,ruleset,result.rulesetVersionId(),"ORACLE");require(plans.size()==5,"Persisted Rule Set lost a table");
    RulesetSqlExport export;try(var administration=new OracleRulesetAdministrationAdapter(FixtureDriver.URL,"synthetic",new char[0])){export=administration.exportSql(ruleset,result.rulesetVersionId());}
    require(export.statementCount()==5&&!export.sql().contains("DBMS_PARALLEL_EXECUTE")&&!export.sql().contains(":start_id")&&!export.sql().contains("RUNTIME_V26_REQUIRED"),"Technical orchestration/runtime sentinel leaked into SQL export");
    for(String sql:statements(export.sql()))try(Statement q=target.createStatement()){q.execute(sql);}target.commit();
    require(expected.equals(snapshot(target,names)),"Generated Rule Set results differ from native source script");
    execute(metadata,"UPDATE ruleset_version SET status='PUBLISHED',published_at=SYSTIMESTAMP WHERE ruleset_version_id=?",result.rulesetVersionId());
    var next=DiscoveryDraftWriter.create(metadata,ruleset,List.of(),"synthetic");
    RulesetSqlExport copied;try(var administration=new OracleRulesetAdministrationAdapter(FixtureDriver.URL,"synthetic",new char[0])){copied=administration.exportSql(ruleset,next.versionId());}
    reset(target,names);for(String sql:statements(copied.sql()))try(Statement q=target.createStatement()){q.execute(sql);}target.commit();
    require(copied.versionNumber()==2&&expected.equals(snapshot(target,names)),"New draft version lost exact source expressions or parameters");
    System.out.println("Native STL equivalence passed: five persisted table plans, seven rules, linked derivations, role filter, exact constants, five ordered SWIFT replacements, nulls, CLOB above 32 KB and faithful v1-to-v2 draft copy.");
   }finally{if(driver!=null)DriverManager.deregisterDriver(driver);metadata.rollback();target.rollback();for(String name:created){if(!name.matches("PV_STL_[A-F0-9]{10}_[0-4]"))throw new AssertionError("Unsafe fixture cleanup name");try(Statement q=target.createStatement()){q.execute("DROP TABLE "+name+" PURGE");}}System.out.println("All synthetic metadata rolled back and dedicated tables removed; no business tables, Rule Sets or Jobs modified.");}
  }
 }
 private static List<String> statements(String sql)throws Exception {var m=com.privonym.install.OracleRepositoryInstaller.class.getDeclaredMethod("statements",String.class);m.setAccessible(true);Object result=m.invoke(null,sql);List<String> statements=new ArrayList<>();for(Object item:(List<?>)result)statements.add((String)item);return statements;}
 private static void reset(Connection c,String[] t)throws SQLException {
  for(String name:t)execute(c,"DELETE FROM "+name);
  String tags=":95L::ABCD/account\n:95P::ABCD/bic\n:95Q::ABCD/private address\n:95R::ABCD/dtc\n:95S::ABCD/tax\n:20C::KEEP//REFERENCE";
  for(int i=1;i<=4;i++){
   execute(c,"INSERT INTO "+t[0]+" VALUES(?,?,?,?,?)",i,"BOOKING12"+i,i==2?null:"Private name","Private description",List.of("CLIENT","BROKER","OTHER","OTHER").get(i-1));
   String body=i==2?null:i==3?":20C::KEEP//UNCHANGED":i==4?"SAFE HEADER ".repeat(4000)+tags:tags;
   execute(c,"INSERT INTO "+t[1]+" VALUES(?,?)",i,body);execute(c,"INSERT INTO "+t[2]+" VALUES(?,?,?)",i,"CALLER",body);execute(c,"INSERT INTO "+t[3]+" VALUES(?,?)",i,body);execute(c,"INSERT INTO "+t[4]+" VALUES(?,?)",i,i==2?null:"Private note");
  }c.commit();
 }
 private static List<List<String>> snapshot(Connection c,String[] tables)throws SQLException {List<List<String>> result=new ArrayList<>();for(String table:tables)try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT * FROM "+table+" ORDER BY FID")){while(r.next()){List<String> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));result.add(row);}}return result;}
 private static long number(Connection c,String sql,Object... args)throws SQLException{try(PreparedStatement q=c.prepareStatement(sql)){bind(q,args);try(ResultSet r=q.executeQuery()){r.next();return r.getLong(1);}}}
 private static void execute(Connection c,String sql,Object... args)throws SQLException{try(PreparedStatement q=c.prepareStatement(sql)){bind(q,args);q.executeUpdate();}}
 private static void bind(PreparedStatement q,Object[] args)throws SQLException{for(int i=0;i<args.length;i++){Object v=args[i];if(v instanceof String s&&s.length()>1000)q.setCharacterStream(i+1,new java.io.StringReader(s));else q.setObject(i+1,v);}}
 private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
 private static final class FixtureDriver implements Driver {
  static final String URL="jdbc:privonym-stl-fixture";private final Connection connection;FixtureDriver(Connection c){connection=c;}
  @Override public boolean acceptsURL(String url){return URL.equals(url);}
  @Override public Connection connect(String url,Properties p){if(!acceptsURL(url))return null;return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(x,m,a)->{if(List.of("close","commit","setReadOnly").contains(m.getName()))return null;try{return m.invoke(connection,a);}catch(InvocationTargetException e){throw e.getCause();}});}
  @Override public DriverPropertyInfo[] getPropertyInfo(String u,Properties p){return new DriverPropertyInfo[0];}
  @Override public int getMajorVersion(){return 1;}@Override public int getMinorVersion(){return 0;}@Override public boolean jdbcCompliant(){return false;}@Override public Logger getParentLogger(){return Logger.getGlobal();}
 }
}
