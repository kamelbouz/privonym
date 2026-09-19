package com.privonym.http;

import com.privonym.application.port.in.QueryJobsUseCase;
import com.privonym.application.port.in.QueryEnvironmentsUseCase;
import com.privonym.application.port.in.QueryInventoryUseCase;
import com.privonym.application.port.in.CreateDraftInventoryUseCase;
import com.privonym.application.port.in.QueryMaskingInventoriesUseCase;
import com.privonym.application.port.in.ManageMaskingInventoryUseCase;
import com.privonym.application.port.in.RulesetAdministrationUseCase;
import com.privonym.application.port.in.DiscoveryUseCase;
import com.privonym.application.service.DataSourceInventory;
import com.privonym.application.service.EnvironmentDetails;
import com.privonym.application.service.EnvironmentSummary;
import com.privonym.application.service.JobDetails;
import com.privonym.application.service.JobSummary;
import com.privonym.application.service.OracleSqlInventoryPreviewService;
import com.privonym.application.service.SqlServerSqlInventoryPreviewService;
import com.privonym.application.service.PostgresqlSqlInventoryPreviewService;
import com.privonym.application.service.MultiDialectSqlInventoryPreviewService;
import java.net.InetSocketAddress;
import java.util.List;

import org.eclipse.jetty.server.Server;

/** Embedded Jetty bootstrap for the Privonym REST API. */
public final class PrivonymHttpServer {
    private PrivonymHttpServer() {
    }

    public static Server create(String host, int port) {
        QueryJobsUseCase unavailable = new QueryJobsUseCase() {
            @Override
            public List<JobSummary> listJobs(int limit) {
                throw new IllegalStateException("No job repository is configured");
            }

            @Override
            public JobDetails getJob(String reference) {
                throw new IllegalStateException("No job repository is configured");
            }
        };
        QueryEnvironmentsUseCase unavailableEnvironments = new QueryEnvironmentsUseCase() {
            @Override
            public List<EnvironmentSummary> listEnvironments() {
                throw new IllegalStateException("No environment repository is configured");
            }

            @Override
            public EnvironmentDetails getEnvironment(String code) {
                throw new IllegalStateException("No environment repository is configured");
            }

            @Override public EnvironmentSummary createEnvironment(String code,String name,String description) {
                throw new IllegalStateException("No environment repository is configured");
            }
        };
        QueryInventoryUseCase unavailableInventory = dataSourceId -> {
            throw new IllegalStateException("No inventory repository is configured");
        };
        return create(host, port, unavailable, unavailableEnvironments, unavailableInventory);
    }

    public static Server create(String host, int port, QueryJobsUseCase jobs,
            QueryEnvironmentsUseCase environments, QueryInventoryUseCase inventory) {
        return create(host, port, jobs, environments, inventory,
                (dataSourceId, code, name, sql) -> {
                    throw new IllegalStateException("No draft inventory repository is configured");
                }, unavailableMaskingInventories(), unavailableInventoryManagement());
    }

    public static Server create(String host, int port, QueryJobsUseCase jobs,
            QueryEnvironmentsUseCase environments, QueryInventoryUseCase inventory,
            CreateDraftInventoryUseCase draftInventory) {
        return create(host, port, jobs, environments, inventory, draftInventory,
                unavailableMaskingInventories(), unavailableInventoryManagement());
    }

    public static Server create(String host, int port, QueryJobsUseCase jobs,
            QueryEnvironmentsUseCase environments, QueryInventoryUseCase inventory,
            CreateDraftInventoryUseCase draftInventory,
            QueryMaskingInventoriesUseCase maskingInventories) {
        return create(host, port, jobs, environments, inventory, draftInventory,
                maskingInventories, unavailableInventoryManagement());
    }

    public static Server create(String host, int port, QueryJobsUseCase jobs,
            QueryEnvironmentsUseCase environments, QueryInventoryUseCase inventory,
            CreateDraftInventoryUseCase draftInventory,
            QueryMaskingInventoriesUseCase maskingInventories,
            ManageMaskingInventoryUseCase inventoryManagement) {
        return create(host, port, jobs, environments, inventory, draftInventory,
                maskingInventories, inventoryManagement, unavailableRulesets());
    }

