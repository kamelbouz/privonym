# Lot 2 — Exécution et reprise — 8 novembre 2026

Reprise vérifiée sans intervention SQL : [REPRISE_ORACLE.md](REPRISE_ORACLE.md).

## Comportement

- Une reprise garde sa version publiée du Rule Set, le SQL planifié, sa stratégie et la taille des chunks. Publier une nouvelle version ne rebranche plus silencieusement un ancien job. Créer un nouveau job pour changer de version.
- Le nombre de workers reste modifiable lorsque le job est arrêté. Après une exécution entièrement réussie, une nouvelle exécution explicite peut changer de géométrie et efface ses anciens checkpoints.
- Les checkpoints SUCCEEDED sont conservés lors d'une reprise partielle. Les étapes complètement validées restent SUCCEEDED dans le journal d'un échec ; les étapes non commencées restent PENDING. Les échecs ont une date de fin.
- Les exécuteurs parallèles et DIRECT avec journal surveillent l'état du job dans une connexion indépendante. CANCEL reste CANCELLING jusqu'à confirmation du runner ; l'absence d'un runner dans la JVM de la console ne prouve plus un rollback.
- Avant le commit cible, le worker verrouille la ligne du job et vérifie l'identité de sa tentative. Une reprise ou une annulation observée interdit le commit. Une indisponibilité du contrôle interrompt le SQL suivi et bloque les commits.
- DIRECT avec relations utilise des checkpoints DIRECT_TX pour l'ensemble de sa transaction. Une transaction déjà entièrement journalisée n'est pas réappliquée. Un résultat de commit ou de rollback incertain garde ses checkpoints non réconciliés.
- Les phases PRE/POST ont des reçus STARTED/COMPLETED liés à la planification. Une phase terminée n'est pas rejouée. Une phase commencée sans reçu de fin bloque le rejeu, car un script SQL peut contenir des commits implicites.

## Console de récupération

Jobs → menu ⋯ → EXECUTION / RECOVERY affiche la stratégie demandée, le mode constaté dans le journal et les checkpoints en échec ou incertains. Sans exécution, le panneau fournit une estimation à partir du catalogue ; le type réel de clé et le seuil AUTO nécessitent encore une qualification de la cible. La confirmation d'exécution affiche également ces modes.

Un administrateur peut consigner un rollback confirmé (le chunk devient retryable) ou un commit confirmé avec son nombre exact de lignes (le checkpoint est conservé). Il doit arrêter le runner, vérifier les transactions sur la cible, documenter cette vérification, cocher l'attestation et saisir exactement RECONCILE JOB <id> CHUNK <id>. Une décision est auditée avec son utilisateur. JOB_MANAGE seul ne donne pas ce droit. Aucun checkpoint SUCCEEDED n'est modifiable par cette opération.

Pour un runner réellement arrêté dont le heartbeat est périmé depuis plus de 30 secondes, l'administrateur peut confirmer STOPPED avec RECONCILE JOB <id> STOPPED. Cela rend le job terminal sans inventer de résultat pour ses chunks. Un heartbeat ancien seul ne déclenche aucune modification automatique.

Pour DIRECT_TX, vérifier et consigner une décision cohérente pour toutes les étapes de la même transaction. Un ensemble partiellement confirmé reste bloqué. Le panneau ne répare pas les données cibles et ne devine pas si une transformation non idempotente a déjà été appliquée.

API : GET /api/v1/jobs/<id>/recovery ; POST au même chemin, réservé aux administrateurs authentifiés, avec decision, chunkId, rows, note, confirmation et verified=true. La décision STOPPED ne réconcilie aucun chunk.

## Limites et déploiement

Ce mécanisme ne constitue pas une transaction distribuée entre cible et référentiel : une coupure entre les deux commits nécessite toujours une vérification. Il réduit les rejeux dangereux et rend cette vérification accessible dans le produit. Les scripts historiques sans reçu ne permettent pas de reconstruire rétroactivement leurs effets ; un reçu de script incomplet nécessite une vérification spécifique, hors du panneau de chunks.

