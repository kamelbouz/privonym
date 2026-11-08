# Reprise et vérification historique Oracle

## Parcours normal

EXECUTE conserve les chunks SUCCEEDED et rejoue les FAILED dont l'échec est journalisé. Le chargement conserve toutes les étapes du plan approuvé, y compris celles déjà réussies : son empreinte ne change plus lors d'une reprise partielle. Les checkpoints permettent de sauter leurs traitements terminés.

Un statut FAILED modifié manuellement sans date de fin ni erreur n'est pas une preuve de rollback. Il est présenté comme une issue non confirmée, au même titre qu'un ancien RUNNING. Il n'est pas nécessaire de modifier le référentiel pour le récupérer.

## Vérifier depuis la console

Jobs → ⋯ → EXECUTION / RECOVERY → sélectionner le chunk → verify rollback using Oracle history → Verify historical data and enable retry.

Cette action est réservée aux administrateurs. Elle verrouille le job et le checkpoint, puis prend un verrou partagé NOWAIT sur la table cible. Elle compare toutes les colonnes scalaires prises en charge entre les lignes actuelles et Oracle Flashback à la date de début du chunk, dans les bornes ROWID enregistrées. Les ajouts, suppressions et différences sont détectés par une jointure externe complète. Aucun contenu métier n'est affiché.

Seule une plage non vide entièrement inchangée permet de journaliser la preuve et de rendre le chunk réessayable. La décision est auditée ; le verrou cible est conservé jusqu'au commit du journal, puis libéré par rollback de la connexion de vérification. La vérification ne lance aucun masquage. Les checkpoints SUCCEEDED restent protégés.

Si l'historique est indisponible, si les lignes ont changé, si une colonne n'est pas prise en charge ou si la table est en cours de modification, le contrôle refuse de confirmer et ne change pas le checkpoint. Cette vérification exige actuellement une cible et un référentiel dans la même base/conteneur Oracle pour partager la référence temporelle ; les bornes DIRECT/DIRECT_TX et les autres moteurs ne sont pas couverts par cette comparaison.

Après vérification réussie, fermer le panneau et utiliser EXECUTE pour reprendre normalement. Il n'y a pas de déclaration manuelle de rollback à rédiger pour cette action automatique. Les décisions manuelles COMMIT/ROLLBACK restent distinctes et demandent leurs confirmations habituelles.

## API et tests

POST /api/v1/jobs/<id>/recovery avec decision=VERIFY_ROLLBACK et chunkId. La session doit être administrateur ; les confirmations manuelles ne remplacent pas les contrôles automatiques de ce mode.

Tests : maintien de l'empreinte du plan comprenant une étape réussie ; distinction entre FAILED confirmé et changement manuel ; vérification Oracle réelle sur fixtures dédiées avec acceptation de données inchangées, refus de données modifiées, conservation des succès et refus d'un historique absent. Le moteur complet est également testé par panne et reprise sur 18000 lignes réparties en 18 chunks, sans double traitement. Les fixtures sont supprimées en fin de test.

Pour l'incident du job 121, les preuves détaillées et les contrôles avant/après sont conservés localement dans audit/2026-11-08/recovery-job121 sur l'installation. Les données cibles ne sont pas modifiées par la récupération. Les JAR installés nécessitent un redémarrage normal du serveur pour charger le nouveau parcours.
