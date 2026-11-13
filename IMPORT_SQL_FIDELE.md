# Import SQL fidèle — Oracle, SQL Server et PostgreSQL

L'import conserve les expressions scalaires prises en charge dans le paramètre `expression` des algorithmes `ORACLE_EXPRESSION_EXACT`, `SQLSERVER_EXPRESSION_EXACT` et `POSTGRESQL_EXPRESSION_EXACT`. Chaque mode est lié à son moteur source. Le SQL n'est pas remplacé par un lookup ou un masque jugé similaire. NULL reste représenté par NULLIFY. Les paramètres appartiennent à la règle et suivent sa version ; les générations et exports SQL relisent la même expression validée. Le CSV historique à huit colonnes ne contient pas les expressions et ne constitue pas un format de sauvegarde fidèle de ces nouvelles règles ; utiliser leur version enregistrée et l'export SQL.

## Directives et orchestration

WHENEVER, DEFINE, SET SERVEROUTPUT et autres réglages SQL*Plus reconnus, PROMPT, les COMMIT/ROLLBACK et les séparateurs / ne sont pas des règles. Ils sont signalés dans le compte rendu, sans être exécutés. Les textes identiques présents dans un littéral SQL ne sont pas supprimés. Les commentaires sont reconnus hors des littéraux.

Les références &schema sont rattachées au schéma de la connexion sélectionnée. Les variables de SQL dynamique ne sont reconstruites que si leur valeur est statiquement déterminable. Les appels DBMS_PARALLEL_EXECUTE servent à extraire le SQL métier associé ; création de tâche, calcul des chunks, lancement et reprise restent gérés par Privonym. Une borne technique complète ROWID BETWEEN :start_id AND :end_id est retirée du SQL extrait. Une combinaison ambiguë avec un prédicat métier est refusée.

Les branches directe et parallèle équivalentes sont regroupées en une seule opération métier après comparaison de leurs tokens, en conservant les littéraux. Des branches différentes ne sont pas fusionnées. Les conditions ou boucles métier non représentables sont signalées explicitement, sans extraction silencieuse de leur seul UPDATE.

## Expressions et limites

Le registre `ImportedSqlExpression` distingue les fonctions scalaires des trois
moteurs : chaînes et concaténation, expressions régulières, conversions, nombres,
dates, hachage et fonctions JSON courantes. Exemples : `LPAD`/`NVL`/`TO_CHAR` sur
Oracle, `STUFF`/`ISNULL`/`CONVERT`/`DATEADD` sur SQL Server,
`regexp_replace`/`split_part`/`date_trunc`/`::text` sur PostgreSQL.
Les appels imbriqués, identifiants SQL Server entre crochets, littéraux Unicode,
nombres décimaux, casts et littéraux PostgreSQL dollar-quoted sont conservés.
Les préfixes, flags de regex, arguments et paramètres de précision ne sont pas
remplacés par des paramètres approximatifs d'un autre algorithme.

Ce registre couvre des fonctions courantes, pas toutes les fonctions de toutes
les versions et extensions. Une fonction inconnue, un appel qualifié de package,
une fonction utilisateur, une sous-requête ou une expression nécessitant un alias
externe reste à examiner. La base cible vérifie les signatures, les types et la
disponibilité selon sa version et ses extensions ; `digest`, par exemple, dépend
de l'installation de pgcrypto. Aucun script importé n'est exécuté par l'analyse.

