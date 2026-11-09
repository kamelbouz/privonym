# Privonym

Import SQL fidèle (Oracle, SQL Server, PostgreSQL) : [IMPORT_SQL_FIDELE.md](IMPORT_SQL_FIDELE.md).

Reprise vérifiée sans intervention SQL : [REPRISE_ORACLE.md](REPRISE_ORACLE.md).

Lot 3 — référentiel et reproductibilité : [LOT3_REFERENTIEL.md](LOT3_REFERENTIEL.md).

Lot 2 : reprise, annulation et récupération : [LOT2_EXECUTION.md](LOT2_EXECUTION.md).

Corrections de confidentialité et d'accès du 8 novembre 2026 :
[LOT1_CONFIDENTIALITE.md](LOT1_CONFIDENTIALITE.md).

Privonym est une application Java de masquage de données : découverte de colonnes
sensibles, règles versionnées, génération SQL, contrôles préalables, exécution
de jobs et rapports. Le référentiel est Oracle ; les adaptateurs cibles couvrent
Oracle, PostgreSQL et Microsoft SQL Server.

Le code de référence est importé sous le tag `v2.5.0`. Les capacités sont présentes
dans le code mais leur qualification complète sur bases réelles reste à faire.
Voir les limites connues dans [PROJET.md](PROJET.md).

## Documentation

| Guide | Contenu |
| --- | --- |
| [PROJET.md](PROJET.md) | Architecture, état constaté, transactions et limites connues |
| [VISION.md](VISION.md) | Objectif produit, priorités et critères d'une version stable |
| [COMPILATION.md](COMPILATION.md) | Prérequis, compilation, tests et inventaire des scripts |
| [CHUNKS.md](CHUNKS.md) | Correctifs des chunks, migration requise et limites de qualification |
| [TEXT_MASKING.md](TEXT_MASKING.md) | Masquage de fragments dans les CLOB et profils emails/téléphones/IBAN |
| [Installation Windows](packaging/windows/README-INSTALL.txt) | Installation et exploitation |
| [Mise à jour Windows](packaging/windows/README-UPGRADE.txt) | Upgrade, sauvegarde et rollback |
| [THIRD_PARTY.md](THIRD_PARTY.md) | Composants tiers et références disponibles |

## Compiler et tester

Depuis Windows PowerShell, avec un JDK 17 ou supérieur :

```powershell
Set-Location C:\Privonym
.\build.ps1
```

Le script utilise `JAVA_HOME` s'il est défini, sinon `javac` dans PATH.
Pour choisir un JDK explicitement :

```powershell
.\build.ps1 -JdkHome 'C:\Tools\jdk-17'
```

`build.cmd` est l'équivalent pour une invite de commandes et transmet les
paramètres. Aucun téléchargement, Maven ou Gradle n'est nécessaire ; les
bibliothèques sont dans `lib`. Le build compile avec les avertissements traités
comme des erreurs, exécute sept suites SmokeCheck et produit quatre JAR dans
`target`. Il n'exécute aucun job métier sur une base réelle.

## Configurer et démarrer le serveur

Pour une copie source nouvellement récupérée, créer la configuration locale une
seule fois. Conserver le fichier existant si l'installation est déjà configurée.

```powershell
Copy-Item .\config\privonym.properties.example .\config\privonym.properties
```

Modifier l'URL JDBC et l'utilisateur du référentiel Oracle dans ce fichier.
Le schéma Privonym doit avoir été installé avec sa baseline et ses migrations ;
le serveur ne crée pas le référentiel. Suivre le guide d'installation Windows
pour initialiser un schéma vide.

```powershell
.\privonym-server.cmd
```

Ouvrir <http://127.0.0.1:8080/login>, connecter le référentiel avec son mot de
passe puis, au premier usage, créer l'administrateur de l'application.
Le serveur utilise `config/privonym.properties`, ou le chemin fourni dans
`PRIVONYM_CONFIG`. La configuration d'exemple ne contient aucun mot de passe.

Conserver une sauvegarde protégée de `config/privonym-secret.key` lorsqu'une clé
existe : les mots de passe chiffrés des connexions en dépendent. Ne pas remplacer
la clé d'une installation existante par une clé nouvellement générée.

L'écoute est locale par défaut (`127.0.0.1:8080`). Pour un accès distant, utiliser
SSH ou une infrastructure HTTPS avec contrôle d'accès ; lire le guide Windows
avant de changer l'adresse d'écoute.

La route de diagnostic est `GET /api/v1/health`. Son interaction actuelle avec
les contrôles de connexion constitue une limite connue, notamment pour l'upgrade ;
voir [PROJET.md](PROJET.md). Elle ne prouve pas que les bases cibles sont disponibles.

## Parcours d'utilisation

1. Créer un environnement et une connexion cible.
2. Découvrir les colonnes ou importer un inventaire.
3. Définir les algorithmes et publier une version de règles.
4. Créer et exécuter le job, puis consulter sa validation et son rapport.

