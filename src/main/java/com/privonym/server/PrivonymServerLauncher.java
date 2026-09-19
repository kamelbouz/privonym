package com.privonym.server;

import com.privonym.application.port.in.CreateDraftInventoryUseCase;
import com.privonym.application.port.in.QueryEnvironmentsUseCase;
import com.privonym.application.port.in.QueryInventoryUseCase;
import com.privonym.application.port.in.QueryJobsUseCase;
import com.privonym.application.port.in.DiscoveryUseCase;
import com.privonym.application.service.JobQueryService;
import com.privonym.application.service.EnvironmentQueryService;
import com.privonym.application.service.InventoryQueryService;
import com.privonym.application.service.CreateDraftInventoryService;
import com.privonym.application.service.OracleSqlInventoryPreviewService;
import com.privonym.application.service.SqlServerSqlInventoryPreviewService;
import com.privonym.application.service.PostgresqlSqlInventoryPreviewService;
import com.privonym.application.service.MultiDialectSqlInventoryPreviewService;
import com.privonym.application.service.MaskingInventoryQueryService;
import com.privonym.application.service.MaskingInventoryManagementService;
import com.privonym.application.service.RulesetAdministrationService;
import com.privonym.http.PrivonymHttpServer;
import com.privonym.http.RepositoryLogin;
import com.privonym.http.ServerConfiguration;
import com.privonym.repository.oracle.OracleJobQueryAdapter;
import com.privonym.repository.oracle.OracleEnvironmentQueryAdapter;
import com.privonym.repository.oracle.OracleDraftInventoryAdapter;
import com.privonym.repository.oracle.OracleMaskingInventoryQueryAdapter;
import com.privonym.repository.oracle.OracleMaskingInventoryManagementAdapter;
import com.privonym.repository.oracle.OracleRulesetAdministrationAdapter;
import com.privonym.repository.oracle.OracleDiscoveryAdapter;
import com.privonym.repository.oracle.OracleApplicationSecurityAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.Server;

/** Production composition root for the HTTP and Oracle adapters. */
public final class PrivonymServerLauncher {
    private PrivonymServerLauncher() {
    }

