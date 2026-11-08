# Lot 3 — Référentiel et reproductibilité — 8 novembre 2026

## Corrections de fonctionnement

Les constructeurs Discovery et Planning n'exécutent plus de DDL, de publication d'algorithmes, d'enrichissement des lookups ni de commit implicite du référentiel. Un démarrage ne réactive plus une valeur désactivée et ne change plus le résultat d'un mapping déterministe. Les installations et évolutions du catalogue passent par des migrations explicites, versionnées.

La migration V2_025 protège en base les valeurs et bindings de lookup appartenant à des versions d'algorithme PUBLISHED/RETIRED : INSERT, UPDATE et DELETE des valeurs sont refusés, de même que le remplacement d'un binding publié. Une évolution nécessite un nouveau dataset et une nouvelle version d'algorithme. Les créations initiales d'un dataset sans binding publié restent possibles.

Transformer/CSV vérifie maintenant l'absence de DRAFT sous le verrou du Rule Set avant toute insertion. Un index unique conditionnel impose aussi un seul DRAFT par Rule Set aux écritures SQL directes. Le brouillon existant n'est ni remplacé ni fusionné automatiquement.

Les nouveaux Rule Sets sont protégés par un trigger qui sérialise la connexion et refuse un deuxième Rule Set. Les doublons historiques sont conservés : leurs versions, propriétaires et jobs ne sont pas modifiés. Une table connection_ruleset_binding désigne un Rule Set courant par connexion, avec date et acteur. La clé étrangère composite interdit de désigner celui d'une autre connexion. Les sources non ambiguës sont renseignées automatiquement. Une source historique ambiguë sans désignation provoque un message explicite ; Discovery et les imports ne prennent plus arbitrairement le premier ID. La résolution avant autorisation HTTP et celle effectuée sous verrou pendant l'import utilisent le même binding ; les droits du propriétaire sont revérifiés.

## Historique de migrations

L'installation historique n'enregistrait que V2_022–024. Les anciens scripts de seed ne sont pas rejoués. OracleRepositoryReconciler vérifie le contrat physique de la baseline, les colonnes de fonctionnalités ultérieures, les contraintes clés validées, l'absence de brouillons multiples et les empreintes déjà enregistrées. Il demande une inspection préalable et une empreinte identique avant maintenance, et refuse les jobs actifs.

La maintenance élargit START_ROWID/END_ROWID à 2000 caractères, recompile les deux vues historiques, puis conserve séparément les états adoptés dans PRIVONYM_SCHEMA_ADOPTION. Cette table indique explicitement que les anciens scripts de données n'ont pas été exécutés ; elle garde leurs empreintes, l'acteur, la date et l'empreinte de l'état observé. PRIVONYM_SCHEMA_MIGRATION conserve l'historique des migrations réellement enregistrées. L'upgrader accepte la chaîne complète des deux sources et distingue BASELINE et SKIP dans ses messages.

Une adoption qualifie l'état de fonctionnement observé, pas une identité parfaite avec les valeurs de seed historiques. Les personnalisations de lookup sont conservées. Les différences de fins de ligne LF/CRLF sont acceptées pour les SHA-256 historiques ; une modification du contenu SQL reste refusée. Les fichiers de migration déjà enregistrés ne sont pas réécrits.

## Commandes de maintenance

Depuis le dossier Privonym, Java 17, classpath Windows :

~~~powershell
$cp='target/privonym-domain.jar;target/privonym-application.jar;target/privonym-oracle-adapter.jar;lib/*'
java -cp $cp com.privonym.install.OracleRepositoryReconciler config/privonym-job.properties database/migrations --inspect
java -cp $cp com.privonym.install.OracleRepositoryReconciler config/privonym-job.properties database/migrations --verify
~~~

Une adoption explicite utilise --apply suivi de l'empreinte affichée par --inspect. La maintenance exige l'arrêt des jobs. Les DDL Oracle sont auto-commit : les évolutions sont additives et réexécutables ; une erreur interrompt la suite plutôt que de prétendre à un rollback du schéma.

Pour désigner un Rule Set historique, utiliser --bind sourceId:rulesetId après choix explicite de l'administrateur. La commande verrouille la connexion, valide l'appartenance, conserve tous les Rule Sets et refuse les jobs actifs. Exemple de forme, sans choisir un ID automatiquement : --bind <connexion>:<ruleset>. La connexion 3 possède toujours les IDs 3, 4 et 5 tant qu'un choix explicite n'a pas été consigné.

## Validation et déploiement

Build complet Java 17, tests de démarrage sans SQL ni commit, acceptation LF/CRLF et refus de SQL modifié. Qualification Oracle sur fixtures synthétiques avec rollback systématique : refus du second DRAFT depuis Transformer et depuis SQL, refus du second Rule Set, stockage d'une UUID et d'une borne de 2000 caractères, immutabilité des valeurs/bindings publiés et conservation de la transaction du caller à la construction de Planning.

Aucune opération de masquage n'est lancée sur les cibles métier. Le chunk 3032 reste non réconcilié. Les fichiers de preuve se trouvent dans audit/2026-11-08/lot3-referentiel sur l'installation. Les JAR nécessitent un redémarrage normal de Privonym ; aucun serveur caché n'est lancé.
