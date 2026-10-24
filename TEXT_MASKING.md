# Masquage ciblé du texte et des CLOB

## Algorithmes

La migration V2_022 ajoute les profils suivants pour Oracle (dont CLOB) et
PostgreSQL (TEXT). V2_023 ajoute leurs implémentations SQL Server (nvarchar(max)).
Chaque profil expose seulement le paramètre
`replacement`, limité à 100 caractères, avec `ANONYMIZED` comme valeur par défaut.

| Code | Exemples de formats détectés |
| --- | --- |
| `TEXT_REDACT_EMAIL` | `alice@example.com`, `bob@test.ch` |
| `TEXT_REDACT_PHONE_FR` | `06 12 34 56 78`, `+33 6 12 34 56 78`, `0033…` |
| `TEXT_REDACT_PHONE_CH` | `079 123 45 67`, `+41 79 123 45 67`, `0041…` |
| `TEXT_REDACT_IBAN_FR_CH` | Candidats IBAN de longueur française ou suisse, avec espaces facultatifs |

Le reste du texte est conservé. Les profils traitent toutes les occurrences
correspondant au motif. La valeur de remplacement est littérale, y compris
les apostrophes et antislashs.

```text
Avant : Contacter Élodie à alice@example.com pour la livraison.
Après : Contacter Élodie à ANONYMIZED pour la livraison.
```

Choisir `replacement=[EMAIL]` permet de remplacer uniquement l'adresse par
`[EMAIL]`. Le nom Élodie reste inchangé : ces profils ne font pas de reconnaissance
automatique des noms, adresses postales ou autres entités dans le texte libre.
La composition de plusieurs profils sur une même colonne n'est pas ajoutée ici.

## REGEX_MASK et gros CLOB Oracle

La migration publie une nouvelle version de `REGEX_MASK`. Son expression Oracle
applique `REGEXP_REPLACE` directement à la colonne, sans conversion `TO_CHAR`.
Elle conserve ainsi le traitement CLOB au-delà des limites d'une chaîne VARCHAR2.
Les paramètres `pattern` et `replacement` restent disponibles pour les formats
métier spécifiques. Dans cet algorithme générique, la valeur de remplacement
conserve les conventions de la regex de la base (notamment les références de groupes).

Les règles existantes restent liées à leur ancienne version d'algorithme.
Pour adopter le comportement CLOB, travailler dans un ruleset brouillon, choisir
la nouvelle version et renseigner/vérifier le motif et le remplacement avant
publication. Aucune règle publiée n'est automatiquement réaffectée.

## Disponibilité et activation

- Les profils sont disponibles sur Oracle, PostgreSQL et SQL Server.
- SQL Server 2019 utilise la fonction T-SQL `dbo.PRIVONYM_TEXT_REDACT`, fournie
  dans `database/target-functions/sqlserver/privonym_text_redact.sql`. Elle traite
  les quatre formats sans CLR ni service externe. Le preflight exige sa présence
  avant d'exécuter un job qui utilise ces profils. Installer la fonction sur
  chaque base cible SQL Server concernée ; le catalogue Oracle ne l'installe pas.
- `REGEX_MASK` avec une expression régulière arbitraire reste distinct : sur
  SQL Server 2019, il exige toujours un moteur externe tel que la fonction
  `dbo.PRIVONYM_REGEX_REPLACE`. Cette livraison n'installe pas ce moteur générique.
- Les fichiers seuls ne modifient pas le catalogue en base. Appliquer V2_022 par
  la procédure habituelle de migration avec suivi de checksum, puis recharger
  les algorithmes dans la console. Lire les migrations précédentes encore en
  attente avant une mise à jour globale ; V2_021 concerne les chunks.
- Le serveur ne lance pas les migrations au login. Aucun job métier ne doit
  être exécuté pour installer ces définitions.

