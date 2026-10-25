# Analyse des textes dans la Discovery

Cette fonction sert uniquement à proposer des algorithmes dans Discovery.
Elle n'ajoute aucun détecteur à la génération SQL ou à l'exécution des jobs.
L'utilisateur examine les propositions et choisit explicitement les colonnes avant
de créer un Rule Set brouillon. Aucune proposition ne modifie un Rule Set existant.

## Parcours

1. Ouvrir Discovery et sélectionner une connexion activée.
2. Cliquer sur **Analyze schema and text samples**.
3. Examiner la classification, le score, les types détectés, le nombre de valeurs
   examinées, l'explication et l'algorithme proposé.
4. Ajuster l'algorithme et cocher les colonnes voulues pour créer un brouillon.

Une connexion qui possède déjà un Rule Set peut créer sa prochaine version
brouillon, sans créer un second Rule Set. Les cases sont initialement décochées. Aucun
algorithme n'est choisi arbitrairement lorsqu'une recommandation est indisponible.
La liste proposée utilise les implémentations publiées de la dernière version
publiée pour le moteur cible. Les fonctions cibles et les contraintes restent à
vérifier avant exécution.

## Connexion possédant déjà un Rule Set

Le bouton devient **Create draft vN**, où N est le plus grand numéro existant + 1.
Le code et le nom du Rule Set sont conservés et affichés sans modification.
La dernière version publiée sert de base. Toutes ses règles de colonne et de
table sont copiées avec leurs filtres, ordres, états activés/désactivés et paramètres.
Les règles non sélectionnées restent donc présentes dans le nouveau brouillon.

Les propositions sélectionnées ajoutent les nouvelles colonnes. Pour une colonne
déjà configurée, un choix différent change l'algorithme uniquement dans le nouveau
brouillon ; ses anciens paramètres incompatibles sont retirés, tandis que le filtre,
l'ordre et l'état sont conservés. Il faut ensuite renseigner les éventuels paramètres
requis du nouvel algorithme avant publication. Si le code d'algorithme est inchangé,
la version d'algorithme déjà utilisée et ses paramètres sont conservés exactement.
Sans proposition sélectionnée, le brouillon est simplement une copie de la version
publiée. Pour une connexion sans Rule Set, la création initiale reste une version 1
et exige au moins une sélection.

Un seul brouillon est permis par Rule Set : s'il existe, un lien permet de l'ouvrir ;
il n'est pas écrasé par une nouvelle analyse. Une demande concurrente est contrôlée
à nouveau dans la transaction. La création Discovery et la création habituelle
verrouillent le Rule Set pour sérialiser l'attribution du prochain numéro.
L'opération exige le droit de modifier le Rule Set et sa propriété, ou un compte
administrateur. Elle ne réattribue pas le propriétaire. Les versions publiées et
les jobs qui les utilisent restent inchangés.

Le point d'entrée de cette création est `POST /api/v1/discovery/versions/{rulesetId}`.
Les sélections sont vérifiées contre la connexion du Rule Set et les implémentations
publiées de son moteur. La copie et les changements du brouillon forment une seule
transaction ; une sélection invalide provoque son annulation.

## Analyse disponible

La reconnaissance est locale, en Java, avec des expressions de format et des
indices de contexte. Elle ne dépend d'aucun service externe et n'envoie pas les
échantillons à un modèle distant. Ce premier niveau n'est pas un modèle NER.

| Indice observé dans le texte | Proposition |
| --- | --- |
| Email | TEXT_REDACT_EMAIL |
| Téléphone international français | TEXT_REDACT_PHONE_FR |
| Téléphone international suisse | TEXT_REDACT_PHONE_CH |
| Téléphone national FR/CH ambigu | TEXT_REDACT_COMMON |
| Format IBAN français ou suisse | TEXT_REDACT_IBAN_FR_CH |
| Plusieurs formats | TEXT_REDACT_COMMON |
| Nom précédé d'un titre, indice d'adresse ou d'organisation | NULLIFY, avec avertissement sur la suppression de la valeur entière |