    public static void main(String[] arguments) throws Exception {
        Properties properties = loadProperties();
        ServerConfiguration serverConfiguration = ServerConfiguration.load();
        String repositoryUrl = required(properties, "repository.url");
        String repositoryUser = required(properties, "repository.user");
        DriverManager.setLoginTimeout(15);
        RepositorySession session = new RepositorySession(repositoryUrl, repositoryUser);
        Server server = PrivonymHttpServer.create(serverConfiguration.host(),
                serverConfiguration.port(), session.jobs(), session.environments(), session.inventory(),
                session.draftInventory(), session.maskingInventories(), session.inventoryManagement(),
                session.rulesets(), session.discovery(), session, session.security());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(server, session),
                "privonym-server-shutdown"));

        server.start();
        System.out.printf("Privonym server listening on http://%s:%d/%n",
                serverConfiguration.host(), serverConfiguration.port());
        System.out.printf("Open http://%s:%d/login to connect the Oracle repository.%n",
                serverConfiguration.host(), serverConfiguration.port());
        server.join();
    }

    private static Properties loadProperties() throws IOException {
        String configuredPath = System.getenv("PRIVONYM_CONFIG");
        Path path = configuredPath == null || configuredPath.isBlank()
                ? Path.of("config", "privonym.properties") : Path.of(configuredPath);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing configuration property: " + key);
        }
        return value.trim();
    }

    private static void stop(Server server, RepositorySession session) {
        try {
            server.stop();
        } catch (Exception error) {
            System.err.println("Unable to stop HTTP server cleanly: " + error.getMessage());
        } finally {
            session.close();
        }
    }

    private static final class RepositorySession implements RepositoryLogin, AutoCloseable {
        private final String url;
        private final String user;
        private final AtomicReference<QueryJobsUseCase> jobs = new AtomicReference<>();
        private final AtomicReference<QueryEnvironmentsUseCase> environments = new AtomicReference<>();
        private final AtomicReference<QueryInventoryUseCase> inventory = new AtomicReference<>();
        private final AtomicReference<CreateDraftInventoryUseCase> draftInventory = new AtomicReference<>();
        private final AtomicReference<com.privonym.application.port.in.QueryMaskingInventoriesUseCase> masking = new AtomicReference<>();
        private final AtomicReference<com.privonym.application.port.in.ManageMaskingInventoryUseCase> management = new AtomicReference<>();
        private final AtomicReference<com.privonym.application.port.in.RulesetAdministrationUseCase> rulesets = new AtomicReference<>();
        private final AtomicReference<DiscoveryUseCase> discovery = new AtomicReference<>();
        private final AtomicReference<com.privonym.http.ApplicationSecurity> security = new AtomicReference<>();
        private final List<AutoCloseable> resources = new ArrayList<>();

        RepositorySession(String url, String user) { this.url = url; this.user = user; }
        QueryJobsUseCase jobs() { return delegate(QueryJobsUseCase.class, jobs); }
        QueryEnvironmentsUseCase environments() { return delegate(QueryEnvironmentsUseCase.class, environments); }
        QueryInventoryUseCase inventory() { return delegate(QueryInventoryUseCase.class, inventory); }
        CreateDraftInventoryUseCase draftInventory() { return delegate(CreateDraftInventoryUseCase.class, draftInventory); }
        com.privonym.application.port.in.QueryMaskingInventoriesUseCase maskingInventories() { return delegate(com.privonym.application.port.in.QueryMaskingInventoriesUseCase.class, masking); }
        com.privonym.application.port.in.ManageMaskingInventoryUseCase inventoryManagement() { return delegate(com.privonym.application.port.in.ManageMaskingInventoryUseCase.class, management); }
        com.privonym.application.port.in.RulesetAdministrationUseCase rulesets() { return delegate(com.privonym.application.port.in.RulesetAdministrationUseCase.class, rulesets); }
        DiscoveryUseCase discovery() { return delegate(DiscoveryUseCase.class, discovery); }
        com.privonym.http.ApplicationSecurity security() {
            return delegate(com.privonym.http.ApplicationSecurity.class, security);
        }

        @Override public boolean isConnected() { return jobs.get() != null; }

        @Override public void logout() { close(); }

        @Override public synchronized void login(char[] password) {
            if (isConnected()) return;
            try (Connection connection = DriverManager.getConnection(url, user, new String(password))) {
                if (!connection.isValid(5)) {
                    throw new IllegalStateException("Repository connection validation failed");
                }
                OracleJobQueryAdapter jobAdapter = new OracleJobQueryAdapter(url, user, password);
                OracleEnvironmentQueryAdapter environmentAdapter = new OracleEnvironmentQueryAdapter(url, user, password);
                OracleDraftInventoryAdapter draftAdapter = new OracleDraftInventoryAdapter(url, user, password);
                OracleMaskingInventoryQueryAdapter maskingAdapter = new OracleMaskingInventoryQueryAdapter(url, user, password);
                OracleMaskingInventoryManagementAdapter managementAdapter = new OracleMaskingInventoryManagementAdapter(url, user, password);
                OracleRulesetAdministrationAdapter rulesetAdapter = new OracleRulesetAdministrationAdapter(url, user, password);
                OracleDiscoveryAdapter discoveryAdapter = new OracleDiscoveryAdapter(url, user, password);
                OracleApplicationSecurityAdapter securityAdapter =
                        new OracleApplicationSecurityAdapter(url, user, password);
                resources.addAll(List.of(jobAdapter, environmentAdapter, draftAdapter, maskingAdapter,
                        managementAdapter, rulesetAdapter, discoveryAdapter, securityAdapter));
                jobs.set(new JobQueryService(jobAdapter));
                environments.set(new EnvironmentQueryService(environmentAdapter));
                inventory.set(new InventoryQueryService(environmentAdapter));
                var transformer = new MultiDialectSqlInventoryPreviewService(
                        new OracleSqlInventoryPreviewService(),
                        new SqlServerSqlInventoryPreviewService(),
                        new PostgresqlSqlInventoryPreviewService());
                draftInventory.set(new CreateDraftInventoryService(transformer, draftAdapter, user));
                masking.set(new MaskingInventoryQueryService(maskingAdapter));
                management.set(new MaskingInventoryManagementService(managementAdapter, user));
                rulesets.set(new RulesetAdministrationService(rulesetAdapter, user));
                discovery.set(discoveryAdapter);
                security.set(securityAdapter);
            } catch (Exception error) {
                close();
                throw new IllegalStateException(error.getMessage(), error);
            }
        }

        @SuppressWarnings("unchecked")
        private static <T> T delegate(Class<T> type, AtomicReference<T> reference) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                T target = reference.get();
                if (target == null) throw new IllegalStateException("Repository login required");
                try { return method.invoke(target, args); }
                catch (InvocationTargetException error) { throw error.getCause(); }
            });
        }

        @Override public synchronized void close() {
            for (AutoCloseable resource : resources) {
                try { resource.close(); } catch (Exception ignored) { }
            }
            resources.clear(); jobs.set(null); environments.set(null); inventory.set(null);
            draftInventory.set(null); masking.set(null); management.set(null); rulesets.set(null);
            discovery.set(null); security.set(null);
        }
    }
}