Aucune migration de schéma n'est nécessaire. Les anciens journaux sont conservés. Le chunk historique 3032 n'est ni modifié ni relancé par la livraison de ce lot. Aucun masquage de tables métier n'est exécuté par les tests.

Compilation et tests : .\build.cmd -JdkHome 'C:\Program Files\Java\jdk-17'. Les tests injectent des pertes de réponse au commit, échecs de rollback et de checkpoint, annulations externes et changements de propriétaire dans des doubles JDBC isolés ; ils vérifient les droits HTTP et la conservation des chunks réussis sur Oracle, PostgreSQL et SQL Server. Ces tests ne remplacent pas une campagne de charge et de coupures réseau réelles sur les trois moteurs.

Les JAR installés nécessitent un redémarrage normal de Privonym pour être chargés. Aucun serveur caché n'est démarré par cette livraison.

## Correctif de surveillance et d'arrêt des workers — 8 novembre 2026

Un timeout isolé de la requête de surveillance (SQLTimeoutException ou Oracle ORA-01013) est réessayé après rollback de la connexion de contrôle. Trois timeouts consécutifs provoquent l'arrêt ; une perte de propriétaire, une annulation utilisateur ou une autre erreur reste bloquante immédiatement. Chaque commit cible exige toujours une nouvelle lecture verrouillée du propriétaire dans le référentiel : aucune tolérance n'est ajoutée à cette barrière.

Une erreur de contrôle conserve maintenant sa cause SQL, son code fournisseur et son SQLState dans le message du job et la console serveur. Les workers ne remplacent plus cette cause par le simple message d'annulation de la requête cible. Le pool utilise un arrêt sans interruption des threads JDBC pour laisser finir rollback et journalisation ; l'interruption du caller est conservée et la demande d'annulation SQL reste explicite.

Tests : timeout isolé récupéré sans annulation SQL, timeouts répétés bloquants avec cause conservée, perte de connexion/propriétaire, fin du rollback/journal malgré un caller interrompu. ExecutionControlOracleIntegrationCheck qualifie le heartbeat et une contention réelle de la barrière de commit Oracle sur un job synthétique dédié, supprimé en fin de test, sans SQL métier.

Les anciens messages génériques ne permettent pas de retrouver rétroactivement la cause précise d'un incident de contrôle. Les chunks déjà incertains restent soumis au parcours de récupération ; aucun RUNNING historique n'est automatiquement déclaré annulé ou réessayable par cette correction.

## Régression du verrou de commit corrigée

Le verrou partagé par les workers était acquis avec une attente JDBC limitée à cinq secondes. Si un autre worker gardait le verrou pendant un commit cible long, un worker sain était annulé avec ORA-01013. Cette régression a été reproduite sur Oracle réel avec deux connexions et un verrou maintenu 6,5 secondes.

La barrière utilise maintenant FOR UPDATE NOWAIT et réessaie une contention ORA-00054 en vérifiant l'annulation entre essais. Le timeout réseau/requête ne sert plus de délai maximal d'attente d'un autre worker. Une fois le verrou obtenu, l'identité et le statut restent contrôlés avant chaque commit ; une perte de connexion et un changement de propriétaire ne sont pas ignorés.

OracleChunkRoundTripIntegrationCheck qualifie le moteur réel sur une table synthétique dédiée : 18000 lignes, chunks de 1000, quatre workers, erreur injectée, rollback, reprise, conservation des checkpoints SUCCEEDED et vérification que chaque ligne a été modifiée exactement une fois. Le job et la table synthétiques sont supprimés en fin de test. Les tests contrôlent aussi l'annulation et le changement de propriétaire pendant l'attente du verrou. Les anciens chunks à issue inconnue ne sont pas réconciliés automatiquement.