Références des familles de fonctions :
[Oracle](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/Single-Row-Functions.html),
[SQL Server](https://learn.microsoft.com/en-us/sql/t-sql/functions/functions),
[PostgreSQL](https://www.postgresql.org/docs/current/functions-string.html).

Le mode exact accepte un ensemble contrôlé d'expressions scalaires : colonnes, constantes, concaténations, CASE et fonctions Oracle autorisées, notamment SUBSTR, INSTR, NVL, CHR et REGEXP_REPLACE. Les groupes capturés, retours à la ligne et remplacements imbriqués restent dans leur ordre d'origine. Les expressions sont vérifiées lors de l'analyse et à chaque génération de plan ; elles sont limitées à 4000 caractères. Les appels de packages arbitraires, sous-requêtes dans une affectation, commandes SQL, commentaires injectés et marqueurs de templates ne sont pas admis dans ce mode.

Les DDL Oracle autonomes reconnus sont conservés en POST par défaut, y compris CREATE INDEX et DROP. Un TRUNCATE qui précède une opération de masquage sur la même table est placé en PRE, comme les blocs ADD et UPDATE dépendants déjà pris en charge. La boucle de suppression TMP du fichier SD_POS est conservée entière en POST. Les définitions PL/SQL stockées et les dépendances ambiguës restent à examiner. Un diagnostic UNSUPPORTED bloque la création du Rule Set.

Le fichier .bak est accepté comme texte dans le sélecteur Transformer. Les éléments INFO/IGNORED restent visibles dans le compte rendu sans créer artificiellement des erreurs de revue. Les alertes métier restent bloquantes ou à examiner.

## Cas fournis et qualification

- STL_anonymise.sql : 5 tables et 7 règles fidèles. Même dérivation pour les deux colonnes PARTY, filtre CLIENT/BROKER conservé, constantes exactes et chaîne de cinq remplacements SWIFT dans les CLOB.
- FOREX_anonymise.sql conserve les deux ALTER TABLE DROP et ADD en POST. Ce fichier structurel seul ne produit aucune règle de masquage.
- SD_POS_anonymise.bak conserve notamment CREATE INDEX et la boucle de suppression des tables TMP en POST. Les alias corrélés, variables dans les sous-requêtes et blocs d'exception restent à examiner.

Le test Oracle natif exécute le script STL original dans sa branche directe exclusivement sur cinq tables dédiées, puis réinitialise les mêmes données synthétiques et exécute le SQL du Rule Set enregistré/régénéré. La copie en nouveau brouillon v2 et son export SQL sont également vérifiés. Les résultats sont comparés intégralement : rôles inclus et exclus, nulls, textes constants, champs SWIFT préservés et CLOB de plus de 32 Ko. Les métadonnées et le catalogue de test sont annulés et les tables dédiées supprimées. Aucun script fourni n'est exécuté sur des tables métier.

## Scripts Oracle PRE et POST issus d'un import

Les `SELECT` autonomes de mesure de durée sont ignorés et signalés. Une
sous-requête `SELECT` appartenant à un filtre métier reste dans ce filtre.

Les blocs contigus `ALTER TABLE ... ADD colonne` puis `UPDATE` de cette colonne
sont conservés ensemble dans le PRE-SCRIPT. L'UPDATE préparatoire ne devient pas
une règle sur une colonne encore absente du catalogue. Une opération antérieure
sur la même table ou une dépendance traversant d'autres opérations métier reste
à examiner : aucun déplacement automatique de ce bloc n'est alors présenté
comme fidèle. Les `SET UNUSED` et les ajouts de remplacement final sont conservés
dans le POST-SCRIPT, dans leur ordre d'origine.

La prévisualisation affiche les deux scripts et permet de les télécharger. Le
SQL source enregistré avec la version sert de provenance ; une copie du
brouillon ou une version créée depuis Discovery conserve cette provenance. À la
création d'un Job, les scripts sont copiés dans ses phases PRE et POST avec des
noms `transformer-pre-v*.sql` et `transformer-post-v*.sql`. Les références SQL*Plus
au schéma sont liées au schéma de la connexion sans changer les littéraux SQL.
Un script manuel existant n'est pas écrasé. Les anciens Jobs ne sont pas modifiés.
Le clonage entre connexions refuse les scripts contenant un propriétaire de
schéma littéral : leur cible doit d'abord être examinée.

L'export SQL respecte l'ordre PRE, masquage, POST. Les scripts attachés aux Jobs
s'exécutent uniquement lors de l'exécution du Job, jamais pendant l'analyse ou
l'import. Une phase terminée possède un reçu et n'est pas rejouée pendant une
reprise. Une phase commencée sans reçu de fin exige une vérification, car un DDL
Oracle peut avoir été validé même si la suite du script a échoué. Un redémarrage
complet du Job peut relancer ses scripts ; les ADD ne sont pas automatiquement
transformés en commandes idempotentes.

Les scripts PRE/POST générés accompagnent un inventaire contenant des règles de
masquage ; un fichier ne contenant que du DDL n'est pas converti en Job autonome.
Cette extraction structurelle concerne Oracle. Elle utilise les tables de
provenance et de scripts déjà présentes ; aucune nouvelle migration n'est requise.

Qualification : test sur métadonnées synthétiques vérifiant import, attachement
aux Jobs, liaison du schéma, ordre de l'export, copie en v2 et préservation d'un
script manuel. Toutes ces métadonnées sont annulées ; aucun DDL n'est exécuté sur
une base cible par ce test.

## Installation des modes d'expression

La migration additive `V2_027_native_sql_import_oracle.sql` ajoute les deux modes
SQL Server et PostgreSQL, leurs paramètres et leurs implémentations propres au
moteur. Elle ne modifie pas les anciennes règles. Après V2_026, le programme
`com.privonym.install.NativeSqlImportCatalogInstaller` accepte seulement le fichier
V2_027 scellé, refuse un catalogue préexistant non enregistré et insère les deux
nouveaux modes et leur historique en une transaction. Comme V2_026, un marqueur
inexécutable empêche un ancien runtime d'utiliser silencieusement ces expressions.
Installer les JARs compilés, appliquer la migration et redémarrer normalement.
Les brouillons déjà importés ne sont pas réécrits : refaire la prévisualisation
avec le dialecte de la connexion, puis créer une nouvelle version du brouillon.

La migration V2_026 ajoute uniquement le mode d'expression et son paramètre au catalogue. Elle ne modifie aucune règle existante ni table métier. Une mise à jour générale du schéma exige l'arrêt des jobs. L'activation ciblée peut utiliser ExactOracleImportCatalogInstaller : il n'accepte que l'empreinte scellée de V2_026, vérifie V2_025 et l'absence du nouveau mode, puis insère uniquement les nouvelles entrées de catalogue et leur historique, en une transaction. Il n'effectue aucun DDL ni mise à jour de données existantes et peut donc préserver un job actif. Un catalogue préexistant non enregistré ou un fichier modifié est refusé. L'implémentation de catalogue contient un marqueur volontairement inexécutable par un ancien runtime : le nouveau chargeur doit la remplacer par l'expression validée, pour éviter un traitement silencieusement incorrect en cas de mélange de versions.

Compiler avec .\build.cmd -JdkHome 'C:\Program Files\Java\jdk-17'. Après migration et installation des JAR, redémarrer normalement Privonym avant utilisation du nouvel import.
