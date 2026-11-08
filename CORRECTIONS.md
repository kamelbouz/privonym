# Corrections du 24 octobre 2026

Lot 3 — référentiel et reproductibilité : [LOT3_REFERENTIEL.md](LOT3_REFERENTIEL.md).

Lot 2 — exécution et reprise : [LOT2_EXECUTION.md](LOT2_EXECUTION.md).

## Lot 1 du 8 novembre 2026

Les imports authentifiés contrôlent la propriété du Rule Set existant et ne la
réattribuent plus. Le contrôle de liaison/propriété est répété sous verrou avant
l'écriture. Les changements de privilèges révoquent toutes les sessions concernées,
y compris celle de l'acteur. La validation ajoute des comptages indépendants sur
la cible et les PDF distinguent les compteurs du retrait des données sensibles,
qui reste non évalué. L'activation de la fonction email SQL Server corrigée est
séparée de celle des JAR : [LOT1_CONFIDENTIALITE.md](LOT1_CONFIDENTIALITE.md).

## Historique des corrections initiales

Cette livraison complète la base 2.5.0 avec les changements suivants :

- Filtres des règles conservés dans les plans de jobs, y compris les critères
  distincts par colonne.
- Bornes de chunks PostgreSQL et SQL Server plus fidèles aux types des clés,
  comptages attendus vérifiés et détection des mises à jour de clé primaire.
- Profils de rédaction d'adresses e-mail, téléphones FR/CH et IBAN FR/CH dans
  les textes longs Oracle, PostgreSQL et SQL Server. Voir [TEXT_MASKING.md](TEXT_MASKING.md).
- Protection des comptes administrateurs contre les modifications déléguées.
- Chiffrement AES-GCM des nouveaux plans enregistrés et refus des exports SQL
  utilisant le paramètre `key_material`. La clé locale existante doit être conservée
  et sauvegardée. Les anciens plans restent lisibles ; ils ne sont pas automatiquement
  rechiffrés et les anciens exports ne sont pas effacés.
- Blocage des reprises contenant des chunks non confirmés, au lieu d'un retraitement
  automatique après une issue de commit incertaine. Voir [CHUNKS.md](CHUNKS.md).
- Contrôle de santé accessible sans session, validation supplémentaire après les
  scripts POST, attribution des requêtes de modification au compte connecté,
  limites de taille HTTP et délais d'expiration des requêtes suivies.
- Préservation Unicode SQL Server dans les paramètres et les valeurs de lookup.
- Vérification des empreintes des paquets Windows, durcissement des permissions,
  refus des historiques de migrations incomplets et suppression du bootstrap DDL
  au login. Le compte de service configuré est conservé lors des upgrades.
- Pilote PostgreSQL JDBC 42.7.14 et workflow de vérification Java 17 sous Windows.

## Installation et qualification

Exécuter le build décrit dans [COMPILATION.md](COMPILATION.md). Il lance les tests
isolés ; les tests d'intégration réels restent optionnels et nécessitent leur propre
configuration. Aucun test du build ne masque de données métier.

Les migrations V2_021 à V2_023 sont livrées mais ne sont pas appliquées au démarrage.
L'historique doit être réconcilié avant un upgrade s'il contient des trous. La migration
V2_021 étend les bornes du journal des chunks. Les profils SQL Server requièrent
la fonction cible documentée dans TEXT_MASKING.md.

Le script Install-PrivonymAuditFixes.ps1 est destiné au paquet local de corrections
avec son payload et son manifeste ; il ne s'exécute pas seul depuis les sources.
Pour les nouvelles installations et les upgrades, utiliser les constructeurs de
paquets dans packaging/windows.

## Limites restant à qualifier

### Correction de reprise du 25 octobre

Les chunks FAILED sont à nouveau repris, et les SUCCEEDED sont conservés.
Le blocage global de tous les chunks non réussis a été retiré. Les annulations
avec rollback confirmé sont reprenables ; les commits ou rollbacks incertains
restent bloqués. Aucune modification manuelle des statuts n'est nécessaire pour
reprendre les FAILED existants. Des tests rejouent six chunks réussis et deux
échoués sur les trois dialectes, sans retraiter les premiers, ainsi qu'un succès
non contigu et les pannes de commit, rollback et annulation.

### Rapports partiels et durée des requêtes

Un échec utilise les checkpoints courants pour compter les lignes et les tables
terminées, sans additionner les tentatives anciennes. Le PDF signale une validation
finale non confirmée au lieu d'afficher des zéros ou des métriques anciennes.
Le délai JDBC de 900 secondes introduit lors de l'audit n'est plus imposé par défaut
aux longues opérations de masquage. Il devient explicite via
`-Dprivonym.jdbc.queryTimeoutSeconds=900`. La valeur par défaut est 0, comme avant
l'audit (pas de timeout de statement) ; les valeurs admises vont de 0 à 86400.
Cela ne change pas les délais réseau propres aux pilotes ni les annulations
demandées par l'utilisateur ou le serveur de base.

Les garde-fous ne fournissent pas une transaction distribuée ni une reprise
automatique garantissant une seule application. Les certificats TLS, les droits
runtime, la gestion complète des secrets existants, les restaurations et les tests
de charge doivent être qualifiés pour chaque installation. Les profils de texte
ne constituent pas une preuve d'anonymisation exhaustive des données personnelles.
Les empreintes de livraison ne remplacent pas une signature de confiance.

Les diagnostics, sauvegardes et preuves d'une installation locale sont exclus du dépôt.

## Palette portable, lot 1 — V2_024

Quatre ajouts : MASK_PARTIAL, ROUND_NUMBER, NUMBER_BUCKET et TEXT_REDACT_COMMON,
avec douze expressions SQL pour les trois moteurs. Les paramètres sont bornés
dans le runtime. Les règles existantes conservent leurs versions.
Le précontrôle SQL Server exige la fonction cible pour le profil combiné.
La fonction SQL Server corrige aussi les classes de caractères email : un tiret
mal placé absorbait certains séparateurs et pouvait laisser un email apparent.
Les essais synthétiques couvrent les trois moteurs, les valeurs limites, NULL,
l'Unicode, les nombres négatifs et les textes longs. Détails et limites :
[ALGORITHM_PALETTE.md](ALGORITHM_PALETTE.md).

## Discovery : recommandations sur échantillons de texte

L'action explicite Discovery analyse localement des préfixes de valeurs textuelles
sur les trois moteurs. Elle distingue les formats dans le texte et certains indices
de personne, adresse ou organisation, puis explique la proposition d'algorithme.
Les propositions sont décochées et restent à valider. Les algorithmes indisponibles
pour le moteur ne sont pas sélectionnés arbitrairement. Le moteur d'exécution
n'appelle pas ce détecteur. Il n'y a ni nouveau modèle NER, ni service externe.
Voir [DISCOVERY_TEXT.md](DISCOVERY_TEXT.md) pour le périmètre, les limites et les tests.

## Discovery : prochaine version du Rule Set existant

Une connexion déjà liée à un Rule Set peut créer un brouillon au prochain numéro,
à partir de sa dernière version publiée. Ses règles, filtres et paramètres sont
copiés avant l'application des propositions sélectionnées. Aucun second Rule Set
n'est créé, aucun brouillon existant n'est écrasé et aucun propriétaire n'est changé.
La création est transactionnelle et exige les droits de modification du propriétaire
ou un compte administrateur. Les règles publiées et jobs restent inchangés.
