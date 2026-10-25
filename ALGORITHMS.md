# Catalogue et extension des algorithmes

Inventaire vérifié le 25 octobre 2026 : 35 définitions actives possèdent une version
publiée dans le référentiel inspecté. Une définition ou une implémentation publiée
n'est pas une preuve de disponibilité sur chaque installation ni une qualification
de toutes les versions de bases. Les profils et variantes ne sont pas tous des
méthodes de confidentialité indépendantes.

## Catalogue observé

Le premier lot supplémentaire est livré dans le code par V2_024 : MASK_PARTIAL,
ROUND_NUMBER, NUMBER_BUCKET et TEXT_REDACT_COMMON, soit 39 définitions après
activation. Paramètres, limites, qualification et procédure d'activation sont
décrits dans [ALGORITHM_PALETTE.md](ALGORITHM_PALETTE.md). L'inventaire de 35
ci-dessous décrit le référentiel avant cette migration.

| Famille | Définitions publiées |
| --- | --- |
| Suppression | NULLIFY, ZERO_NUMBER |
| Masquage | MASK_STRING, MASK_PAYMENT_CARD, CONDITIONAL_MASK |
| Texte | REGEX_MASK, TEXT_REDACT_EMAIL, TEXT_REDACT_PHONE_FR, TEXT_REDACT_PHONE_CH, TEXT_REDACT_IBAN_FR_CH |
| Structure documentaire | JSON_PATH_MASK |
| Dates | GENERALIZE_DATE_MONTH, GENERALIZE_DATE_YEAR, DATE_SHIFT_DETERMINISTIC |
| Nombres | PERTURB_NUMBER_PERCENT |
| Chaînes et contacts aléatoires | RANDOM_LOWER_STRING, RANDOM_UPPER_STRING, RANDOM_EMAIL_LOCAL_PART, RANDOM_PHONE_FR |
| Substitution | CONSISTENT_LOOKUP, LOOKUP_FROM_DATASET, LOOKUP_FIRST_NAME, LOOKUP_LAST_NAME, LOOKUP_CITY, LOOKUP_ADDRESS, LOOKUP_COMPANY, LOOKUP_DEPARTMENT |
| Données métier synthétiques | IBAN_GENERATOR, GEOGRAPHIC_COHERENT_ADDRESS |
| Pseudonymisation | HASH_SHA256, HMAC_SHA256, RELATIONSHIP_AWARE_TOKEN, PERSISTENT_MAPPING |
| Opérations de table | DELETE_TABLE_ROWS, TRUNCATE_TABLE |

Les 35 définitions ont des implémentations enregistrées sur Oracle, PostgreSQL et
SQL Server, sauf REGEX_MASK dont la dernière version publiée observée ne possède
que les implémentations Oracle et PostgreSQL. Cela ne supprime pas la nécessité de
vérifier les fonctions et extensions requises sur la cible.

En particulier :

- HMAC_SHA256 et RELATIONSHIP_AWARE_TOKEN sur SQL Server nécessitent une fonction
  cible dédiée. La présence de ces définitions ne l'installe pas.
- REGEX_MASK générique sur SQL Server 2019 ne fonctionne pas sans moteur regex dédié.
- Les quatre profils TEXT_REDACT SQL Server utilisent PRIVONYM_TEXT_REDACT ; leur
  périmètre est expliqué dans [TEXT_MASKING.md](TEXT_MASKING.md).
- IBAN_GENERATOR nécessite une fonction propre à chaque moteur.
- GEOGRAPHIC_COHERENT_ADDRESS utilise actuellement un jeu d'adresses US, pas une
  génération de colonnes d'adresse FR/CH liées entre elles.
- PERSISTENT_MAPPING utilise actuellement un lookup déterministe fini. Il ne
  garantit pas une correspondance bijective, une table de mapping persistante
  par valeur ni l'absence de collisions.
- Les profils de texte actuels repèrent des formats, pas automatiquement les noms
  ou toutes les adresses postales. L'IBAN détecté par motif n'est pas validé par checksum.
