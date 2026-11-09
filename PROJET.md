# Projet Privonym

Import Oracle fidèle : [IMPORT_SQL_FIDELE.md](IMPORT_SQL_FIDELE.md).

Lot 3 — référentiel et reproductibilité : [LOT3_REFERENTIEL.md](LOT3_REFERENTIEL.md).

État de référence : code importé en version 2.5.0, commit `04825f7` du
19 septembre 2026. Analyse initiale réalisée le 24 octobre 2026.
Ce document décrit le dépôt ; il ne certifie pas une installation en production.

## Fonction et périmètre

Privonym prépare et exécute des traitements de masquage sur des bases de données.
Le référentiel de configuration et d'exécution reste Oracle. Les adaptateurs
cibles couvrent Oracle, PostgreSQL et Microsoft SQL Server, avec des capacités
et des contraintes qui dépendent du dialecte et de l'algorithme.

Les fonctions présentes comprennent les environnements et connexions, la
découverte et classification de colonnes sensibles, les inventaires, les règles
versionnées, leur publication, la génération de SQL, les contrôles préalables,
les jobs, le parallélisme, les lots, l'annulation et les rapports PDF.
La console gère aussi les utilisateurs, les privilèges et la propriété des règles.

Discovery peut compléter les noms de colonnes par une analyse locale bornée de
textes Oracle, PostgreSQL et SQL Server. Elle fournit des recommandations motivées,
sans ajouter de détection pendant les jobs : [DISCOVERY_TEXT.md](DISCOVERY_TEXT.md).
Pour une connexion déjà configurée, les propositions Discovery peuvent préparer
la prochaine version brouillon du même Rule Set en conservant sa configuration.

## Organisation technique

| Emplacement | Responsabilité |
| --- | --- |
| `modules/privonym-domain` | Identifiants, états et transitions des jobs/exécutions |
| `modules/privonym-application` | Cas d'usage, services et interfaces des adaptateurs |
| `modules/privonym-http-adapter` | Serveur Jetty, routes REST, console et ressources web |
| `src/main/java/com/privonym/adapter/oracle` | Validation et rendu des expressions SQL |
| `src/main/java/com/privonym/repository` | Accès JDBC, planification et exécution |
| `src/main/java/com/privonym/cli` | Commandes interactives et runner de jobs |
| `src/main/java/com/privonym/install` | Installation et migrations Oracle |
| `database/generated` | Schéma initial et métadonnées système |
| `database/migrations` | Évolutions du référentiel, V2_002 à V2_024 |
| `database/target-functions` | Fonctions à installer sur les cibles selon les algorithmes |
| `packaging/windows` | Construction des archives, installation et mise à jour |
| `lib` | Dépendances binaires utilisées hors ligne |

La séparation domaine/application/adaptateurs est en place. Certaines classes
restent très volumineuses : `PrivonymConsolePage`, `PrivonymHttpHandler`,
`OracleRulesetAdministrationAdapter` et `OracleDiscoveryAdapter`.
L'interface HTML/CSS/JavaScript est intégrée à des classes Java.

## Cycle d'exécution

1. Créer une connexion et constituer un inventaire.
2. Configurer puis publier une version de règles.
3. Créer un job et générer son plan SQL.
4. Contrôler le plan et les prérequis de la cible.
5. Exécuter, puis valider les métriques et les relations référentielles.
6. Produire le rapport et terminer le job.

Des contrôles SHA-256 associent les plans au SQL sauvegardé. Cela contrôle
l'intégrité du plan ; cela ne prouve pas la qualité du masquage des données.

Le parcours web et le runner configuré utilisent `executeJobToCompletion`.
La CLI interactive a son propre parcours : ne pas supposer une parité complète
des dialectes, de la reprise ou des transactions entre ces points d'entrée.

## Transactions et reprise

Le parcours web habituel utilise `OracleParallelPlanExecutor`, y compris lorsque
le réglage est DIRECT. Les étapes directes et les lots peuvent être validés
séparément. Un échec du job ne signifie donc pas que toutes les écritures
précédentes ont été annulées. Certains parcours utilisent un exécuteur séquentiel
avec une transaction cible commune.

Les scripts PRE et POST ont leurs propres validations de transaction. Sur Oracle,
les opérations DDL, notamment TRUNCATE, ont aussi des effets transactionnels
spécifiques : une promesse de rollback global ne s'applique pas à tous les jobs.

Les lots réussis sont journalisés pour permettre leur réutilisation. La validation
cible et la journalisation dans le référentiel sont deux opérations distinctes.
La reprise après une interruption entre ces opérations doit être qualifiée sur
des données de test, notamment pour les algorithmes non idempotents.

## État vérifié et limites

L'analyse initiale a confirmé la compilation Java 17 et la réussite des sept
suites SmokeCheck. Le build produit quatre JAR. Voir [COMPILATION.md](COMPILATION.md)
pour reproduire ces vérifications.

Aucun test complet avec un référentiel et des cibles réels n'a été réalisé lors
de cette analyse. Les tests HTTP utilisent des services simulés et les tests de
migrations contrôlent notamment le parsing, sans appliquer le schéma à Oracle.
La réussite du build ne constitue donc pas une qualification multi-base.

Points à traiter, encore ouverts dans cette livraison documentaire :

- **Qualification des lots** : le correctif du 24 octobre conserve un comptage
  indépendant et détecte les écarts. Sa qualification sur bases réelles et
  l'application de V2_021 restent à réaliser ; voir [CHUNKS.md](CHUNKS.md).
- **Santé du serveur** : `/api/v1/health` est traité après les contrôles de sécurité.
  Avec le lanceur de production, les états de connexion peuvent empêcher une
  réponse publique UP, alors que l'upgrade attend cette réponse sans session.
- **Transactions/reprise** : qualifier les engagements partiels, les scripts
  PRE/POST, les interruptions et les relances avant toute promesse opérationnelle.
- **Couverture** : ajouter des tests d'intégration par technologie et des tests
  d'autorisation avec plusieurs profils utilisateurs.
- **Maintenance** : extraire progressivement la console, le routage et
  l'orchestration des classes les plus volumineuses.
- **Automatisation** : aucun pipeline CI n'est fourni à ce stade.

## Configuration et exploitation

Les configurations réelles, clés et données d'exécution restent locales et sont
ignorées par Git. Les exemples dans `config/*.example` sont versionnés.
Conserver une sauvegarde de la clé de chiffrement avec les sauvegardes du
référentiel : les identifiants chiffrés des connexions en dépendent.

Le serveur écoute par défaut sur `127.0.0.1:8080`. L'accès distant et l'exploitation
Windows sont décrits dans [README.md](README.md) et les guides de packaging.
Les scripts d'installation, de reset et de migration modifient des données ;
ils ne font pas partie des vérifications ordinaires du code.

## Corrections du 24 octobre 2026

Voir [CORRECTIONS.md](CORRECTIONS.md) pour les correctifs, les prérequis de
configuration et les limites restant à qualifier.
