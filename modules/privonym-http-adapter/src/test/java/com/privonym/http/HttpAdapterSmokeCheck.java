package com.privonym.http;

import com.privonym.application.port.in.QueryJobsUseCase;
import com.privonym.application.port.in.QueryEnvironmentsUseCase;
import com.privonym.application.port.in.QueryInventoryUseCase;
import com.privonym.application.port.in.QueryMaskingInventoriesUseCase;
import com.privonym.application.port.in.ManageMaskingInventoryUseCase;
import com.privonym.application.port.in.CreateDraftInventoryUseCase;
import com.privonym.application.port.in.RulesetAdministrationUseCase;
import com.privonym.application.service.DataSourceInventory;
import com.privonym.application.service.DataSourceSummary;
import com.privonym.application.service.EnvironmentDetails;
import com.privonym.application.service.EnvironmentSummary;
import com.privonym.application.service.JobDetails;
import com.privonym.application.service.JobStepDetails;
import com.privonym.application.service.JobSummary;
import com.privonym.application.service.InventoryColumn;
import com.privonym.application.service.InventorySchema;
import com.privonym.application.service.InventoryTable;
import com.privonym.application.service.DraftInventoryResult;
import com.privonym.application.service.MaskingInventoryDetails;
import com.privonym.application.service.MaskingInventoryMapping;
import com.privonym.application.service.MaskingInventorySummary;
import com.privonym.application.service.MaskingAlgorithmOption;
import com.privonym.application.service.InventoryMutationResult;
import com.privonym.application.service.SqlInventoryPreview;
import com.privonym.application.service.JobCreationResult;
import com.privonym.application.service.RulesetDetails;
import com.privonym.application.service.RulesetInventoryEntry;
import com.privonym.application.service.RulesetJobView;
import com.privonym.application.service.RulesetSummary;
import com.privonym.application.service.RulesetVersionView;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/** Real-socket acceptance check for the embedded HTTP adapter. */
public final class HttpAdapterSmokeCheck {
    private HttpAdapterSmokeCheck() {
    }