Exemple : un email à l'intérieur d'une note conduit à un profil de remplacement
dans le texte, plutôt qu'à la génération d'un email remplaçant toute la note.
Les noms de colonnes apportent également des indices. Un nom de colonne indiquant
un secret conserve la priorité de suppression entière même si son contenu
ressemble à un email. Les recommandations de métadonnées tiennent compte du type
natif : un nom contenant EMAIL sur une colonne numérique ne propose pas de
générateur de chaîne.

Les indices de personnes comprennent certains titres FR/EN/DE suivis d'un mot
commençant par une majuscule. Les indices d'adresses utilisent certains termes de
voie ; les organisations utilisent des mots introducteurs. Des noms tels que
« Marie Dupont » sans contexte peuvent être manqués, et les indices peuvent
produire des faux positifs. Les formats IBAN ne valident pas la clé de contrôle.
Le score sur 100 exprime la force de l'heuristique, pas une probabilité mesurée.

Un indice de nom ou d'adresse ne doit pas suggérer que TEXT_REDACT_COMMON sait
retirer ce passage : cet algorithme ne traite que les formats documentés.
NULLIFY est une proposition explicite de suppression entière, à examiner selon
le besoin métier et les contraintes NOT NULL. Aucun remplacement ciblé de noms,
adresses ou organisations n'est ajouté à l'exécution dans ce lot.

## Échantillonnage et confidentialité

- Oracle : colonnes de texte dont CLOB ; PostgreSQL : types de texte compatibles ;
  SQL Server : colonnes caractères, text/ntext et nvarchar(max).
- Au plus 100 colonnes textuelles, 20 valeurs non NULL par colonne, et les 16 384
  premiers caractères de chaque valeur ; budget global de 2 millions de caractères.
- Budget de 40 secondes pour l'étape d'échantillonnage, avec un délai de 5 secondes
  par requête. La synchronisation préalable des métadonnées est distincte.
- Les requêtes source sont des SELECT bornés. Aucun DML ni DDL n'est effectué sur
  la cible. Les métadonnées du catalogue sont synchronisées comme auparavant.
- Les valeurs ne sont ni retournées dans l'API, ni persistées dans le référentiel,
  ni journalisées. Seuls les types détectés, scores, explications et compteurs sont
  exposés. Les textes restent transitoirement en mémoire pendant l'analyse.

L'échantillon prend les premières valeurs renvoyées, sans tirage représentatif ni
parcours exhaustif des CLOB. Une absence de détection ne prouve donc pas l'absence
de données sensibles. Les limites, colonnes inaccessibles et échecs de lecture sont
signalés ; les classifications fondées sur les noms restent disponibles.

L'analyse du contenu est accessible uniquement par l'action explicite Discovery
(`POST /api/v1/discovery/scan/{id}`). Les synchronisations internes continuent à
utiliser `scan()` sans échantillonnage ; `analyzeContent()` porte l'analyse nouvelle.
Le chargement d'une page ou une synchronisation liée à un job ne déclenche pas
cette analyse.

## Qualification

Les tests de compilation couvrent les formats imbriqués dans des phrases, les
indices contextuels, les collisions de formats, les valeurs vides, le préfixe
borné, la non-exposition des passages, les suggestions indisponibles et les types.
Le test HTTP vérifie le point d'entrée dédié, les explications et les avertissements.

`DiscoveryDraftIntegrationCheck` vérifie le passage v5 vers v6 sur un Rule Set
synthétique, la conservation des règles/paramètres/filtres, le choix d'un nouvel
algorithme, l'ajout d'une colonne, la copie sans sélection, le refus d'un second
brouillon et le rollback sur sélection invalide. Toutes ses métadonnées de test
sont annulées ; aucune table métier n'est appelée. Les tests HTTP vérifient aussi
les droits du propriétaire et le refus d'un autre utilisateur.

`DiscoverySamplingIntegrationCheck` qualifie les trois dialectes sur des CTE
synthétiques : source de plus de 65 000 caractères, Unicode, lecture en flux,
limite de 20 lignes et préfixe exact de 16 384 caractères. Il n'appelle pas le scan
d'une connexion métier et ne lit aucune table métier.

Une éventuelle reconnaissance linguistique plus générale reste un lot distinct,
à évaluer sur un corpus annoté. Son périmètre prévu reste Discovery et la
recommandation ; une intégration dans l'exécution nécessiterait une nouvelle demande.
