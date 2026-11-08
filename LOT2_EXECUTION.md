# Lot 2 — Exécution et reprise — 8 novembre 2026

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
