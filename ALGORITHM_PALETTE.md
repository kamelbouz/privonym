# Extension de la palette : premier lot

La migration additive `V2_024_portable_algorithm_palette_oracle.sql` apporte quatre
définitions, avec une implémentation pour Oracle, PostgreSQL et SQL Server chacune.
Elle ne réaffecte aucune règle existante. Après activation, un référentiel qui
contenait les 35 définitions de l'inventaire en contient 39.

| Code | Paramètres et valeurs par défaut | Exemple |
| --- | --- | --- |
| MASK_PARTIAL | visible_prefix_length=0, visible_suffix_length=4, mask_character=* | ABCDEFGHIJ devient ******GHIJ |
| ROUND_NUMBER | scale=0 | 123.456 avec scale=2 devient 123.46 |
| NUMBER_BUCKET | bucket_size=10 | 27.5 devient 20 ; -0.01 devient -10 |
| TEXT_REDACT_COMMON | replacement=ANONYMIZED | Remplace dans le même texte les emails, téléphones FR/CH et formats IBAN FR/CH |

Les quatre opérations sont déterministes et conservent les NULL. Elles ne
garantissent pas l'unicité des résultats. Une règle doit être ajoutée explicitement
pour utiliser un nouvel algorithme ; les jobs existants ne changent pas seuls.

## Types et limites

MASK_PARTIAL traite des colonnes de texte, dont CLOB Oracle, text PostgreSQL et
nvarchar(max) SQL Server. Les longueurs visibles sont des entiers de 0 à 100.
Le caractère de masquage est l'un de `*`, `#`, `X`, `x`, `?`.
Si la valeur est trop courte pour les deux parties visibles, elle est entièrement
masquée. Les espaces de fin comptent dans la longueur. Les tests Unicode couvrent
les caractères BMP ; le comptage des caractères supplémentaires dépend du moteur
et de la collation. Oracle assimile la chaîne vide à NULL.

ROUND_NUMBER accepte scale de -6 à 6 : une valeur négative arrondit les dizaines,
centaines, etc. NUMBER_BUCKET accepte un intervalle de 0.000001 à 1000000000000
et retourne sa borne inférieure, y compris pour les nombres négatifs.
Ces deux algorithmes visent les nombres exacts. Les tests portables utilisent
decimal(28,6) et Oracle NUMBER ; ils ne qualifient pas tous les flottants et toutes
les précisions possibles. La précision de destination et ses contraintes doivent
accepter le résultat, notamment après un arrondi ou une borne négative.
La généralisation numérique seule ne prouve pas l'anonymat d'un jeu de données.

TEXT_REDACT_COMMON compose les quatre profils de format existants, avec un
remplacement littéral commun de 100 caractères maximum. Le contenu restant est
conservé, y compris sur des textes longs. Les noms, adresses et organisations ne
sont pas détectés. Le profil IBAN ne vérifie pas la clé de contrôle. Les limites de
détection et les frontières des profils diffèrent entre moteurs ; ce lot ne
constitue pas un détecteur exhaustif de données personnelles. Les profils passent
successivement : un remplacement qui ressemble lui-même à une donnée détectable
peut être retraité par un profil suivant. Le remplacement par défaut évite ce cas.

## Qualification et activation

La compilation exécute les contrôles de paramètres et le parsing des migrations.
`AlgorithmPaletteCheck` ajoute les essais en base sur valeurs synthétiques :
NULL, bornes, valeurs courtes, nombres négatifs, espaces de fin, Unicode,
remplacements littéraux avec apostrophe et antislash, textes de plus de 65 000
caractères et présence de plusieurs formats dans le même texte.

Par défaut, cet outil compile le bloc Oracle sans l'exécuter et effectue seulement
des SELECT de qualification. L'option `--qualify-helper` évalue le corps SQL Server
corrigé avec des variables locales et des SELECT, sans créer ou modifier de fonction.
Cela qualifie ses opérations successives ; la qualification de l'expression SQL
imbriquée installée est effectuée lors de l'activation.

L'option explicite `--apply` exige l'absence de job actif, sauvegarde la fonction
SQL Server reconnue, applique sa correction de séparateurs et ne la valide qu'après
les essais. Elle ajoute ensuite les quatre définitions au référentiel et vérifie
les douze expressions avant commit. Les deux bases ont des commits distincts : une
erreur ultérieure du catalogue ne retire pas une correction SQL Server déjà validée.
Une fonction SQL Server personnalisée ou illisible n'est pas remplacée.

Le profil combiné SQL Server nécessite `dbo.PRIVONYM_TEXT_REDACT` corrigée : le
tiret est placé en tête des classes de caractères pour empêcher l'absorption de
séparateurs, notamment le point-virgule après un email. Aucun CLR n'est requis.
Les trois autres algorithmes utilisent les fonctions SQL natives.

L'activation ciblée enregistre l'empreinte du fichier de migration utilisé. Elle ne
rejoue pas les anciennes migrations manquantes. Un job en cours doit terminer avant
l'activation ; les nouveaux JAR nécessitent un redémarrage normal du serveur.
Aucune qualification ne lance un job de masquage sur les tables métier.

Les lots suivants restent ceux de [ALGORITHMS.md](ALGORITHMS.md) : identités métier
cohérentes et localisées, détection contextuelle facultative, puis documents,
permutations et mappings persistants.
