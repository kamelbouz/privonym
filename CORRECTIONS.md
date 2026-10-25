# Corrections du 24 octobre 2026

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

Les garde-fous ne fournissent pas une transaction distribuée ni une reprise
automatique garantissant une seule application. Les certificats TLS, les droits
runtime, la gestion complète des secrets existants, les restaurations et les tests
de charge doivent être qualifiés pour chaque installation. Les profils de texte
ne constituent pas une preuve d'anonymisation exhaustive des données personnelles.
Les empreintes de livraison ne remplacent pas une signature de confiance.

Les diagnostics, sauvegardes et preuves d'une installation locale sont exclus du dépôt.