- Un hash ou un pseudonyme stable ne constitue pas, à lui seul, une anonymisation
  irréversible de l'ensemble du jeu de données.

## Palette cible à développer

Les éléments ci-dessous sont des extensions proposées, pas des fonctions déjà livrées.

| Famille | Extension | Critère de qualification |
| --- | --- | --- |
| Masquage partiel | Préfixe/suffixe configurables, e-mail, téléphone, code postal | Valeurs courtes, Unicode, longueur, NULL et champs mal formés |
| Identités | Profils de noms, sociétés et contacts localisés FR/CH | Cohérence d'une même identité entre colonnes et exécutions |
| Contacts | E-mails synthétiques et profils téléphoniques internationaux | Format, collisions et caractère non utilisable à qualifier |
| Dates | Décalage commun par personne ou dossier, tranches d'âge | Ordre chronologique, années bissextiles et bornes de dates |
| Nombres | Arrondi, intervalles, plafonnement et généralisation | Types décimaux, valeurs négatives, précision et bornes |
| Finance | Profils IBAN internationaux et données de paiement de test | Checksum, contraintes de format et absence de garantie de non-collision |
| Identifiants métier | Profils nationaux ou sectoriels configurables | Validation officielle du format et politique explicite de remplacement |
| Texte composé | Appliquer plusieurs profils à une même colonne | Toutes les occurrences, chevauchements et remplacement littéral |
| Texte intelligent | Personnes, organisations, lieux et adresses en contexte | Langues explicites, corpus annoté, précision et rappel |
| Documents | JSON imbriqué et tableaux, XML avec chemins | Préservation du document, types, NULL, gros volumes et parsing sécurisé |
| Relations | Mapping persistant avec gestion des collisions et de l'unicité | Relations parent/enfant, contraintes uniques, concurrence et reprise |
| Mélange | Permutation d'une colonne ou d'un groupe cohérent de colonnes | Distribution conservée, NULL, contraintes et reprise reproductible |
| Évaluation | Contrôles résiduels et analyse des quasi-identifiants | Mesures sur corpus représentatif, sans promesse générale de conformité |

Le chiffrement réversible et les techniques de conservation de format constituent
un chantier distinct : clés, rotation, accès au déchiffrement et bibliothèque
cryptographique éprouvée sont des prérequis. Ils ne doivent pas être présentés comme
de l'anonymisation définitive.

## Ordre de réalisation proposé

1. Premier lot livré dans V2_024 : masquage partiel, arrondis, intervalles numériques
   et composition des profils de texte. L'activation reste propre à l'installation.
2. Construire les profils métier localisés avec cohérence entre colonnes,
   gestion de l'unicité et jeux de données de substitution documentés.
3. Ajouter un processeur de texte indépendant du moteur cible pour la détection
   contextuelle, avec langues, entités, seuils et traitement des textes longs.
4. Ajouter les traitements de documents, permutations et mappings persistants
   dont les garanties nécessitent un état d'exécution partagé.

Pour le troisième lot, Presidio distingue l'analyse des entités et l'opération de
remplacement. Son intégration doit être facultative, configurable et évaluée sur
les langues réellement choisies. Aucun envoi de texte à un service distant ne doit
être implicite.

Sources techniques :
[Presidio Analyzer](https://presidio.dataprivacystack.org/analyzer/) et
[Presidio Anonymizer](https://presidio.dataprivacystack.org/anonymizer/).

## Contrat commun de chaque ajout

Chaque algorithme doit préciser ses types compatibles, les moteurs et versions
qualifiés, les paramètres et leurs bornes, les fonctions cibles requises, le
déterminisme, la conservation des NULL et les éventuelles collisions.

Les tests doivent couvrir des valeurs synthétiques normales et limites, l'Unicode,
les contraintes de longueur/précision, les filtres, les gros volumes et la reprise
après interruption. Une détection de données personnelles doit également fournir
des mesures de faux positifs et de faux négatifs.

Les nouvelles versions ne doivent pas réaffecter silencieusement les règles
publiées. Le catalogue, les fonctions cibles et le runtime doivent être qualifiés
séparément avant activation sur une installation.