Les modes DIRECT, AUTO et CHUNKED ont des engagements transactionnels différents
selon le parcours. Dans le parcours web habituel, des étapes ou lots réussis
peuvent rester validés après un échec du job. Les scripts PRE et POST sont validés
séparément. Consulter [PROJET.md](PROJET.md) avant de définir une procédure de reprise.

## CLI interactive

La CLI utilise le référentiel configuré localement. Son parcours est distinct de
celui du serveur ; toutes les capacités multi-base ne sont pas équivalentes.

```bat
privonym.cmd preview DEMO-JOB-001
privonym.cmd preview DEMO-JOB-001 --save
privonym.cmd preflight DEMO-JOB-001
privonym.cmd execute DEMO-JOB-001 --confirm
privonym.cmd validate DEMO-JOB-001
privonym.cmd report DEMO-JOB-001
```

`preview` lit le référentiel et génère du SQL sans ouvrir la cible ; `--save`
enregistre le plan. `preflight` contrôle le plan et les prérequis.
`execute --confirm` modifie les données cibles ; utiliser un environnement de test
pour qualifier les règles. Les contrôles de checksum portent sur le plan SQL.

Le renderer Oracle accepte uniquement les placeholders documentés :

```text
${column}
${qualified_table}
${param.<name>}
```

Il valide les identifiants et les paramètres typés, échappe les littéraux et
rejette les entrées manquantes, inconnues, inutilisées ou non sûres.

## Runner de jobs sans serveur web

Copier `config/privonym-job.properties.example` vers
`config/privonym-job.properties`, renseigner le référentiel et protéger les droits
d'accès au fichier : ce runner accepte un mot de passe dans la configuration.
Il utilise aussi la clé locale des connexions cibles.

```bat
privonym-job.cmd 42
privonym-job.cmd 42 C:\chemin\privonym-job.properties
```

Le deuxième argument ou `PRIVONYM_JOB_CONFIG` permet de choisir la configuration.
`PRIVONYM_SECRET_KEY_FILE` permet de choisir la clé utilisée par le runner.
Les codes de sortie sont 0 (succès), 1 (échec d'exécution) et 2 (invocation invalide).
`privonym-job.sh` fournit le lanceur POSIX pour une distribution déjà compilée.
Le runner se connecte directement au référentiel ; il ne nécessite pas de session web.

## Exécution distante par SSH

```powershell
.\privonym-run-remote.ps1 -Server privonym-host -User windows-user -Job CUSTOMER_MASKING_001
```

Ce script appelle `POST /api/v1/jobs/{reference}/execute` sur le serveur distant.
Le serveur doit être connecté au référentiel. Le script existant ne transmet pas
la session utilisateur de l'application : si l'API exige cette session, l'appel
sera refusé. Pour une exécution automatisée sans session web, utiliser le runner
configuré sur l'hôte approprié.

## Inventaires CSV

Les imports créent une nouvelle version brouillon et n'exécutent aucun masquage.

```text
GET  /api/v1/inventory-csv/template
POST /api/v1/inventory-csv/imports/{dataSourceId}
GET  /api/v1/inventories/{rulesetVersionId}/export.csv
```

En-tête :

```text
RULESET_NAME,TABLE_OWNER,TABLE_NAME,COLUMN_NAME,DATA_TYPE,ALGORITHM,IS_MASKED,CRITERIA
```

## Schéma et migrations

Les scripts initiaux sont dans `database/generated`, les 22 évolutions V2_002 à
V2_024 dans `database/migrations`. L'installateur et l'upgrader appliquent les
migrations dans l'ordre et enregistrent leurs checksums dans
`PRIVONYM_SCHEMA_MIGRATION`. Les connexions web ordinaires n'appliquent pas ces
migrations. Ne pas réappliquer manuellement les scripts sans vérifier l'état du
référentiel. Les droits DDL nécessaires à la maintenance sont distincts des
droits d'exploitation normale.

Les fonctions optionnelles des algorithmes sont sous `database/target-functions`
par technologie. Elles doivent être installées sur la cible selon les besoins.

## Contribuer

Lire [PROJET.md](PROJET.md), effectuer une modification ciblée, lancer le build,
puis vérifier le diff avant de committer. Ne pas versionner les configurations
réelles, clés, logs, rapports ou artefacts de `target`. Documenter les changements
qui affectent l'installation, les transactions, les dialectes ou la reprise.

## Corrections du 24 octobre 2026

Le catalogue et les extensions envisagées sont décrits dans
[ALGORITHMS.md](ALGORITHMS.md). Les propositions y sont distinguées des fonctions livrées.

Le premier lot d'extension ajoute quatre algorithmes portables ; voir
[ALGORITHM_PALETTE.md](ALGORITHM_PALETTE.md) pour leurs paramètres et leur activation.

La Discovery analyse aussi des échantillons de texte pour expliquer ses suggestions
d'algorithmes. Ce traitement reste propre à la Discovery ; voir
[DISCOVERY_TEXT.md](DISCOVERY_TEXT.md) pour les indices reconnus et les limites.

Voir [CORRECTIONS.md](CORRECTIONS.md) pour les correctifs, les prérequis de
configuration et les limites restant à qualifier.
