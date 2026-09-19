# Privonym Oracle Adapter

Framework-independent and dependency-free Java 17 core for rendering validated
Oracle masking SQL expressions from the algorithm templates stored by Privonym
V2. The project can be built in a fully isolated environment.

Current scope:

- validates and quotes Oracle identifiers;
- validates typed algorithm parameters;
- escapes string values as Oracle SQL literals;
- expands only the supported template placeholders;
- rejects missing, unknown, unused, or unsafe inputs;
- generates and verifies Oracle masking SQL;
- executes a saved, preflight-approved plan in transactional DIRECT mode.

Build and run all checks with Windows PowerShell:

```powershell
.\build.ps1
```

No Maven, Gradle, network access, or package repository is required during the
build. The required Oracle, Jetty, and SLF4J libraries are kept in `lib`. Only
a JDK 17 or newer must be installed.

Supported placeholders:

```text
${column}
${qualified_table}
${param.<name>}
```

The application layer must load published algorithm definitions and parameter
metadata from the V2 repository before invoking the renderer.

## Repository migrations

Database schema changes are never executed by the HTTP server during login.
Apply each file in `database/migrations` once, in filename order, as the
`PRIVONYM` schema owner. The lookup-algorithm model requires:

```sql
@database/migrations/V2_005_algorithm_owned_lookup_oracle.sql
```

The schema owner needs `CREATE TABLE` while this migration is applied. If that
privilege is managed by a DBA, it can be granted only for the migration and
revoked immediately afterwards:

```sql
GRANT CREATE TABLE TO PRIVONYM;
-- Connect as PRIVONYM and execute V2_005_algorithm_owned_lookup_oracle.sql.
REVOKE CREATE TABLE FROM PRIVONYM;
```

Normal Privonym startup and repository login require no DDL privilege.

Migration `V2_008_job_pre_post_scripts_oracle.sql` adds optional SQL scripts to
Jobs. Uploaded `.sql` files are stored as CLOBs in `JOB_SCRIPT`. A PRE script is
committed on the target before masking starts; a POST script is committed after
table validation and before the final report. A script error rolls back that
script transaction and marks the Job `EXECUTION_FAILED`.

## Run a Job from another machine

Keep the Privonym HTTP server bound to `127.0.0.1` and use SSH as the secured
remote transport. The repository must already be connected in the running
server. From a Windows machine, copy `privonym-run-remote.ps1` and run:

```powershell
.\privonym-run-remote.ps1 -Server privonym-host -User windows-user -Job CUSTOMER_MASKING_001
```

The command calls the existing synchronous Job execution endpoint on the
Privonym host and prints its JSON result. A successful result contains the Job
status, executed step count, and processed row count.

The equivalent command from Linux or macOS is:

```bash
ssh windows-user@privonym-host 'curl.exe --silent --show-error --fail-with-body --request POST --header "Accept: application/json" http://127.0.0.1:8080/api/v1/jobs/CUSTOMER_MASKING_001/execute'
```

## Delphix-style non-interactive execution

Copy `config/privonym-job.properties.example` to
`config/privonym-job.properties`, fill in the Oracle repository host, port,
service, username and password, and restrict the file so only the execution
account can read it. Then Delphix can invoke the Job using only its numeric id:

```bat
privonym-job.cmd 42
```

An alternate configuration path can be supplied as the second argument or
through `PRIVONYM_JOB_CONFIG`. Exit code `0` means success, `1` means execution
failure, and `2` means invalid invocation. This runner connects directly to the
repository and does not require the web server to be running or logged in.

## Oracle repository preview

The local repository connection is configured in `config/privonym.properties`.
No password is stored. After building, generate a read-only preview with:

```bat
privonym.cmd preview DEMO-JOB-001
```

To explicitly store the generated SQL and checksum in `JOB_STEP`:

```bat
privonym.cmd preview DEMO-JOB-001 --save
```

The `preview` command connects only to the Privonym repository. It never opens
the target data-source connector and never executes the generated statement.

Verify the saved plan and its SHA-256 checksum in read-only mode with:

```bat
privonym.cmd preflight DEMO-JOB-001
```

Execute the exact saved plan after a successful preflight:

```bat
privonym.cmd execute DEMO-JOB-001 --confirm
```

DIRECT execution uses a distinct target connection and one target transaction.
It commits all anonymization steps together, or rolls them all back on failure.

Validate target row-count invariants and persist validation evidence:

```bat
privonym.cmd validate DEMO-JOB-001
```

Generate the repository-backed final report and close the job:

```bat
privonym.cmd report DEMO-JOB-001
```

## Embedded HTTP server

Build and start the local REST server with:

```bat
powershell -ExecutionPolicy Bypass -File build.ps1
privonym-server.cmd
```

The bind address is configured by `server.host` and `server.port` in
`config/privonym.properties`. Open the web console at:

```text
http://127.0.0.1:8080/
```

The initial REST endpoint is:

```text
GET http://127.0.0.1:8080/api/v1/health
```

### Ruleset inventory CSV exchange

The Inventories page exchanges the column inventory owned by one ruleset.
CSV imports create a new draft ruleset version and never execute masking SQL.

```text
GET  /api/v1/inventory-csv/template
POST /api/v1/inventory-csv/imports/{dataSourceId}
GET  /api/v1/inventories/{rulesetVersionId}/export.csv
```

The CSV header is:

```text
RULESET_NAME,TABLE_OWNER,TABLE_NAME,COLUMN_NAME,DATA_TYPE,ALGORITHM,IS_MASKED,CRITERIA
```
