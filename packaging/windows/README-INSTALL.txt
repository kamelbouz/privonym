PRIVONYM FOR WINDOWS
====================

Prerequisites
-------------
- Windows 10/11 or Windows Server 2019+
- Java 17 or newer in PATH
- Network access to the Oracle repository and Oracle, PostgreSQL or Microsoft
  SQL Server target databases
- An elevated PowerShell console

Installation
------------
1. Extract the complete ZIP archive.
2. Open PowerShell as Administrator in the extracted folder.
3. Run, for example:

   .\Install-Privonym.ps1 `
     -RepositoryUrl "jdbc:oracle:thin:@//oracle-host:1521/PRIVONYM" `
     -RepositoryUser "privonym" `
     -JavaPath "C:\Tools\jdk-17\bin\java.exe" `
     -HttpPort 8080

4. By default the installer initializes an empty Oracle schema. Enter the
   repository password when the Java installer requests it. The password is
   used in memory only and is never written to the package or configuration.
5. Open http://localhost:8080/login and enter the repository password.

JavaPath may reference any supported OpenJDK or commercial Java distribution;
Oracle Java is not required. Use the complete java.exe path when the Java found
in PATH is absent, obsolete or broken. The installer validates Java 17+ before
copying files or changing the repository.

For a repository whose Privonym model already exists, add:

   -InitializeRepository $false

Destructive clean installation
------------------------------
To erase an existing Privonym repository and reinstall a completely clean
model in one command, use Install-PrivonymReset.ps1. Example:

   .\Install-PrivonymReset.ps1 `
     -RepositoryUrl "jdbc:oracle:thin:@//oracle-host:1521/PRIVONYM" `
     -RepositoryUser "PRIVONYM" `
     -JavaPath "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.1-hotspot\bin\java.exe"

This command permanently deletes all objects and all Privonym data from the
specified repository schema before rebuilding it. As a safety check, a
non-empty schema that is not recognized as a Privonym repository is refused.

The application is installed in C:\Program Files\Privonym.
Configuration, the generated encryption key and logs are stored in
C:\ProgramData\Privonym. The server runs as the scheduled task
"Privonym Server" under the Windows SYSTEM account and starts at boot.

Network access
--------------
The safe default binds HTTP to 127.0.0.1. To bind on every interface, install
with -BindAddress "0.0.0.0" and configure the Windows firewall separately.
Do not expose the current HTTP interface to an untrusted network. Put an HTTPS
reverse proxy and authentication in front of Privonym for remote access.

Database
--------
For an empty repository schema, the installer creates the complete current
model and inserts product-owned metadata: database technologies, sensitive
data types, standard algorithms, parameters and built-in lookup lists. It does
not install environments, connections, discovered catalogues, Rule Sets, Jobs,
execution history or credentials from the reference development repository.

The installer then applies every bundled Oracle migration in version order and
records its SHA-256 checksum in PRIVONYM_SCHEMA_MIGRATION. SQL statements end
with a semicolon. PL/SQL blocks keep their internal and final semicolons and
must use a slash (/) alone on the line following END; as the block separator.

Uninstallation
--------------
Run C:\Program Files\Privonym\Uninstall-Privonym.ps1 as Administrator.
By default, configuration, the credential encryption key and logs are kept.
Use -RemoveData to remove them permanently.

External job execution
----------------------
The installed privonym-job.cmd accepts a numeric JOB_ID and uses the protected
configuration and encryption key under C:\ProgramData\Privonym by default:

  privonym-job.cmd 27

Upgrades
--------
For an existing installation, use Upgrade-Privonym.ps1 and follow
README-UPGRADE.txt. The upgrade path preserves configuration, encryption keys,
logs, reports and repository business data.
