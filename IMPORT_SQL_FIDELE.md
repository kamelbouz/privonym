# Import SQL Oracle fidèle — 9 novembre 2026

L'import Oracle conserve désormais les expressions scalaires prises en charge dans le paramètre expression de l'algorithme ORACLE_EXPRESSION_EXACT. Le SQL source n'est pas remplacé par un lookup ou un masque jugé similaire. NULL reste représenté par NULLIFY. Les paramètres appartiennent à la règle et suivent sa version ; les générations et exports SQL relisent la même expression validée. Cette capacité est Oracle uniquement. Le CSV historique à huit colonnes ne contient pas les expressions et ne constitue pas un format de sauvegarde fidèle de ces nouvelles règles ; utiliser leur version enregistrée et l'export SQL.

## Directives et orchestration

WHENEVER, DEFINE, SET SERVEROUTPUT et autres réglages SQL*Plus reconnus, PROMPT, les COMMIT/ROLLBACK et les séparateurs / ne sont pas des règles. Ils sont signalés dans le compte rendu, sans être exécutés. Les textes identiques présents dans un littéral SQL ne sont pas supprimés. Les commentaires sont reconnus hors des littéraux.

Les références &schema sont rattachées au schéma de la connexion sélectionnée. Les variables de SQL dynamique ne sont reconstruites que si leur valeur est statiquement déterminable. Les appels DBMS_PARALLEL_EXECUTE servent à extraire le SQL métier associé ; création de tâche, calcul des chunks, lancement et reprise restent gérés par Privonym. Une borne technique complète ROWID BETWEEN :start_id AND :end_id est retirée du SQL extrait. Une combinaison ambiguë avec un prédicat métier est refusée.

Les branches directe et parallèle équivalentes sont regroupées en une seule opération métier après comparaison de leurs tokens, en conservant les littéraux. Des branches différentes ne sont pas fusionnées. Les conditions ou boucles métier non représentables sont signalées explicitement, sans extraction silencieuse de leur seul UPDATE.

## Expressions et limites

Le mode exact accepte un ensemble contrôlé d'expressions scalaires : colonnes, constantes, concaténations, CASE et fonctions Oracle autorisées, notamment SUBSTR, INSTR, NVL, CHR et REGEXP_REPLACE. Les groupes capturés, retours à la ligne et remplacements imbriqués restent dans leur ordre d'origine. Les expressions sont vérifiées lors de l'analyse et à chaque génération de plan ; elles sont limitées à 4000 caractères. Les appels de packages arbitraires, sous-requêtes dans une affectation, commandes SQL, commentaires injectés et marqueurs de templates ne sont pas admis dans ce mode.

Les DDL métier ne sont jamais classés comme simples directives. Une opération ALTER/DROP/CREATE, une condition non représentable ou un prédicat corrélé dont les alias ne sont pas conservés produit un diagnostic UNSUPPORTED. L'import de ce script complet est bloqué avant création d'un Rule Set partiel. Une expression non reconnue reste à examiner ; elle n'est pas remplacée automatiquement par une proposition approximative.

Le fichier .bak est accepté comme texte dans le sélecteur Transformer. Les éléments INFO/IGNORED restent visibles dans le compte rendu sans créer artificiellement des erreurs de revue. Les alertes métier restent bloquantes ou à examiner.

## Cas fournis et qualification

- STL_anonymise.sql : 5 tables et 7 règles fidèles. Même dérivation pour les deux colonnes PARTY, filtre CLIENT/BROKER conservé, constantes exactes et chaîne de cinq remplacements SWIFT dans les CLOB.
- FOREX_anonymise.sql : 2 ALTER TABLE métier. Pas de conversion approximative du DROP/ADD en NULLIFY ; import complet non pris en charge et signalé.
- SD_POS_anonymise.bak : 24 opérations et 118 affectations identifiées dans le diagnostic. DDL, alias corrélés, variables dans les sous-requêtes et suppression dynamique de tables empêchent un import automatique complet fidèle. Ce fichier n'est pas annoncé comme pris en charge de bout en bout.

Le test Oracle natif exécute le script STL original dans sa branche directe exclusivement sur cinq tables dédiées, puis réinitialise les mêmes données synthétiques et exécute le SQL du Rule Set enregistré/régénéré. La copie en nouveau brouillon v2 et son export SQL sont également vérifiés. Les résultats sont comparés intégralement : rôles inclus et exclus, nulls, textes constants, champs SWIFT préservés et CLOB de plus de 32 Ko. Les métadonnées et le catalogue de test sont annulés et les tables dédiées supprimées. Aucun script fourni n'est exécuté sur des tables métier.

## Installation

La migration V2_026 ajoute uniquement le mode d'expression et son paramètre au catalogue. Elle ne modifie aucune règle existante ni table métier. Une mise à jour générale du schéma exige l'arrêt des jobs. L'activation ciblée peut utiliser ExactOracleImportCatalogInstaller : il n'accepte que l'empreinte scellée de V2_026, vérifie V2_025 et l'absence du nouveau mode, puis insère uniquement les nouvelles entrées de catalogue et leur historique, en une transaction. Il n'effectue aucun DDL ni mise à jour de données existantes et peut donc préserver un job actif. Un catalogue préexistant non enregistré ou un fichier modifié est refusé. L'implémentation de catalogue contient un marqueur volontairement inexécutable par un ancien runtime : le nouveau chargeur doit la remplacer par l'expression validée, pour éviter un traitement silencieusement incorrect en cas de mélange de versions.

Compiler avec .\build.cmd -JdkHome 'C:\Program Files\Java\jdk-17'. Après migration et installation des JAR, redémarrer normalement Privonym avant utilisation du nouvel import.
