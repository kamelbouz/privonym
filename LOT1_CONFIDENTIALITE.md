# Lot 1 — confidentialité, accès et portée des validations

Cette livraison traite les points 01, 02, 03 et 14 de l'audit du 8 novembre 2026.
Elle ne réconcilie pas les chunks bloqués, ne change pas les versions des jobs et
ne lance aucun masquage sur les données métier.

## Rédaction des emails SQL Server

La fonction cible `dbo.PRIVONYM_TEXT_REDACT` doit utiliser les classes de caractères
corrigées du fichier `database/target-functions/sqlserver/privonym_text_redact.sql`.
Les JAR seuls ne remplacent pas une fonction déjà installée dans SQL Server.
Le cas `a,alice@example.com; FIN` doit devenir `a,ANONYMIZED; FIN`.

La qualification `AlgorithmPaletteCheck` teste les quatre algorithmes de la palette,
les séparateurs, NULL, Unicode, les remplacements littéraux et les textes longs sur
Oracle, PostgreSQL et SQL Server. Son option `--apply-helper-only` remplace seulement
la fonction SQL Server reconnue, sauvegarde sa définition précédente, exécute les
tests sur la nouvelle fonction et ne commit qu'en cas de réussite. Elle ne modifie
pas le catalogue. Une définition personnalisée inconnue est refusée.
Un verrou partagé temporaire sur les jobs du référentiel empêche leur démarrage
pendant l'opération ; l'activation refuse tout job actif. Ce verrou est libéré à la
fin des essais. Aucune table métier n'est mise à jour par cette qualification.

## Imports Transformer et CSV

- Connexion sans Rule Set : droit RULESET_CREATE requis ; le nouveau Rule Set reçoit
  son propriétaire initial.
- Connexion déjà liée : droit RULESET_MODIFY_OWN et propriété requis, ou administrateur.
  Un import ne change jamais le propriétaire de ce Rule Set.
- Connexion historiquement liée à plusieurs Rule Sets : refus explicite, sans choisir
  arbitrairement lequel modifier.

Le contrôle porte d'abord sur la requête HTTP. L'identité du Rule Set autorisé est
ensuite transmise par le serveur au service d'import, sans provenance d'un champ client.
L'adaptateur JDBC la revérifie après verrouillage de la connexion et contrôle le
propriétaire sous verrou de Rule Set avant d'écrire. Si l'association ou le propriétaire
a changé, l'import est annulé. Le compte administrateur dispose d'une exemption
explicite de propriété, sans réattribution à son profit.

Les appels internes historiques sans authentification applicative conservent leur
contrat ; les routes HTTP authentifiées utilisent le contrat d'import autorisé.
Un adaptateur qui n'implémente pas ce contrat ne bénéficie d'aucun repli permissif.

## Révocation de sessions

Après une modification des privilèges, toutes les sessions du compte concerné sont
invalidées dans le serveur, y compris celle qui effectue la modification. Une
modification de ses propres droits expire aussi le cookie de session de l'acteur.
Une nouvelle authentification est nécessaire. La désactivation d'un compte conserve
également son mécanisme de révocation.

Ce changement concerne le serveur qui gère les sessions en mémoire. Une architecture
future à plusieurs serveurs nécessitera une invalidation partagée ou un contrôle de
version de session ; ce lot ne crée pas de gestionnaire de sessions distribué.

## Validation : trois notions distinctes

1. **Compteurs d'exécution** : comparaison des lignes affectées attendues avec les
   lignes traitées journalisées. Cela vérifie la cohérence du journal d'exécution.
2. **Population réelle de la cible** : SELECT COUNT indépendant après exécution,
   comparé à une population attendue capturée avant les mutations.
3. **Retrait des données sensibles** : non évalué par ces deux contrôles. Aucun
   détecteur de contenu n'a été ajouté à l'exécution ; l'analyse contextuelle reste
   dans Discovery.

La population attendue est enregistrée sous la métrique TARGET_ROWS_EXPECTED :
UPDATE conserve le nombre total de lignes ; DELETE soustrait les lignes attendues
à supprimer ; TRUNCATE attend zéro. Un UPDATE filtré compare la population totale
de la table, et non le seul sous-ensemble filtré, à la population finale.
Pour les opérations non filtrées, le comptage exact déjà effectué est réutilisé.

Une reprise avec des chunks déjà commités conserve l'ancienne preuve ; elle n'en
fabrique pas une à partir d'une cible partiellement modifiée. Si cette preuve
manque sur une exécution historique, la cible est quand même comptée mais la
comparaison indépendante est marquée NOT CHECKED, avec WARNING dans le référentiel.
Cette absence ne bloque pas à elle seule une ancienne exécution.
Une impossibilité de lire la cible ou un écart avec une preuve présente provoque
un échec de validation. Les vérifications sont aussi exécutées après le script POST.

Les résultats distinguent ROW_COUNT, REFERENTIAL_INTEGRITY et SENSITIVE_DATA_SCAN.
L'absence d'évaluation du contenu est enregistrée comme SKIPPED, jamais comme PASSED.
Les anciennes métriques ANONYMIZED_* sont conservées comme alias de compteurs pour
compatibilité ; elles ne sont plus présentées comme une preuve d'anonymisation dans
les nouveaux PDF. Ceux-ci affichent « Reported processed », les comptages cibles,
la disponibilité de la baseline et « SENSITIVE-DATA REMOVAL: NOT ASSESSED ».
Les nouveaux PDF ne réécrivent pas les rapports historiques.

Une population inchangée ne détecte pas une donnée sensible laissée visible ou une
modification POST conservant le même nombre de lignes. Les contrôles de formats
et la qualification des algorithmes restent indispensables. Des écritures externes
concurrentes sur la cible peuvent faire échouer la comparaison ; la cible doit être
stabilisée pendant le traitement. Les comptages complets supplémentaires sur les
opérations filtrées ont un coût sur les grandes tables.

## Vérifications et installation

Le build couvre les refus d'import des non-propriétaires, l'import du propriétaire,
la création initiale, l'absence de réattribution, le changement de propriétaire entre
contrôle et écriture, la révocation de deux sessions du même compte, la population
cible différente malgré des compteurs concordants, les cibles illisibles, l'absence
de baseline historique et les rapports sans promesse de retrait des données sensibles.

Le helper SQL Server est qualifié sur des valeurs synthétiques avant activation.
`TargetValidationIntegrationCheck` vérifie les nouveaux comptages sur les trois
pilotes réels, avec des populations produites uniquement par des SELECT synthétiques.
Les métadonnées attendues sont factices ; aucune table métier n'est lue ni modifiée.
Le déploiement conserve une sauvegarde des JAR et de la fonction. Les nouvelles
protections HTTP et les nouveaux rapports nécessitent le chargement des JAR lors
d'un redémarrage normal du serveur. Le remplacement de la fonction SQL Server prend
effet dans cette base dès son commit, sans migration générale du référentiel.
