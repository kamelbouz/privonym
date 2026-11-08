# Compilation, tests et scripts

Lot 3 — référentiel et reproductibilité : [LOT3_REFERENTIEL.md](LOT3_REFERENTIEL.md).

## Prérequis

- Windows et Windows PowerShell 5.1 ou PowerShell 7 pour le build fourni.
- Un JDK 17 ou supérieur complet (`java.exe`, `javac.exe`, `jar.exe`).
- Les dépendances déjà versionnées dans `lib`.

Maven, Gradle et une connexion réseau ne sont pas nécessaires pour compiler.
La récupération initiale du dépôt et l'accès aux bases sont des opérations séparées.
Le build fourni utilise des chemins et des exécutables Windows ; le runner POSIX
ne constitue pas un script de compilation Linux.

## Construire et vérifier

Depuis une console PowerShell :

```powershell
Set-Location C:\Privonym
.\build.ps1
```

Ou depuis une invite de commandes :

```bat
C:\Privonym\build.cmd
```

Le compilateur est choisi dans cet ordre : paramètre `-JdkHome`, variable
`JAVA_HOME`, puis `javac` dans PATH. Une valeur explicite invalide échoue avec un
message ; le script ne bascule pas silencieusement sur un autre JDK.
Le build utilise les outils du même JDK pour compiler, tester et créer les JAR.

```powershell
.\build.ps1 -JdkHome 'C:\Tools\jdk-17'
```

```bat
build.cmd -JdkHome "C:\Tools\jdk-17"
```

La compilation cible Java 17 (`--release 17`), avec `-Xlint:all` et `-Werror`.
Le script s'arrête si une compilation, une suite ou un packaging échoue.
Un build réussi se termine par `Offline build completed` et produit :

- `target/privonym-domain.jar`
- `target/privonym-application.jar`
- `target/privonym-http-adapter.jar`
- `target/privonym-oracle-adapter.jar`

Chaque build conserve ses classes dans `target/.build-<identifiant>`. Les JAR
finaux ont des noms fixes : ne pas lancer plusieurs builds simultanément dans
le même dossier. Aucun nettoyage récursif automatique n'est ajouté au build.

## Vérifications exécutées

| Suite | Périmètre |
| --- | --- |
| `DomainSmokeCheck` | Modèle et transitions du domaine |
| `ApplicationSmokeCheck` | Services, parcours simulés et parsing multi-dialecte |
| `HttpAdapterSmokeCheck` | Routes et réponses HTTP sur serveurs locaux temporaires |
| `OracleAdapterSmokeCheck` | Rendu SQL, paramètres et identifiants |
| `OracleDiscoveryClassifierSmokeCheck` | Classification de colonnes |
| `OracleSqlServerPlanSmokeCheck` | Cas de compatibilité et génération SQL Server |
| `OracleMigrationScriptSmokeCheck` | Parsing et contrôles des scripts de schéma |

Ces vérifications ne nécessitent pas d'identifiants de base. Les tests HTTP
ouvrent des ports locaux temporaires. Aucun job métier n'est lancé et aucune
migration n'est appliquée à un référentiel réel par le build.
Les essais d'intégration et de reprise sur bases restent à organiser séparément.

Vérification du 24 octobre 2026 : build complet via `build.cmd -JdkHome` sous
JDK 17.0.15, sept suites réussies et quatre JAR générés. Un chemin JDK invalide
a été refusé comme attendu. Les deux archives Windows ont été générées avec
`-SkipBuild` après ce build. Aucune installation ni mise à jour d'un référentiel
réel n'a été exécutée.

## Inventaire des scripts

| Script | Usage et effet |
| --- | --- |
| `build.cmd` / `build.ps1` | Compiler, tester et générer les quatre JAR |
| `privonym-server.cmd` / `.ps1` | Démarrer le serveur et lire la configuration locale |
| `privonym.cmd` | CLI interactive ; `execute` modifie la cible |
| `privonym-job.cmd` / `.ps1` / `.sh` | Exécuter un job par identifiant sans serveur web |
| `privonym-run-remote.ps1` | Appeler un job du serveur par SSH |
| `packaging/windows/Build-PrivonymWindowsPackage.ps1` | Créer une archive d'installation |
| `packaging/windows/Build-PrivonymWindowsUpgrade.ps1` | Créer une archive de mise à jour |
| `packaging/windows/Install-Privonym.ps1` | Installer l'application et, selon les options, le référentiel |
| `packaging/windows/Install-PrivonymClean.ps1` / `Install-PrivonymReset.ps1` | Installation propre/reset ; lire les effets destructifs avant utilisation |
| `packaging/windows/Upgrade-Privonym.ps1` | Mettre à jour une installation et contrôler sa santé |
| `packaging/windows/Rollback-Privonym.ps1` | Restaurer une sauvegarde de runtime |
| `packaging/windows/Uninstall-Privonym.ps1` | Désinstaller ; option de suppression des données |
| `packaging/windows/Start-PrivonymServer.ps1` | Lancer le serveur installé |
| `packaging/windows/Export-PrivonymBaseline.ps1` | Exporter une baseline Oracle ; nécessite une connexion |

Les fichiers SQL `Export-PrivonymBaseline.sql` et `Export-PrivonymMetadata.sql`
assistent les exports du référentiel. Ce ne sont pas des tests de compilation.

## Archives Windows

Depuis la racine du dépôt, après un build réussi :

```powershell
.\packaging\windows\Build-PrivonymWindowsPackage.ps1 -Version '2.5.0' -SkipBuild
.\packaging\windows\Build-PrivonymWindowsUpgrade.ps1 -Version '2.5.0' -SkipBuild
```

Omettre `-SkipBuild` pour reconstruire avant l'archivage. Le paramètre `-Version`
nomme l'archive ; il ne crée ni commit, ni tag, ni release GitHub.
Les générateurs remplacent le dossier de staging et le ZIP de même nom dans
`target`. Ils n'installent pas l'application. Les configurations réelles et les
clés ne doivent jamais être ajoutées manuellement aux archives.

Lire [README-INSTALL.txt](packaging/windows/README-INSTALL.txt) et
[README-UPGRADE.txt](packaging/windows/README-UPGRADE.txt) avant installation.
La limite actuelle du contrôle de santé est signalée dans [PROJET.md](PROJET.md).

## Diagnostic

- `javac` absent : installer un JDK complet ou sélectionner son dossier avec `-JdkHome`.
- `JAVA_HOME` invalide : corriger la variable ou passer un `-JdkHome` valide.
- Bibliothèque manquante : restaurer les fichiers suivis dans `lib` ; éviter de
  substituer une version sans qualification.
- JAR verrouillé : arrêter le processus qui l'utilise avant de reconstruire.
- Serveur impossible à démarrer : vérifier `config/privonym.properties`, le port
  et l'accès au référentiel. Un build réussi n'établit pas ces connexions.
- Échec de migration : conserver le diagnostic et examiner l'historique de
  migration ; ne pas lancer un reset pour contourner une erreur.
