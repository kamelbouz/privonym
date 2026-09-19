PRIVONYM WINDOWS UPGRADE - 2.0.20260823
========================================

PURPOSE
-------
This package upgrades an existing Privonym Windows installation and its Oracle
repository. It is not a clean installer.

PRESERVED DATA
--------------
- The existing privonym.properties file is not replaced.
- The repository password is requested interactively and is never stored.
- The existing privonym-secret.key file is not copied or replaced.
- Logs, reports and repository business data are preserved.

PREREQUISITES
-------------
- Run PowerShell as Administrator.
- Java 17 or newer must still be available.
- The Oracle repository user needs the privileges used by the migrations,
  including CREATE TABLE, ALTER TABLE and CREATE INDEX. CREATE TRIGGER is needed
  for the job/ruleset consistency trigger.
- Take the normal database backup required by your organization's change process.
- Stop any manually launched Privonym server. The standard scheduled task is
  stopped automatically.

STANDARD UPGRADE
----------------
1. Copy and extract the ZIP on the Privonym server.
2. Open an elevated PowerShell window in the extracted directory.
3. Run:

   Set-ExecutionPolicy -Scope Process Bypass
   .\Upgrade-Privonym.ps1

The script reads the existing installation metadata from:
  C:\ProgramData\Privonym\installation.json

For a custom installation, specify both locations explicitly:

   .\Upgrade-Privonym.ps1 `
       -InstallDirectory "D:\Applications\Privonym" `
       -DataDirectory "D:\PrivonymData"

The Oracle password is requested during the repository migration. Each SQL
migration is recorded with its SHA-256 checksum in PRIVONYM_SCHEMA_MIGRATION,
so the same upgrade package can be run again safely.

OPTIONS
-------
-SkipRepositoryUpgrade  Replace only the runtime. Use only when a DBA has
                        already applied and verified every bundled migration.
-NoStart                Do not restart the Privonym scheduled task.
-HealthTimeoutSeconds N Wait N seconds for GET /api/v1/health (default: 60).

BACKUP AND ROLLBACK
-------------------
Before changing binaries, the script creates:
  <DataDirectory>\backups\upgrade-YYYYMMDD-HHMMSS

If runtime replacement or the health check fails, the previous binaries are
restored automatically. A manual rollback can be run from the package:

   .\Rollback-Privonym.ps1 `
       -BackupDirectory "C:\ProgramData\Privonym\backups\upgrade-YYYYMMDD-HHMMSS"

Oracle schema migrations are forward-only and are not reversed by binary
rollback. They are additive/idempotent and remain compatible with the previous
runtime. Restore the Oracle backup only through the approved DBA procedure.

POST-UPGRADE CHECKS
-------------------
- GET http://localhost:8080/api/v1/health returns {"status":"UP"}.
- Open http://localhost:8080/login and connect to the repository.
- Verify Overview, Rule Sets, Jobs, Monitor, Scheduler and About.
- Execute a non-production validation job, then open its Report action.
- Review C:\ProgramData\Privonym\logs\privonym-server.log.

See RELEASE-NOTES-2026-08-23.txt for the included functional changes.