    public static void main(String[] arguments) throws Exception {
        QueryJobsUseCase jobs = new QueryJobsUseCase() {
            private final JobSummary summary = new JobSummary(
                    42, "TEST-JOB-001", "READY", "2026-08-09T21:00:00.000+02:00", "", 14, 14,
                    61, "Customer masking", 40, 4, "AUTO", 100000, 1000000, 0, 0,
                    "prepare-data.sql", "rebuild-indexes.sql");

            @Override
            public List<JobSummary> listJobs(int limit) {
                return List.of(summary);
            }

            @Override
            public JobDetails getJob(String reference) {
                if (!summary.reference().equals(reference)) {
                    throw new IllegalArgumentException("not found");
                }
                return new JobDetails(summary, 10, 20, "", "", "prepare-data.sql",
                        "rebuild-indexes.sql",
                        List.of(new JobStepDetails(20, "ANONYMIZE_TABLE", "READY", 14)));
            }
        };
        QueryEnvironmentsUseCase environments = new QueryEnvironmentsUseCase() {
            private final EnvironmentSummary summary = new EnvironmentSummary(
                    7, "TEST", "Test environment", "ACTIVE", "admin", 1, 4);

            @Override
            public List<EnvironmentSummary> listEnvironments() {
                return List.of(summary);
            }

            @Override
            public EnvironmentDetails getEnvironment(String code) {
                if (!summary.code().equals(code)) {
                    throw new IllegalArgumentException("not found");
                }
                return new EnvironmentDetails(summary, "Test",
                        List.of(new DataSourceSummary(9, "Oracle source", true, "ORACLE", "ACTIVE",
                                "db.local", 1521, "SERVICE", "target", 2, 10, 4)));
            }

            @Override public EnvironmentSummary createEnvironment(String code,String name,String description) {
                return new EnvironmentSummary(8,code.toUpperCase(),name,"ACTIVE","admin",0,0);
            }
        };
        QueryInventoryUseCase inventory = dataSourceId -> new DataSourceInventory(dataSourceId,
                "Oracle source", "ORACLE", List.of(new InventorySchema("APP", "2026-08-10T01:00:00.000+02:00",
                        List.of(new InventoryTable(12, "CUSTOMER", "TABLE", 100L, true,
                                "2026-08-10T01:00:00.000+02:00",
                                List.of(new InventoryColumn("CUSTOMER_ID", 1, "NUMBER", "INTEGER",
                                        false, true, true)))))));
        MaskingInventorySummary maskingSummary = new MaskingInventorySummary(81,
                "CUSTOMER_MASKING", "Customer masking", 1, "DRAFT",
                "2026-08-11T09:00:00.000+02:00", 2, 0, 1,
                "Oracle source", "TRANSFORM-HTTP-TEST");
        QueryMaskingInventoriesUseCase maskingInventories = new QueryMaskingInventoriesUseCase() {
            @Override
            public List<MaskingInventorySummary> listInventories() {
                return List.of(maskingSummary);
            }

            @Override
            public MaskingInventoryDetails getInventory(long id) {
                if (id != maskingSummary.rulesetVersionId()) throw new IllegalArgumentException("not found");
                return new MaskingInventoryDetails(maskingSummary,
                        List.of(new MaskingInventoryMapping(91, 1, 10, "COLUMN", "UPDATE",
                                "APP", "CUSTOMER", "EMAIL", "NULLIFY", "MATCHED", "",
                                "{}", "", "NULL", "dbhost", "ORCL", "VARCHAR2(200)",
                                true, true, "MASK")));
            }
        };
        ManageMaskingInventoryUseCase inventoryManagement = new ManageMaskingInventoryUseCase() {
            @Override
            public List<MaskingAlgorithmOption> listAlgorithms() {
                return List.of(new MaskingAlgorithmOption("NULLIFY", "Replace with null",
                                "SUPPRESSION", "PUBLISHED", List.of()),
                        new MaskingAlgorithmOption("HMAC_SHA256", "Keyed SHA-256",
                                "PSEUDONYMIZATION", "PUBLISHED", List.of("key_material")));
            }

            @Override
            public InventoryMutationResult resolveMapping(long versionId, long mappingId,
                    String code, String parameters) {
                return new InventoryMutationResult(versionId, "DRAFT", 0, "Column rule resolved");
            }

            @Override
            public InventoryMutationResult resolveAllMappings(long versionId) {
                return new InventoryMutationResult(versionId, "DRAFT", 2,
                        "5 rule(s) validated automatically; 2 item(s) still require individual review");
            }

            @Override
            public InventoryMutationResult acceptMapping(long versionId, long mappingId) {
                return new InventoryMutationResult(versionId, "DRAFT", 0,
                        "Structural operation accepted");
            }

            @Override
            public InventoryMutationResult publish(long versionId) {
                return new InventoryMutationResult(versionId, "PUBLISHED", 0,
                        "Inventory published successfully");
            }
            @Override public com.privonym.application.service.AlgorithmCreationResult cloneAlgorithm(String template,String code,String name,List<String> values){return new com.privonym.application.service.AlgorithmCreationResult(8,9,code,name,"LOOKUP_MAPPING","PUBLISHED",values.size());}
        };
        CreateDraftInventoryUseCase draftInventory = new CreateDraftInventoryUseCase() {
            private DraftInventoryResult result(String code) {
                return new DraftInventoryResult(71, "TRANSFORM-HTTP-TEST", 81, code, 1,
                        "DRAFT_CREATED", 2, 0, 0,
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
            }

            @Override
            public DraftInventoryResult create(long dataSourceId, String code,
                    String name, String sql) {
                return result(code);
            }

            @Override
            public DraftInventoryResult createFromInventory(long dataSourceId, String code,
                    String name, String source, SqlInventoryPreview preview) {
                return result(code);
            }
        };
        RulesetSummary rulesetSummary = new RulesetSummary(61, "CUSTOMER_MASKING",
                "Customer masking", "Customer privacy policy", 9, "Oracle source",
                "ORACLE", "TEST", "Test", 1, 1, 0);
        java.util.concurrent.atomic.AtomicReference<List<com.privonym.application.service.RulesetInventorySelection>>
                savedSelections = new java.util.concurrent.atomic.AtomicReference<>(List.of());
        RulesetAdministrationUseCase rulesets = new RulesetAdministrationUseCase() {
            @Override
            public List<RulesetSummary> listRulesets() {
                return List.of(rulesetSummary);
            }

            @Override
            public RulesetDetails getRuleset(long rulesetId, Long versionId) {
                if (rulesetId != 61) throw new IllegalArgumentException("not found");
                return new RulesetDetails(rulesetSummary, 81,
                        List.of(new RulesetVersionView(81, 1, "PUBLISHED",
                                "2026-08-11T09:00:00.000+02:00",
                                "2026-08-11T10:00:00.000+02:00", 1, 0)),
                        List.of(new RulesetInventoryEntry(91, "APP", "CUSTOMER", "EMAIL",
                                "VARCHAR2(200)", "NULLIFY", true, "ALL")),
                        List.<RulesetJobView>of());
            }

            @Override public RulesetVersionView createDraft(long rulesetId,long sourceVersionId) {
                return new RulesetVersionView(82,2,"DRAFT","2026-08-11T11:00:00.000+02:00","",1,0);
            }

            @Override public com.privonym.application.service.RulesetCloneResult cloneRuleset(
                    long sourceRulesetId,long targetDataSourceId,String name) {
                return new com.privonym.application.service.RulesetCloneResult(
                        62,83,81,1,0,2);
            }

            @Override public void deleteRuleset(long rulesetId) {
                if (rulesetId != 61) throw new IllegalArgumentException("not found");
            }

            @Override public void deleteVersion(long rulesetId,long versionId) {
                if (rulesetId != 61 || versionId != 81) throw new IllegalArgumentException("not found");
            }

            @Override public List<com.privonym.application.service.RulesetCatalogColumn> listCatalog(long rulesetId,long versionId) { return List.of(new com.privonym.application.service.RulesetCatalogColumn(12,13,"APP","CUSTOMER","EMAIL","VARCHAR2",false,"","",true,"HMAC_SHA256","",List.of("key_material"),List.of("NULLIFY","HMAC_SHA256"))); }
            @Override public int saveInventory(long rulesetId,long versionId,List<com.privonym.application.service.RulesetInventorySelection> selections) { savedSelections.set(selections);return selections.size(); }
            @Override public void deleteJob(String reference) { if(!"TEST-JOB-001".equals(reference))throw new IllegalArgumentException("not found"); }
            @Override public com.privonym.application.service.JobUpdateResult updateJob(String currentReference,String newReference,long rulesetId,int parallelDegree,String executionMode,int chunkSize,long chunkThreshold) { if(!"TEST-JOB-001".equals(currentReference))throw new IllegalArgumentException("Job not found");return new com.privonym.application.service.JobUpdateResult(42,newReference,"CREATED",rulesetId,81,true); }
            @Override public com.privonym.application.service.ExecuteJobResult executeJob(String reference) { return new com.privonym.application.service.ExecuteJobResult(new com.privonym.domain.job.JobId(41),com.privonym.domain.job.JobStatus.VALIDATING,1,10); }
            @Override public com.privonym.application.service.ExecuteJobResult executeJob(long jobId) { return new com.privonym.application.service.ExecuteJobResult(new com.privonym.domain.job.JobId(jobId),com.privonym.domain.job.JobStatus.VALIDATING,1,10); }
            @Override public void saveJobScript(String reference,String phase,String fileName,String content) { }
            @Override public void cancelJob(long jobId) { if(jobId!=41)throw new IllegalArgumentException("Job was not found: "+jobId); }
            @Override public com.privonym.application.service.RulesetSqlExport exportSql(long rulesetId,long versionId) { if(rulesetId!=61||versionId!=81)throw new IllegalArgumentException("not found");return new com.privonym.application.service.RulesetSqlExport("CUSTOMER_MASKING",1,"ORACLE",1,"-- Privonym Rule Set SQL export\nUPDATE \"APP\".\"CUSTOMER\" SET \"EMAIL\" = NULL;\n"); }

            @Override
            public JobCreationResult createJob(long rulesetId, long versionId,
                    String reference, int parallelDegree, String executionMode,
                    int chunkSize, long chunkThreshold) {
                return new JobCreationResult(101, reference, "CREATED", versionId);
            }
        };
        com.privonym.application.port.in.DiscoveryUseCase discovery =
                new com.privonym.application.port.in.DiscoveryUseCase() {
            @Override public List<com.privonym.application.service.DiscoveryConnection> listConnections() { return List.of(); }
            @Override public List<com.privonym.application.service.EnvironmentOption> listEnvironmentOptions() { return List.of(); }
            @Override public com.privonym.application.service.ConnectionCreationResult createConnection(long environmentId,String name,String technology,String schema,String host,int port,String service,String database,String user,String provider,String reference,char[] password) { throw new UnsupportedOperationException(); }
            @Override public void deleteConnection(long id) { throw new UnsupportedOperationException(); }
            @Override public void updateConnection(long id,com.privonym.application.service.ConnectionUpdateCommand command) { throw new UnsupportedOperationException(); }
            @Override public com.privonym.application.service.DiscoveryScan scan(long id) { return new com.privonym.application.service.DiscoveryScan(id,"Test connection",true,List.of(),List.of()); }
            @Override public com.privonym.application.service.DiscoveryScan analyzeContent(long id) {
                return new com.privonym.application.service.DiscoveryScan(id,"Test connection",true,List.of(),List.of(
                    new com.privonym.application.service.DiscoveryFinding(12,"APP","NOTES","BODY","CLOB","TEXT_SENSITIVE",75,"NULLIFY",
                        "Context clues; whole-value removal. Review required.",List.of("PERSON_CONTEXT"),20)),List.of("Bounded samples only."),
                        new com.privonym.application.service.DiscoveryRulesetContext(61,"CUSTOMER_MASKING","Customer masking",5,6,null));
            }
            @Override public com.privonym.application.service.DiscoveryCreationResult createDraftVersion(long id,List<com.privonym.application.service.DiscoverySelection> selections) {
                if(id==62)throw new IllegalStateException("This Rule Set already has a DRAFT version");
                if(id!=61||selections.size()!=1||selections.get(0).columnId()!=12)throw new IllegalArgumentException("Invalid selection");
                return new com.privonym.application.service.DiscoveryCreationResult(61,86,"CUSTOMER_MASKING",1,6,false);
            }
            @Override public com.privonym.application.service.DiscoveryCreationResult createRuleset(long id,String code,String name,List<com.privonym.application.service.DiscoverySelection> selections) { throw new UnsupportedOperationException(); }
            @Override public com.privonym.application.service.DiscoveryCreationResult createEmptyRuleset(long id,String code,String name) { throw new UnsupportedOperationException(); }
        };
        Server server = PrivonymHttpServer.create("127.0.0.1", 0, jobs, environments, inventory,
                draftInventory, maskingInventories, inventoryManagement, rulesets,
                discovery, null);
        ExecutorService httpExecutor = Executors.newCachedThreadPool();
        try {
            server.start();
            int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            HttpClient client = HttpClient.newBuilder().executor(httpExecutor).build();

            HttpResponse<String> home = send(client, port, "/", "GET");
            require(home.statusCode() == 200, "home status must be 200");
            require(home.body().contains("Operational summary"), "home must contain the overview");
            require(home.body().contains("/assets/privonym.png"),
                    "home must contain the Privonym logo");
            require(home.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
                    "home content type must be HTML");

            HttpResponse<String> logo = send(client, port, "/assets/privonym.png", "GET");
            require(logo.statusCode() == 200, "logo status must be 200");
            require(logo.headers().firstValue("Content-Type").orElse("").equals("image/png"),
                    "logo content type must be image/png");
            require(!logo.body().isEmpty(), "logo body must not be empty");

            HttpResponse<String> oracleLogo = send(client, port, "/assets/oracle-logo.png", "GET");
            require(oracleLogo.statusCode() == 200
                            && oracleLogo.headers().firstValue("Content-Type").orElse("").equals("image/png")
                            && !oracleLogo.body().isEmpty(),
                    "Oracle logo must be served as PNG");
            HttpResponse<String> postgresqlLogo = send(client, port, "/assets/postgresql-logo.svg", "GET");
            require(postgresqlLogo.statusCode() == 200
                            && postgresqlLogo.headers().firstValue("Content-Type").orElse("").equals("image/svg+xml")
                            && !postgresqlLogo.body().isEmpty(),
                    "PostgreSQL logo must be served as SVG");
            HttpResponse<String> sqlServerLogo = send(client, port, "/assets/sqlserver-logo.png", "GET");
            require(sqlServerLogo.statusCode() == 200
                            && sqlServerLogo.headers().firstValue("Content-Type").orElse("").equals("image/png")
                            && !sqlServerLogo.body().isEmpty(),
                    "SQL Server logo must be served as PNG");

            HttpResponse<String> environmentsPage = send(client, port, "/environments", "GET");
            require(environmentsPage.statusCode() == 200, "environments page status must be 200");
            require(environmentsPage.body().contains("id=\"environment-list\""),
                    "environments page must contain its dedicated table");
            require(environmentsPage.body().contains("id=\"environment-add\""),
                    "environments page must expose environment creation");

            HttpResponse<String> environmentPage = send(client, port, "/environments/TEST", "GET");
            require(environmentPage.statusCode() == 200, "environment page status must be 200");
            require(environmentPage.body().contains("data-tab=\"connections\""),
                    "environment page must contain dedicated tabs");

            HttpResponse<String> health = send(client, port, "/api/v1/health", "GET");
            require(health.statusCode() == 200, "health status must be 200");
            require(health.body().contains("\"status\":\"UP\""), "health body must report UP");
            require(health.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                    "health content type must be JSON");

            HttpResponse<String> jobList = send(client, port, "/api/v1/jobs", "GET");
            require(jobList.statusCode() == 200, "job list status must be 200");
            require(jobList.body().contains("\"reference\":\"TEST-JOB-001\""),
                    "job list must contain the repository job");
            require(jobList.body().contains("\"rulesetId\":61")
                            && jobList.body().contains("\"rulesetName\":\"Customer masking\"")
                            && jobList.body().contains("\"preScriptFileName\":\"prepare-data.sql\"")
                            && jobList.body().contains("\"postScriptFileName\":\"rebuild-indexes.sql\""),
                    "job list must expose its Rule Set for editing");

            HttpResponse<String> jobDetails = send(client, port, "/api/v1/jobs/TEST-JOB-001", "GET");
            require(jobDetails.statusCode() == 200, "job details status must be 200");
            require(jobDetails.body().contains("\"type\":\"ANONYMIZE_TABLE\""),
                    "job details must contain its steps");

            HttpResponse<String> jobsPage = send(client, port, "/jobs", "GET");
            require(jobsPage.statusCode() == 200 && jobsPage.body().contains("id=\"job-create\""),
                    "jobs page must expose Job creation");
            require(jobsPage.body().contains("id=\"global-error-dialog\"")
                            && jobsPage.body().contains("class=\"error-dialog-icon\"")
                            && jobsPage.body().contains("id=\"global-error-dismiss\"")
                            && jobsPage.body().contains("Unable to complete the action")
                            && jobsPage.body().contains("const showError=error=>")
                            && jobsPage.body().contains("window.addEventListener('unhandledrejection'")
                            && jobsPage.body().contains("const nativeFetch=window.fetch.bind(window)")
                            && jobsPage.body().contains("payload.error==='REPOSITORY_LOGIN_REQUIRED'")
                            && jobsPage.body().contains("payload.error==='APPLICATION_LOGIN_REQUIRED'")
                            && jobsPage.body().contains("location.replace('/login')")
                            && jobsPage.body().contains("if(authenticationRedirectStarted)return"),
                    "console pages must expose the common error popup");
            require(jobsPage.body().contains("class=\"toolbar job-create-actions\"")
                            && !jobsPage.body().contains("</div><button id=\"job-create\""),
                    "Job creation action must use a dedicated aligned row");
            require(jobsPage.body().contains("<th>JOB_ID</th>")
                            && jobsPage.body().contains("cell(row,item.id,'code')"),
                    "jobs page must display the Job identifier column");
            require(jobsPage.body().contains("<th>Progress</th>")
                            && jobsPage.body().contains("appendProgress(row,item)")
                            && jobsPage.body().contains("item.progressPercent")
                            && jobsPage.body().contains("item.completedChunks+'/'+item.totalChunks")
                            && jobsPage.body().contains("id=\"job-execution-mode\"")
                            && jobsPage.body().contains("id=\"job-chunk-size\"")
                            && jobsPage.body().contains("id=\"job-chunk-threshold\"")
                            && jobsPage.body().contains("setInterval(loadJobs,2000)"),
                    "jobs page must display and periodically refresh execution progress");
            require(jobsPage.body().contains("id=\"job-edit-dialog\"")
                            && jobsPage.body().contains("id=\"job-edit-parallel\"")
                            && jobsPage.body().contains("id=\"job-edit-mode\"")
                            && jobsPage.body().contains("id=\"job-edit-chunk-size\"")
                            && jobsPage.body().contains("id=\"job-edit-chunk-threshold\"")
                            && jobsPage.body().contains("<th>Scripts</th>")
                            && jobsPage.body().contains("id=\"job-edit-current-pre\"")
                            && jobsPage.body().contains("id=\"job-edit-current-post\"")
                            && jobsPage.body().contains("id=\"job-edit-choose-pre\"")
                            && jobsPage.body().contains("id=\"job-edit-choose-post\"")
                            && jobsPage.body().contains("id=\"job-edit-remove-pre-row\" hidden")
                            && jobsPage.body().contains("id=\"job-edit-remove-post-row\" hidden")
                            && jobsPage.body().contains("function setStoredScript(phase,fileName)")
                            && jobsPage.body().contains("Replace '+phase+' script")
                            && jobsPage.body().contains("appendJobScripts(row,item)")
                            && jobsPage.body().contains("'CANCELLED','FAILED','PLAN_FAILED'")
                            && jobsPage.body().contains("method:'PUT'")
                            && jobsPage.body().contains("run.textContent=cancellable?'CANCEL EXECUTION':rerun?'RE-EXECUTE':'EXECUTE'")
                            && jobsPage.body().contains("'/cancel',{method:'POST'")
                            && jobsPage.body().contains("options.append(report,edit,remove)")
                            && jobsPage.body().contains("class=\"dots-icon\"")
                            && jobsPage.body().contains("action.className='job-actions-cell'")
                            && jobsPage.body().contains("document.body.appendChild(options)")
                            && jobsPage.body().contains("toggleJobActionMenu(event,toggle,options)")
                            && jobsPage.body().contains(".job-action-popover:not([hidden])")
                            && jobsPage.body().contains("if(item.jobCount===0)"),
                    "jobs page must support edit and safe deletion actions");

            HttpResponse<String> ruleSetsPage = send(client, port, "/rulesets", "GET");
            require(ruleSetsPage.statusCode() == 200
                            && ruleSetsPage.body().contains("!item.rulesetExists&&item.enabled")
                            && ruleSetsPage.body().contains("id=\"ruleset-clone-source\"")
                            && ruleSetsPage.body().contains("'/api/v1/rulesets/'+source.value+'/clone?'")
                            && ruleSetsPage.body().contains("SQLSERVER:'SQL Server'")
                            && ruleSetsPage.body().contains("technologyCell(row,item.technology)")
                            && ruleSetsPage.body().contains("/assets/oracle-logo.png")
                            && ruleSetsPage.body().contains("/assets/postgresql-logo.svg")
                            && ruleSetsPage.body().contains("/assets/sqlserver-logo.png")
                            && !ruleSetsPage.body().contains("cell(row,'ORACLE','code')"),
                    "Rule Set creation must only offer unlinked enabled connections");

            HttpResponse<String> clonedRuleSet = sendBody(client, port,
                    "/api/v1/rulesets/61/clone?targetDataSourceId=10&name=Cloned%20masking", "");
            require(clonedRuleSet.statusCode() == 201
                            && clonedRuleSet.body().contains("\"rulesetId\":62")
                            && clonedRuleSet.body().contains("\"columnRulesCloned\":1")
                            && clonedRuleSet.body().contains("\"skippedRules\":2"),
                    "Rule Set cloning must expose cloned and skipped rule counts");

            HttpResponse<String> discoveryPage = send(client, port, "/discovery", "GET");
            require(discoveryPage.statusCode() == 200
                            && discoveryPage.body().contains(
                                    "payload.connections.filter(item=>item.enabled)")
                            && discoveryPage.body().contains(
                                    "check.checked=false")
                            && !discoveryPage.body().contains("if(parameters.get('analyze')==='1')scanButton.click()"),
                    "Discovery supports review of existing sources, requires explicit sampling and leaves suggestions unchecked");
            HttpResponse<String> discoveryScan=send(client,port,"/api/v1/discovery/scan/1","POST");
            require(discoveryScan.statusCode()==200&&discoveryScan.body().contains("\"detectedEntities\":[\"PERSON_CONTEXT\"]")
                    &&discoveryScan.body().contains("\"sampledValues\":20")&&discoveryScan.body().contains("\"warnings\":[\"Bounded samples only.\"]")
                    &&discoveryScan.body().contains("\"nextVersion\":6"),
                    "explicit Discovery endpoint must use content analysis and expose evidence without samples");
            HttpResponse<String> discoveryDraft=sendBody(client,port,"/api/v1/discovery/versions/61","12,NULLIFY");
            require(discoveryDraft.statusCode()==201&&discoveryDraft.body().contains("\"newRuleset\":false")
                    &&discoveryDraft.body().contains("\"versionNumber\":6"),"Discovery must create next draft on same Rule Set");
            require(sendBody(client,port,"/api/v1/discovery/versions/62","12,NULLIFY").statusCode()==409,"existing draft must not be overwritten");

            HttpResponse<String> connectionsPage = send(client, port, "/connections", "GET");
            require(connectionsPage.statusCode() == 200
                            && connectionsPage.body().contains(
                                    "<option value=\"SQLSERVER\">Microsoft SQL Server</option>")
                            && connectionsPage.body().contains("technology==='SQLSERVER'?'1433'")
                            && connectionsPage.body().contains("id=\"connection-search\" type=\"search\" autocomplete=\"off\"")
                            && connectionsPage.body().contains("search.value=''")
                            && connectionsPage.body().contains("id=\"connection-secret-provider\"")
                            && connectionsPage.body().contains("<option value=\"ENCRYPTED_LOCAL\" selected>")
                            && connectionsPage.body().contains("id=\"connection-secret-reference\"")
                            && connectionsPage.body().contains("secretProvider:provider,secretReference:reference")
                            && connectionsPage.body().contains("id=\"connection-analyze\"")
                            && connectionsPage.body().contains("document.getElementById('connection-analyze').hidden=!item.enabled")
                            && connectionsPage.body().contains("'/discovery?dataSource='")
                            && connectionsPage.body().contains("id=\"connection-credentials-open\"")
                            && connectionsPage.body().contains("document.getElementById('connection-credentials').hidden=true")
                            && connectionsPage.body().contains("Edit database connection")
                            && connectionsPage.body().contains("id=\"connection-config-name\"")
                            && connectionsPage.body().contains("id=\"connection-config-technology\"")
                            && connectionsPage.body().contains("id=\"connection-config-environment\"")
                            && connectionsPage.body().contains("id=\"connection-config-host\"")
                            && connectionsPage.body().contains("id=\"connection-config-database\"")
                            && connectionsPage.body().contains("Leave the password empty to keep")
                            && connectionsPage.body().contains("Testing the updated connection"),
                    "Connections must support complete, safe editing");

            HttpResponse<String> selectedJobPage = send(client, port,
                    "/jobs/TEST-JOB-001", "GET");
            require(selectedJobPage.statusCode() == 200
                            && selectedJobPage.body().contains("const requestedJob=")
                            && selectedJobPage.body().contains("search.value=requestedJob"),
                    "overview Job links must open the Jobs panel with the selected reference");

            HttpResponse<String> monitorPage = send(client, port, "/monitor", "GET");
            require(monitorPage.statusCode() == 200
                            && monitorPage.body().contains("id=\"monitor-table\"")
                            && monitorPage.body().contains("setInterval(loadMonitor,2000)"),
                    "monitor page must expose live Job monitoring");

            HttpResponse<String> schedulerPage = send(client, port, "/scheduler", "GET");
            require(schedulerPage.statusCode() == 200
                            && schedulerPage.body().contains("id=\"scheduler-command\"")
                            && schedulerPage.body().contains("id=\"scheduler-source-os\"")
                            && schedulerPage.body().contains("id=\"scheduler-target-os\"")
                            && schedulerPage.body().contains("id=\"scheduler-host\"")
                            && schedulerPage.body().contains("id=\"scheduler-user\"")
                            && schedulerPage.body().contains("privonym-job.ps1")
                            && schedulerPage.body().contains("privonym-job.sh")
                            && schedulerPage.body().contains("ssh.exe -T")
                            && schedulerPage.body().contains("ssh -T")
                            && schedulerPage.body().contains("remoteQuote(runner)"),
                    "scheduler page must generate remote Linux, AIX and Windows commands");

            HttpResponse<String> aboutPage = send(client, port, "/about", "GET");
            require(aboutPage.statusCode() == 200
                            && aboutPage.body().contains("About Privonym")
                            && aboutPage.body().contains("Developed by Kamel Bouzenad"),
                    "about page must identify Privonym and its developer");

            HttpResponse<String> executedJob = sendBody(client, port,
                    "/api/v1/jobs/TEST-JOB-001/execute", "target-password");
            require(executedJob.statusCode() == 200,
                    "Job execution status must be 200");
            require(executedJob.body().contains("\"rowsProcessed\":10"),
                    "Job execution must expose processed rows");

            HttpResponse<String> executedJobById = sendBody(client, port,
                    "/api/v1/jobs/41/execute", "");
            require(executedJobById.statusCode() == 200,
                    "Job execution by numeric identifier status must be 200");
            require(executedJobById.body().contains("\"jobId\":41"),
                    "Job execution by numeric identifier must expose the Job id");

            HttpResponse<String> cancelledJob = sendBody(client, port,
                    "/api/v1/jobs/41/cancel", "");
            require(cancelledJob.statusCode() == 202
                            && cancelledJob.body().contains("\"status\":\"CANCELLING\""),
                    "Job cancellation must be accepted by numeric identifier");

            HttpResponse<String> updatedJob = sendPutBody(client, port,
                    "/api/v1/jobs/TEST-JOB-001",
                    "reference=RENAMED_JOB&rulesetId=61&parallelDegree=8&executionMode=CHUNKED&chunkSize=50000&chunkThreshold=500000");
            require(updatedJob.statusCode() == 200
                            && updatedJob.body().contains("\"jobId\":42")
                            && updatedJob.body().contains("\"reference\":\"RENAMED_JOB\"")
                            && updatedJob.body().contains("\"planReset\":true"),
                    "Job update must preserve its identifier and expose the plan reset");

            HttpResponse<String> deletedJob = send(client, port,
                    "/api/v1/jobs/TEST-JOB-001", "DELETE");
            require(deletedJob.statusCode() == 200,
                    "Job deletion status must be 200");

            HttpResponse<String> environmentList = send(client, port, "/api/v1/environments", "GET");
            require(environmentList.statusCode() == 200, "environment list status must be 200");
            require(environmentList.body().contains("\"code\":\"TEST\""),
                    "environment list must contain the repository environment");

            HttpResponse<String> createdEnvironment = sendBody(client, port,
                    "/api/v1/environments", "code=DEV&name=Development&description=Developer%20tests");
            require(createdEnvironment.statusCode() == 201,
                    "environment creation status must be 201");
            require(createdEnvironment.body().contains("\"code\":\"DEV\"")
                            && createdEnvironment.body().contains("\"status\":\"ACTIVE\""),
                    "environment creation must expose the active environment");

            HttpResponse<String> environmentDetails = send(
                    client, port, "/api/v1/environments/TEST", "GET");
            require(environmentDetails.statusCode() == 200, "environment details status must be 200");
            require(environmentDetails.body().contains("\"technology\":\"ORACLE\""),
                    "environment details must contain its Oracle source");

            HttpResponse<String> inventoryResponse = send(
                    client, port, "/api/v1/data-sources/9/inventory", "GET");
            require(inventoryResponse.statusCode() == 200, "inventory status must be 200");
            require(inventoryResponse.body().contains("\"name\":\"CUSTOMER\""),
                    "inventory must contain its table");
            require(inventoryResponse.body().contains("\"primaryKey\":true"),
                    "inventory must expose primary-key metadata");

            HttpResponse<String> sqlPreview = sendBody(client, port,
                    "/api/v1/transformations/preview?dataSourceId=9", """
                            UPDATE APP.CUSTOMER
                               SET EMAIL = NULL,
                                   NAME = CASE WHEN NAME IS NULL THEN NULL
                                               ELSE REGEXP_REPLACE(NAME, '[[:alnum:]]', 'X') END;
                            """);
            require(sqlPreview.statusCode() == 200, "SQL preview status must be 200");
            require(sqlPreview.body().contains("\"name\":\"CUSTOMER\""),
                    "SQL preview must contain the detected table");
            require(sqlPreview.body().contains("\"columnCount\":2"),
                    "SQL preview must contain detected column rules");
            require(sqlPreview.body().contains("\"algorithmCode\":\"NULLIFY\""),
                    "SQL preview must detect the masking algorithm");

            HttpResponse<String> previewWithoutConnection = sendBody(client, port,
                    "/api/v1/transformations/preview", "UPDATE APP.CUSTOMER SET EMAIL = NULL;");
            require(previewWithoutConnection.statusCode() == 400
                            && previewWithoutConnection.body().contains("DATA_SOURCE_REQUIRED"),
                    "SQL preview must require a selected connection");

            HttpResponse<String> emptySqlPreview = sendBody(client, port,
                    "/api/v1/inventory-imports/preview", " ");
            require(emptySqlPreview.statusCode() == 400, "empty SQL preview status must be 400");

            HttpResponse<String> draft = sendBody(client, port,
                    "/api/v1/transformations/drafts/9?code=CUSTOMER_MASKING&name=Customer%20Masking",
                    "UPDATE APP.CUSTOMER SET EMAIL = NULL;");
            require(draft.statusCode() == 201, "draft inventory status must be 201");
            require(draft.body().contains("\"importReference\":\"TRANSFORM-HTTP-TEST\""),
                    "draft inventory must expose its import reference");
            require(draft.body().contains("\"columnRulesCreated\":2"),
                    "draft inventory must expose created rules");

            HttpResponse<String> inventoryList = send(client, port, "/api/v1/inventories", "GET");
            require(inventoryList.statusCode() == 410,
                    "legacy global inventory list must report that it was replaced");
            require(inventoryList.body().contains("/api/v1/rulesets"),
                    "legacy inventory list must point to rulesets");

            HttpResponse<String> inventoryDetails = send(client, port,
                    "/api/v1/inventories/81", "GET");
            require(inventoryDetails.statusCode() == 200, "masking inventory details status must be 200");
            require(inventoryDetails.body().contains("\"algorithmCode\":\"NULLIFY\""),
                    "masking inventory details must expose mappings");

            HttpResponse<String> inventoryReviewPage = send(client, port,
                    "/inventories/81", "GET");
            require(inventoryReviewPage.statusCode() == 200,
                    "masking inventory review page status must be 200");
            require(inventoryReviewPage.body().contains("id=\"mapping-table\"")
                            && inventoryReviewPage.body().contains("const inventoryId='81'"),
                    "masking inventory review route must render the imported CSV review page");

            HttpResponse<String> csvTemplate = send(client, port,
                    "/api/v1/inventory-csv/template", "GET");
            require(csvTemplate.statusCode() == 200, "CSV template status must be 200");
            require(csvTemplate.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"),
                    "CSV template content type must be CSV");
            require(csvTemplate.body().contains("RULESET_NAME,TABLE_OWNER,TABLE_NAME"),
                    "CSV template must use the ruleset inventory header");

            HttpResponse<String> csvExport = send(client, port,
                    "/api/v1/inventories/81/export.csv", "GET");
            require(csvExport.statusCode() == 200, "CSV export status must be 200");
            require(csvExport.body().contains("\"APP\",\"CUSTOMER\",\"EMAIL\""),
                    "CSV export must contain the masking mapping");

            HttpResponse<String> csvImport = sendBody(client, port,
                    "/api/v1/inventory-csv/imports/9",
                    "RULESET_NAME,TABLE_OWNER,TABLE_NAME,COLUMN_NAME,DATA_TYPE,ALGORITHM,IS_MASKED,CRITERIA\r\n"
                    + "CSV_MASKING,APP,CUSTOMER,EMAIL,VARCHAR2,NULLIFY,Y,ALL\r\n");
            require(csvImport.statusCode() == 201, "CSV import status must be 201");
            require(csvImport.body().contains("\"inventoryCode\":\"CSV_MASKING\""),
                    "CSV import must create the requested draft");

            HttpResponse<String> rulesetList = send(client, port, "/api/v1/rulesets", "GET");
            require(rulesetList.statusCode() == 200, "ruleset list status must be 200");
            require(rulesetList.body().contains("\"code\":\"CUSTOMER_MASKING\"")
                            && rulesetList.body().contains("\"technology\":\"ORACLE\""),
                    "ruleset list must contain its policy");

            HttpResponse<String> rulesetDetails = send(client, port, "/api/v1/rulesets/61", "GET");
            require(rulesetDetails.statusCode() == 200, "ruleset details status must be 200");
            require(rulesetDetails.body().contains("\"columnName\":\"EMAIL\""),
                    "ruleset details must contain its inventory");

            HttpResponse<String> rulesetPage = send(client, port, "/rulesets/61", "GET");
            require(rulesetPage.statusCode() == 200, "rule set page status must be 200");
            require(rulesetPage.body().contains("id=\"rs-expand-all\"")
                            && rulesetPage.body().contains("initializeColumnTree")
                            && rulesetPage.body().contains("id=\"rs-export-sql\"")
                            && rulesetPage.body().contains("/export.sql?versionId=")
                            && rulesetPage.body().contains("payload.message||('HTTP '+response.status)")
                            && rulesetPage.body().contains("renderRuleParameters")
                            && rulesetPage.body().contains("?'password':'text'"),
                    "column inventory must provide an expandable table tree");

            HttpResponse<String> rulesetCatalog = send(client, port,
                    "/api/v1/rulesets/61/catalog?versionId=81", "GET");
            require(rulesetCatalog.statusCode() == 200
                            && rulesetCatalog.body().contains("\"parameters\":[\"key_material\"]")
                            && rulesetCatalog.body().contains(
                                    "\"configuredParameters\":[\"key_material\"]")
                            && rulesetCatalog.body().contains(
                                    "\"compatibleAlgorithmCodes\":[\"NULLIFY\",\"HMAC_SHA256\"]"),
                    "ruleset catalog must expose parameter metadata without its value");
            HttpResponse<String> savedRules = sendPutBody(client, port,
                    "/api/v1/rulesets/61/inventory?versionId=81",
                    "COLUMN\t13\tHMAC_SHA256\t\tkey_material=bank%2Bkey");
            require(savedRules.statusCode() == 200
                            && "bank+key".equals(savedSelections.get().get(0)
                                    .parameters().get("key_material")),
                    "ruleset inventory must decode and forward algorithm parameters");

            HttpResponse<String> rulesetCsv = send(client, port,
                    "/api/v1/rulesets/61/inventory.csv?versionId=81", "GET");
            require(rulesetCsv.statusCode() == 200, "ruleset CSV status must be 200");
            require(rulesetCsv.body().contains("\"CUSTOMER_MASKING\",\"APP\",\"CUSTOMER\""),
                    "ruleset CSV must contain the inventory entry");

            HttpResponse<String> rulesetExport = send(client, port,
                    "/api/v1/rulesets/61/export.csv?versionId=81", "GET");
            require(rulesetExport.statusCode() == 200, "rule set export status must be 200");
            require(rulesetExport.headers().firstValue("Content-Disposition").orElse("")
                            .contains("CUSTOMER_MASKING-v1-ruleset.csv"),
                    "rule set export must expose a versioned CSV filename");
            require(rulesetExport.body().contains("\"CUSTOMER_MASKING\",\"APP\",\"CUSTOMER\""),
                    "rule set export must contain its masking rules");

            HttpResponse<String> rulesetSql = send(client, port,
                    "/api/v1/rulesets/61/export.sql?versionId=81", "GET");
            require(rulesetSql.statusCode() == 200, "rule set SQL export status must be 200");
            require(rulesetSql.headers().firstValue("Content-Type").orElse("")
                            .startsWith("application/sql")
                            && rulesetSql.headers().firstValue("Content-Disposition").orElse("")
                            .contains("CUSTOMER_MASKING-v1-oracle.sql"),
                    "rule set SQL export must be a versioned SQL attachment");
            require(rulesetSql.body().contains("UPDATE \"APP\".\"CUSTOMER\""),
                    "rule set SQL export must contain generated updates");

            HttpResponse<String> createdDraft = sendBody(client, port,
                    "/api/v1/rulesets/61/draft?versionId=81", "");
            require(createdDraft.statusCode() == 201, "ruleset draft creation status must be 201");
            require(createdDraft.body().contains("\"versionId\":82")
                            && createdDraft.body().contains("\"status\":\"DRAFT\""),
                    "ruleset draft creation must expose the new editable version");

            HttpResponse<String> deletedVersion = send(client, port,
                    "/api/v1/rulesets/61/versions/81", "DELETE");
            require(deletedVersion.statusCode() == 200
                            && deletedVersion.body().contains("\"status\":\"DELETED\""),
                    "latest ruleset version deletion must be exposed");

            HttpResponse<String> createdJob = sendBody(client, port,
                    "/api/v1/rulesets/61/jobs?reference=NEW_JOB&versionId=81&parallelDegree=4"
                            + "&executionMode=AUTO&chunkSize=100000&chunkThreshold=1000000", "");
            require(createdJob.statusCode() == 201, "ruleset job creation status must be 201");
            require(createdJob.body().contains("\"status\":\"CREATED\""),
                    "ruleset job creation must expose its status");

            HttpResponse<String> algorithms = send(client, port, "/api/v1/algorithms", "GET");
            require(algorithms.statusCode() == 200, "algorithm options status must be 200");
            require(algorithms.body().contains("\"code\":\"NULLIFY\""),
                    "algorithm options must expose available algorithms");

            HttpResponse<String> algorithmsPage = send(client, port, "/algorithms", "GET");
            require(algorithmsPage.statusCode() == 200
                            && algorithmsPage.body().contains("id=\"algorithm-template\""),
                    "algorithms page must expose template-based creation");

            HttpResponse<String> clonedAlgorithm = sendBody(client, port,
                    "/api/v1/algorithms?template=NULLIFY&code=CLONED_NULLIFY&name=Cloned%20nullify",
                    "");
            require(clonedAlgorithm.statusCode() == 201,
                    "algorithm clone status must be 201");
            require(clonedAlgorithm.body().contains("\"code\":\"CLONED_NULLIFY\""),
                    "algorithm clone must expose its code");

            HttpResponse<String> resolve = sendBody(client, port,
                    "/api/v1/inventories/81/mappings/91/resolve?algorithm=NULLIFY&parameters=", "");
            require(resolve.statusCode() == 200, "mapping resolution status must be 200");
            require(resolve.body().contains("\"reviewItems\":0"),
                    "mapping resolution must expose remaining reviews");

            HttpResponse<String> resolveAll = sendBody(client, port,
                    "/api/v1/inventories/81/resolve-all", "");
            require(resolveAll.statusCode() == 200 && resolveAll.body().contains("\"reviewItems\":2"),
                    "bulk mapping resolution must expose remaining individual reviews");

            HttpResponse<String> publish = sendBody(client, port,
                    "/api/v1/inventories/81/publish", "");
            require(publish.statusCode() == 200, "inventory publication status must be 200");
            require(publish.body().contains("\"status\":\"PUBLISHED\""),
                    "inventory publication must expose published status");

            HttpResponse<String> deletedRuleset = send(
                    client, port, "/api/v1/rulesets/61", "DELETE");
            require(deletedRuleset.statusCode() == 200,
                    "ruleset deletion status must be 200");
            require(deletedRuleset.body().contains("\"status\":\"DELETED\""),
                    "ruleset deletion must expose deleted status");

            HttpResponse<String> missingRulesetDelete = send(
                    client, port, "/api/v1/rulesets/999", "DELETE");
            require(missingRulesetDelete.statusCode() == 404,
                    "missing ruleset deletion status must be 404");


            HttpResponse<String> missingJob = send(client, port, "/api/v1/jobs/MISSING", "GET");
            require(missingJob.statusCode() == 404, "missing job status must be 404");

            HttpResponse<String> wrongMethod = send(client, port, "/api/v1/health", "POST");
            require(wrongMethod.statusCode() == 405, "POST health status must be 405");
            require(wrongMethod.headers().firstValue("Allow").orElse("").equals("GET"),
                    "405 response must advertise GET");

            HttpResponse<String> missing = send(client, port, "/api/v1/missing", "GET");
            require(missing.statusCode() == 404, "unknown route status must be 404");
        } finally {
            server.stop();
            httpExecutor.shutdownNow();
        }

        PrivonymUser administrator = new PrivonymUser(1, "ADMIN", "Privonym Admin",
                true, true, ApplicationSecurity.AVAILABLE_PRIVILEGES);
        ApplicationSecurity security = new ApplicationSecurity() {
            @Override public boolean isInitialized() { return true; }
            @Override public PrivonymUser createInitialAdministrator(String username,String displayName,char[] password) { return administrator; }
            @Override public PrivonymUser authenticate(String username,char[] password) {
                if(List.of("EDITOR","OTHER").contains(username)&&"Valid-password1!".equals(new String(password)))
                    return new PrivonymUser(username.equals("EDITOR")?3:4,username,username,false,true,Set.of(ApplicationSecurity.RULESET_MODIFY_OWN));
                if ("MANAGER".equals(username) && "Valid-password1!".equals(new String(password)))
                    return new PrivonymUser(2,"MANAGER","Manager",false,true,Set.of(ApplicationSecurity.USER_MANAGE));
                if (!"ADMIN".equalsIgnoreCase(username) || !"Valid-password1!".equals(new String(password)))
                    throw new IllegalArgumentException("invalid");
                return administrator;
            }
            @Override public List<PrivonymUser> listUsers() { return List.of(administrator); }
            @Override public PrivonymUser createUser(String actor,String username,String displayName,char[] password,boolean admin,Set<String> privileges) { return new PrivonymUser(2,username,displayName,admin,true,privileges); }
            @Override public PrivonymUser updateUserPrivileges(String actor,long userId,boolean admin,Set<String> privileges) { return new PrivonymUser(userId,"USER","User",admin,true,privileges); }
            @Override public void setUserEnabled(String actor,long userId,boolean enabled) { }
            @Override public boolean ownsRuleset(long userId,long rulesetId) { return userId!=4&&(userId!=3||rulesetId==61); }
            @Override public boolean ownsRulesetVersion(long userId,long versionId) { return true; }
            @Override public void assignRulesetOwner(long rulesetId,long userId) { }
            @Override public void assignRulesetVersionOwner(long versionId,long userId) { throw new AssertionError("Discovery draft must preserve existing Rule Set owner"); }
            @Override public void record(String username,String action,String type,String reference,String outcome) { }
            @Override public void close() { }
        };
        RepositoryLogin connectedRepository = new RepositoryLogin() {
            @Override public boolean isConnected() { return true; }
            @Override public void login(char[] password) { }
            @Override public void logout() { }
        };
        Server securedServer = PrivonymHttpServer.create("127.0.0.1", 0, jobs, environments,
                inventory, draftInventory, maskingInventories, inventoryManagement, rulesets,
                discovery, connectedRepository, security);
        try {
            securedServer.start();
            int securedPort = ((ServerConnector) securedServer.getConnectors()[0]).getLocalPort();
            HttpClient securedClient = HttpClient.newHttpClient();
            HttpResponse<String> anonymous = send(securedClient, securedPort, "/api/v1/jobs", "GET");
            require(anonymous.statusCode() == 401, "secured API must reject requests without a Privonym session");
            require(send(securedClient, securedPort, "/api/v1/health", "GET").statusCode() == 200,
                    "anonymous liveness must work when application security is enabled");
            require(send(securedClient, securedPort, "/api/v1/health", "POST").statusCode() == 405,
                    "anonymous liveness must reject mutations");
            HttpRequest oversized = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + securedPort + "/api/v1/security/login"))
                    .POST(HttpRequest.BodyPublishers.ofString("X".repeat(8 * 1024 * 1024 + 1))).build();
            require(securedClient.send(oversized, HttpResponse.BodyHandlers.ofString()).statusCode() == 413,
                    "oversized HTTP requests must be rejected before login processing");
            var manager = sendForm(securedClient, securedPort, "/api/v1/security/login",
                    "username=MANAGER&password=Valid-password1%21", "");
            require(manager.statusCode() == 200, "delegated manager login");
            String managerCookie = manager.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
            for (String operation : List.of("status", "privileges")) {
                HttpRequest mutation = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + securedPort + "/api/v1/security/users/1/" + operation))
                        .header("Cookie", managerCookie)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .PUT(HttpRequest.BodyPublishers.ofString("enabled=false&administrator=false&privileges="))
                        .build();
                require(securedClient.send(mutation, HttpResponse.BodyHandlers.ofString()).statusCode() == 409,
                        "delegated manager must not modify an existing administrator: " + operation);
            }
            HttpResponse<String> signedIn = sendForm(securedClient, securedPort,
                    "/api/v1/security/login", "username=ADMIN&password=Valid-password1%21", "");
            require(signedIn.statusCode() == 200, "Privonym application login must succeed");
            String cookie = signedIn.headers().firstValue("Set-Cookie").orElse("").split(";", 2)[0];
            require(cookie.startsWith("PRIVONYM_SESSION="), "login must issue an application session cookie");
            HttpResponse<String> users = sendForm(securedClient, securedPort,
                    "/api/v1/security/users", "", cookie);
            require(users.statusCode() == 200 && users.body().contains("\"username\":\"ADMIN\""),
                    "administrator session must list Privonym users");
            for(String account:List.of("EDITOR","OTHER")) {
                HttpResponse<String> login=sendForm(securedClient,securedPort,"/api/v1/security/login",
                        "username="+account+"&password=Valid-password1%21","");
                String ownCookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
                HttpRequest draftRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+securedPort+"/api/v1/discovery/versions/61"))
                        .header("Cookie",ownCookie).POST(HttpRequest.BodyPublishers.ofString("12,NULLIFY")).build();
                require(securedClient.send(draftRequest,HttpResponse.BodyHandlers.ofString()).statusCode()==(account.equals("EDITOR")?201:403),
                        "Discovery version creation must enforce Rule Set ownership and MODIFY_OWN");
            }
        } finally {
            securedServer.stop();
        }

        System.out.println("Privonym HTTP adapter smoke check passed.");
        System.exit(0);
    }

    private static HttpResponse<String> send(HttpClient client, int port, String path, String method)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> sendBody(HttpClient client, int port, String path, String body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> sendPutBody(HttpClient client, int port, String path,
            String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> sendForm(HttpClient client, int port, String path,
            String body, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (!cookie.isBlank()) request.header("Cookie", cookie);
        if (body.isBlank()) request.GET();
        else request.POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