    public static Server create(String host, int port, QueryJobsUseCase jobs,
            QueryEnvironmentsUseCase environments, QueryInventoryUseCase inventory,
            CreateDraftInventoryUseCase draftInventory,
            QueryMaskingInventoriesUseCase maskingInventories,
            ManageMaskingInventoryUseCase inventoryManagement,
            RulesetAdministrationUseCase rulesets) {
        return create(host, port, jobs, environments, inventory, draftInventory,
                maskingInventories, inventoryManagement, rulesets, unavailableDiscovery(), null);
    }

    public static Server create(String host, int port, QueryJobsUseCase jobs,
            QueryEnvironmentsUseCase environments, QueryInventoryUseCase inventory,
            CreateDraftInventoryUseCase draftInventory,
            QueryMaskingInventoriesUseCase maskingInventories,
            ManageMaskingInventoryUseCase inventoryManagement,
            RulesetAdministrationUseCase rulesets, DiscoveryUseCase discovery,
            RepositoryLogin repositoryLogin) {
        return create(host, port, jobs, environments, inventory, draftInventory,
                maskingInventories, inventoryManagement, rulesets, discovery, repositoryLogin, null);
    }

    public static Server create(String host, int port, QueryJobsUseCase jobs,
            QueryEnvironmentsUseCase environments, QueryInventoryUseCase inventory,
            CreateDraftInventoryUseCase draftInventory,
            QueryMaskingInventoriesUseCase maskingInventories,
            ManageMaskingInventoryUseCase inventoryManagement,
            RulesetAdministrationUseCase rulesets, DiscoveryUseCase discovery,
            RepositoryLogin repositoryLogin, ApplicationSecurity applicationSecurity) {
        Server server = new Server(new InetSocketAddress(host, port));
        server.setHandler(new PrivonymHttpHandler(jobs, environments, inventory,
                new MultiDialectSqlInventoryPreviewService(
                        new OracleSqlInventoryPreviewService(),
                        new SqlServerSqlInventoryPreviewService(),
                        new PostgresqlSqlInventoryPreviewService()), draftInventory, maskingInventories,
                inventoryManagement, rulesets, discovery, repositoryLogin, applicationSecurity));
        return server;
    }

    private static DiscoveryUseCase unavailableDiscovery() {
        return new DiscoveryUseCase() {
            public List<com.privonym.application.service.DiscoveryConnection> listConnections() { throw new IllegalStateException("No discovery repository is configured"); }
            public List<com.privonym.application.service.EnvironmentOption> listEnvironmentOptions() { throw new IllegalStateException("No discovery repository is configured"); }
            public com.privonym.application.service.ConnectionCreationResult createConnection(long environmentId,String name,String technology,String schema,String host,int port,String service,String database,String user,String provider,String reference,char[] password) { throw new IllegalStateException("No discovery repository is configured"); }
            public void deleteConnection(long id) { throw new IllegalStateException("No discovery repository is configured"); }
            public void updateConnection(long id,com.privonym.application.service.ConnectionUpdateCommand command) { throw new IllegalStateException("No discovery repository is configured"); }
            public com.privonym.application.service.DiscoveryScan scan(long id) { throw new IllegalStateException("No discovery repository is configured"); }
            public com.privonym.application.service.DiscoveryCreationResult createRuleset(long id, String code, String name,
                    List<com.privonym.application.service.DiscoverySelection> selections) { throw new IllegalStateException("No discovery repository is configured"); }
            public com.privonym.application.service.DiscoveryCreationResult createEmptyRuleset(long id,String code,String name) { throw new IllegalStateException("No discovery repository is configured"); }
        };
    }

