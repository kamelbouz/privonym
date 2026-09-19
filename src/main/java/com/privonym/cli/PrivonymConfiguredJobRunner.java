package com.privonym.cli;

import com.privonym.application.service.JobReport;
import com.privonym.repository.oracle.OracleRulesetAdministrationAdapter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Properties;

/** Non-interactive Job entry point for schedulers such as Delphix. */
public final class PrivonymConfiguredJobRunner {
    private PrivonymConfiguredJobRunner() { }

    public static void main(String[] arguments) {
        int exitCode = runSafely(arguments);
        if (exitCode != 0) System.exit(exitCode);
    }

    private static int runSafely(String[] arguments) {
        if (arguments.length < 1 || arguments.length > 2) {
            System.err.println("Usage: privonym-job JOB_ID [CONFIG_FILE]");
            return 2;
        }
        try {
            long jobId = Long.parseLong(arguments[0]);
            if (jobId <= 0) throw new IllegalArgumentException("JOB_ID must be positive");
            Path configurationPath = arguments.length == 2 ? Path.of(arguments[1]) : configuredPath();
            Properties configuration = load(configurationPath);
            String url = repositoryUrl(configuration);
            String user = required(configuration, "repository.user");
            char[] password = required(configuration, "repository.password").toCharArray();
            try {
                DriverManager.setLoginTimeout(15);
                try (OracleRulesetAdministrationAdapter adapter = new OracleRulesetAdministrationAdapter(url, user, password)) {
                    JobReport result = adapter.executeJobToCompletion(jobId);
                    System.out.printf("{\"jobId\":%d,\"status\":\"SUCCEEDED\",\"succeededSteps\":%d,\"rowsProcessed\":%d}%n",
                            result.jobId().value(), result.succeededSteps(), result.rowsProcessed());
                }
            } finally {
                Arrays.fill(password, '\0');
            }
            return 0;
        } catch (NumberFormatException error) {
            System.err.println("ERROR: JOB_ID must be a positive number");
            return 2;
        } catch (Exception error) {
            System.err.println("ERROR: " + error.getMessage());
            return 1;
        }
    }

    private static Path configuredPath() {
        String value = System.getenv("PRIVONYM_JOB_CONFIG");
        return value == null || value.isBlank() ? Path.of("config", "privonym-job.properties") : Path.of(value);
    }

    private static Properties load(Path path) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
        return properties;
    }

    private static String repositoryUrl(Properties properties) {
        String explicit = properties.getProperty("repository.url");
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        String host = required(properties, "repository.host");
        int port;
        try { port = Integer.parseInt(required(properties, "repository.port")); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("repository.port must be numeric"); }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("repository.port must be between 1 and 65535");
        return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + required(properties, "repository.service");
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing configuration property: " + key);
        return value.trim();
    }
}