Dans l'installation locale C:\Privonym, V2_022 a été appliquée le 24 octobre 2026
avec enregistrement de son checksum. Les dix implémentations attendues (cinq
algorithmes sur deux technologies) sont présentes et les liens des règles
existantes sont inchangés. V2_021 n'a pas été appliquée par cette activation.

V2_023 a aussi été appliquée dans l'installation locale et les quatre
implémentations SQL Server ont été vérifiées après publication.
La prise en charge SQL Server est ajoutée par V2_023. Elle ne change pas les
versions Oracle/PostgreSQL ni les liens des règles existantes. Les profils SQL
Server détectent des formats courants par un parcours T-SQL du texte ; leurs
limites de détection ne sont pas celles d'un moteur regex généraliste. Les
emails SQL Server visés utilisent des caractères ASCII ; les formats moins
courants et les limites de tokens peuvent différer selon le moteur.

## Limites de détection

Ce sont des profils de formats courants, pas des validateurs exhaustifs.
Ils ne vérifient ni l'existence d'un email ni la clé de contrôle d'un IBAN.
Les numéros nationaux français et suisses peuvent être ambigus : choisir le
profil adapté au contenu. Certains formats internationaux, extensions
téléphoniques, adresses email inhabituelles et identifiants adjacents peuvent
nécessiter un motif métier. Évaluer faux positifs et données non détectées sur
un échantillon représentatif avant tout traitement réel.

## Vérifications

Le build vérifie le parsing de V2_022, les quatre profils attendus et l'absence
de réaffectation des règles dans la migration.

Le 24 octobre 2026, les expressions Oracle ont aussi été testées avec des SELECT
en transaction de lecture seule : quatre profils, texte de plus de 65 536
caractères, accents, occurrences multiples et en fin de document, remplacement
littéral, absence de correspondance et NULL. Le véritable template REGEX_MASK
de la migration a été testé sur ce long texte. Aucune table métier n'a été modifiée.
Le bloc PL/SQL a aussi été compilé sans exécution par DBMS_SQL.PARSE avant
activation. Les quatre profils ont ensuite passé les tests sur PostgreSQL 16
et SQL Server 2019 : textes de plus de 65 536 caractères, occurrences multiples
et en fin de texte, accents, remplacement Unicode avec apostrophe et antislash,
NULL et absence de correspondance. La fonction SQL Server a été installée dans
la cible locale ; aucune table métier n'a été modifiée.
REGEX_MASK v2 a également passé les tests de long texte sur PostgreSQL 16.

Ce test d'intégration est explicite et ne tourne pas dans le build hors ligne :

```powershell
java --class-path "lib\*" `
    src\test\java\com\privonym\repository\oracle\TextMaskingOracleCheck.java `
    config\privonym-job.properties `
    database\migrations\V2_022_clob_text_masking_profiles_oracle.sql
```

Il lit les identifiants existants du fichier local protégé et n'affiche pas les
mots de passe. Il compile le bloc de migration sans l'exécuter et réalise des
SELECT sur des textes synthétiques dans une transaction de lecture seule.

Le test multi-base lit les connexions déjà enregistrées dans Privonym et leurs
identifiants chiffrés. Par défaut, il n'installe rien et vérifie uniquement des
textes synthétiques. Après compilation :

```powershell
$build = Get-ChildItem .\target -Directory -Filter '.build-*' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
java "-Dprivonym.secret.key.file=$PWD\config\privonym-secret.key" `
    -classpath "$($build.FullName)\test-classes;target\*;lib\*" `
    com.privonym.repository.oracle.PortableTextMaskingCheck `
    config\privonym-job.properties `
    database\target-functions\sqlserver\privonym_text_redact.sql
```

L'option explicite `--install-sqlserver-helper` permet d'installer la fonction
sur une nouvelle cible avant les tests. Elle refuse de remplacer une fonction
déjà existante et ne modifie aucun paramètre de sécurité. Le compte doit disposer
des droits de création de fonction ; les jobs ordinaires ne doivent pas recevoir
ces droits uniquement pour fonctionner.