    private static RulesetAdministrationUseCase unavailableRulesets() {
        return new RulesetAdministrationUseCase() {
            @Override
            public List<com.privonym.application.service.RulesetSummary> listRulesets() {
                throw new IllegalStateException("No ruleset repository is configured");
            }

            @Override
            public com.privonym.application.service.RulesetDetails getRuleset(
                    long rulesetId, Long versionId) {
                throw new IllegalStateException("No ruleset repository is configured");
            }

            @Override public void deleteRuleset(long rulesetId) {
                throw new IllegalStateException("No ruleset repository is configured");
            }

            @Override public void deleteVersion(long rulesetId,long versionId) {
                throw new IllegalStateException("No ruleset repository is configured");
            }

            @Override public com.privonym.application.service.RulesetVersionView createDraft(long rulesetId,long sourceVersionId) { throw new IllegalStateException("No ruleset repository is configured"); }
            @Override public com.privonym.application.service.RulesetCloneResult cloneRuleset(long sourceRulesetId,long targetDataSourceId,String name) { throw new IllegalStateException("No ruleset repository is configured"); }

            @Override public List<com.privonym.application.service.RulesetCatalogColumn> listCatalog(long rulesetId,long versionId) { throw new IllegalStateException("No ruleset repository is configured"); }
            @Override public int saveInventory(long rulesetId,long versionId,List<com.privonym.application.service.RulesetInventorySelection> selections) { throw new IllegalStateException("No ruleset repository is configured"); }
            @Override public void deleteJob(String reference) { throw new IllegalStateException("No ruleset repository is configured"); }
            @Override public com.privonym.application.service.JobUpdateResult updateJob(String currentReference,String newReference,long rulesetId,int parallelDegree,String executionMode,int chunkSize,long chunkThreshold) { throw new IllegalStateException("No ruleset repository is configured"); }
            @Override public com.privonym.application.service.ExecuteJobResult executeJob(String reference) { throw new IllegalStateException("No ruleset repository is configured"); }
            @Override public com.privonym.application.service.ExecuteJobResult executeJob(long jobId) { throw new IllegalStateException("No ruleset repository is configured"); }
            @Override public void saveJobScript(String reference,String phase,String fileName,String content) { throw new IllegalStateException("No ruleset repository is configured"); }
            @Override public void cancelJob(long jobId) { throw new IllegalStateException("No ruleset repository is configured"); }
            @Override public com.privonym.application.service.RulesetSqlExport exportSql(long rulesetId,long versionId) { throw new IllegalStateException("No ruleset repository is configured"); }

            @Override
            public com.privonym.application.service.JobCreationResult createJob(long rulesetId,
                    long versionId, String reference, int parallelDegree, String executionMode,
                    int chunkSize, long chunkThreshold) {
                throw new IllegalStateException("No ruleset repository is configured");
            }
        };
    }

    private static QueryMaskingInventoriesUseCase unavailableMaskingInventories() {
        return new QueryMaskingInventoriesUseCase() {
            @Override
            public List<com.privonym.application.service.MaskingInventorySummary> listInventories() {
                throw new IllegalStateException("No masking inventory repository is configured");
            }

            @Override
            public com.privonym.application.service.MaskingInventoryDetails getInventory(long id) {
                throw new IllegalStateException("No masking inventory repository is configured");
            }
        };
    }

    private static ManageMaskingInventoryUseCase unavailableInventoryManagement() {
        return new ManageMaskingInventoryUseCase() {
            @Override
            public List<com.privonym.application.service.MaskingAlgorithmOption> listAlgorithms() {
                throw new IllegalStateException("No inventory management repository is configured");
            }

            @Override
            public com.privonym.application.service.InventoryMutationResult resolveMapping(
                    long versionId, long mappingId, String code, String parameters) {
                throw new IllegalStateException("No inventory management repository is configured");
            }

            @Override
            public com.privonym.application.service.InventoryMutationResult resolveAllMappings(long versionId) {
                throw new IllegalStateException("No inventory management repository is configured");
            }

            @Override
            public com.privonym.application.service.InventoryMutationResult acceptMapping(
                    long versionId, long mappingId) {
                throw new IllegalStateException("No inventory management repository is configured");
            }

            @Override
            public com.privonym.application.service.InventoryMutationResult publish(long versionId) {
                throw new IllegalStateException("No inventory management repository is configured");
            }
            @Override public com.privonym.application.service.AlgorithmCreationResult cloneAlgorithm(String template,String code,String name,List<String> values){throw new IllegalStateException("No inventory management repository is configured");}
        };
    }

    public static void main(String[] arguments) throws Exception {
        ServerConfiguration configuration = ServerConfiguration.load();
        Server server = create(configuration.host(), configuration.port());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(server), "privonym-http-shutdown"));

        server.start();
        System.out.printf("Privonym HTTP server listening on http://%s:%d%n",
                configuration.host(), configuration.port());
        server.join();
    }

    private static void stop(Server server) {
        try {
            server.stop();
        } catch (Exception exception) {
            System.err.println("Unable to stop the Privonym HTTP server cleanly: " + exception.getMessage());
        }
    }
}
