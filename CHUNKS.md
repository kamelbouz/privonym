# Correctifs des chunks portables — 24 octobre 2026

Ces corrections traitent des défauts constatés dans le code. Elles ne constituent
pas un diagnostic de la démonstration du 23 septembre, qui reste à reproduire.

## Changements

- Détection d'une clé primaire modifiée quelle que soit la notation utilisée
  (`"ID"`, `[ID]` ou `ID`). Une telle étape utilise le parcours direct plutôt
  que des bornes qui changent pendant le traitement.
- Conversion SQL Server adaptée au type : précision des float/real, quatre
  décimales des money, formats ISO des dates/heures et représentation hexadécimale
  des clés binaires avec conversion inverse correspondante.
- Comptage exact avant une exécution découpée. Le résultat ne remplace plus le
  nombre attendu. Une différence entre lignes attendues et traitées fait échouer
  l'étape. Ce comptage peut augmenter le temps de préparation des grandes tables.
- Migration V2_021 : extension des deux colonnes de bornes de JOB_CHUNK à
  2000 caractères, notamment pour les UUID et les clés textuelles courantes.
  Les anciennes valeurs et les checksums des migrations précédentes sont conservés.

## Installation et limites

La migration `database/migrations/V2_021_portable_chunk_boundaries_oracle.sql`
est destinée au référentiel Oracle, même pour les cibles PostgreSQL/SQL Server.
Elle doit passer par la procédure habituelle de migration/upgrade et son suivi
de checksums, lors d'une fenêtre sans exécution. Le serveur ne l'applique pas.
Tant qu'elle n'est pas appliquée, la limite de stockage existante reste inchangée.

Les tests du build couvrent la détection des clés, les formats des bornes, les
écarts de comptage et le parsing de migration. Ils ne remplacent pas des essais
sur bases réelles : dernier lot incomplet, UUID, clés binaires/décimales/temporelles,
interruption et reprise. Aucun jeu métier n'est modifié par ces tests.

Le traitement suppose des données stables pendant le job. Il n'existe pas de
transaction distribuée entre la cible et le référentiel : une interruption après
commit cible et avant journalisation demeure une limite de reprise à qualifier.
Depuis les correctifs d'audit du 24 octobre, une tentative de commit dont le
résultat n'est pas confirmé reste non réconciliée. Tout chunk non réussi
(y compris FAILED et CANCELLED) bloque la reprise automatique de l'étape.
Il faut vérifier les données cibles et réconcilier le journal avant reprise ;
ne pas remettre arbitrairement le statut à FAILED pour forcer un retraitement.
Ce garde-fou évite le retraitement automatique, sans fournir une transaction
distribuée ni une reprise automatique garantissant une seule application.
Un échec de validation ne revient pas sur les chunks déjà commités.
