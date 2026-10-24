# Vision de Privonym

## Objectif

Permettre aux équipes de préparer des jeux de données de travail dont les
informations sensibles sont masquées, avec des règles compréhensibles,
versionnées et une exécution traçable.

Le masquage technique ne suffit pas à garantir une anonymisation irréversible.
Le risque de réidentification doit être évalué selon les données, les règles
choisies et les usages prévus. Privonym ne constitue pas, à lui seul, une
certification de conformité.

## Principes de conception

- Présenter le périmètre exact d'un traitement avant de le lancer.
- Conserver la cohérence métier et les relations entre tables lorsque requis.
- Expliquer les transactions, les engagements partiels et les possibilités de reprise.
- Produire des preuves de traitement et des contrôles indépendants de l'exécution.
- Protéger les accès et garder les secrets hors du dépôt.
- Maintenir un build reproductible dans un environnement isolé avec les JAR fournis.

## Feuille de route proposée

Les éléments ci-dessous sont des objectifs ; aucune date ni capacité nouvelle
n'est promise. L'état réellement implémenté est décrit dans [PROJET.md](PROJET.md).

| Priorité | Travail | Critère d'acceptation |
| --- | --- | --- |
| 1 | Fiabiliser la validation des lots | Une ligne oubliée provoque un échec détectable par un contrôle indépendant |
| 1 | Corriger le contrat de santé | Un serveur démarré répond au contrôle de l'upgrade dans les états connecté/déconnecté, avec ou sans utilisateur |
| 1 | Qualifier transactions et reprise | Tests d'échec et d'interruption montrant les effets persistants et le comportement de relance |
| 2 | Tester les bases réelles | Scénarios Oracle, PostgreSQL et SQL Server : plan, exécution, validation et rapport |
| 2 | Renforcer les tests d'accès | Matrice de profils, propriété des règles, refus d'accès et révocation de sessions |
| 2 | Automatiser la vérification | Pipeline exécutant le build et les vérifications sans secrets de production |
| 3 | Réduire la taille des classes | Extraction par responsabilité avec conservation des parcours existants |
| 3 | Harmoniser les points d'entrée | Capacités et limites explicites pour console, CLI et runner externe |

## Définition d'une version stable

Une version stable exige un build réussi, des tests d'intégration sur les
technologies annoncées, des essais d'interruption/reprise, une installation et
une mise à jour vérifiées, une documentation exacte et une liste explicite des
limites connues. Le tag 2.5.0 et la réussite des SmokeCheck ne suffisent pas à
attester tous ces critères.
