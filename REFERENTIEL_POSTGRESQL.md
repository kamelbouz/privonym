# Référentiel PostgreSQL et distributions Windows/Linux

Le référentiel PostgreSQL 17 contient la configuration, les règles, les utilisateurs,
les secrets chiffrés, les jobs et leur journal. Les bases cibles restent Oracle,
SQL Server ou PostgreSQL. Leurs connexions et leur SQL ne passent pas par la
couche de compatibilité du référentiel.

Java 17 ou supérieur et PostgreSQL sont nécessaires. PostgreSQL est un service
distinct ; les archives de Privonym ne contiennent ni serveur PostgreSQL ni JRE.

## Nouvelle installation

1. Créer une base PostgreSQL UTF-8 vide et un rôle dédié avec les droits nécessaires
   pour installer son schéma. Ne pas utiliser une base applicative existante.
2. Décompresser l'archive Windows ZIP ou Linux TAR.GZ dans un répertoire accessible
   en écriture au compte qui exécutera Privonym.
3. Copier `config/privonym-postgresql.properties.example` vers
   `config/privonym.properties`, puis régler l'URL, le rôle, l'adresse HTTP et le port.
4. Initialiser : `privonym-init-postgresql.cmd` sous Windows ou
   `sh privonym-init-postgresql.sh` sous Linux. Le mot de passe est demandé dans le
   terminal. L'initialisation refuse tout schéma contenant déjà des tables.
5. Démarrer : `privonym-server.cmd` ou `sh privonym-server.sh`, puis ouvrir
   `http://127.0.0.1:8080/login`. Créer le premier administrateur de l'application.

`JAVA_HOME` doit désigner Java 17. Sous Windows, le lanceur PowerShell accepte
aussi `-JavaPath`. Les fichiers `.sh` peuvent être lancés avec `sh` sans changer
leurs permissions. Pour les lancer directement, utiliser `chmod +x privonym-*.sh`.

Pour connecter automatiquement le référentiel au démarrage, fournir
`PRIVONYM_REPOSITORY_PASSWORD` au processus serveur, ou ajouter
`repository.password` à sa configuration privée. Protéger cette configuration
avec les ACL Windows ou `chmod 600` sous Linux. L'authentification des utilisateurs
de Privonym reste obligatoire. Les archives publiques contiennent uniquement un
exemple sans mot de passe.

## Migrer un référentiel Oracle existant

Ne pas lancer l'initialisation d'un référentiel neuf avant la migration.
La destination doit rester vide. Conserver le référentiel Oracle et sauvegarder
les binaires, les configurations et **la clé `config/privonym-secret.key`**.
Cette même clé est indispensable pour utiliser les connexions déjà enregistrées.

L'outil réalise une transaction Oracle en lecture seule et une transaction
PostgreSQL pour créer et charger le schéma. Il conserve les identifiants, versions,
hashs de mots de passe, secrets chiffrés et checkpoints. Il refuse un schéma
inattendu ou des exécutions actives/interrompues à résoudre. Il compare les nombres
de lignes et une empreinte du contenu de chaque table, valide les clés étrangères,
réinitialise les séquences et installe les contraintes avant le commit.

Le mot de passe PostgreSQL vient de `PRIVONYM_REPOSITORY_PASSWORD`. Le mot de passe
Oracle vient de la configuration privée source ou de `PRIVONYM_SOURCE_PASSWORD`.
Ne pas passer de mots de passe sur la ligne de commande.

Sous Linux (remplacer `:` par `;` dans le classpath sous Windows) :

```sh
java -cp 'target/*:lib/*' com.privonym.install.OracleToPostgresqlRepositoryMigration \
  snapshot /chemin/source-prive.properties /chemin/test-postgresql.properties .
```

Le mode `snapshot` impose une base jetable nommée `privonym_migration_test_*`.
Il permet de qualifier la copie sans basculer l'installation. Pour la copie finale,
**arrêter tous les serveurs et runners Privonym utilisant Oracle**, puis remplacer
`snapshot` par `migrate-stopped`. Ce nom de mode est une confirmation opérateur ;
il ne détecte pas les runners présents sur d'autres machines. Garder les anciens
runners arrêtés après la bascule pour éviter deux référentiels divergents.

Configurer ensuite l'installation vers PostgreSQL, conserver sa clé de chiffrement,
et démarrer les nouveaux binaires. Vérifier les utilisateurs, connexions, règles,
journaux et un job synthétique avant les traitements réels. Le SQL généré et les
fonctions à installer dans les bases cibles ne changent pas de dialecte.

En cas d'échec avant la bascule, l'outil annule la destination et Oracle reste
inchangé. Après une bascule, revenir aux anciens binaires/configurations tant
qu'aucun nouveau traitement n'a été lancé ; après de nouvelles écritures, prévoir
une réconciliation avant de réutiliser Oracle.

## Vérification et fabrication des archives

```powershell
.\build.ps1 -JdkHome 'C:\Program Files\Java\jdk-17'
.\packaging\Build-PrivonymPortablePackages.ps1
```

Les sorties sont `target/Privonym-Windows-3.0.0-preview.zip` et
`target/Privonym-Linux-3.0.0-preview.tar.gz`. `SHA256SUMS` liste le contenu fourni.
Ni configuration locale, ni clé, ni données du référentiel ne sont incluses.

Les tests PostgreSQL réels sont optionnels :
`PostgresqlRepositoryIntegrationCheck` refuse toute base autre qu'une base de test
explicitement nommée. Ils couvrent l'authentification, la découverte, l'import,
la publication, un job de 2 500 lignes à deux workers, une erreur provoquée,
la reprise sans rejouer les chunks validés et un verrou de commit concurrent.
Les contrôles hors base vérifient aussi la conservation des littéraux SQL importés.

La fabrication d'une archive Linux sur Windows ne remplace pas une exécution sur
Linux. Une qualification sur Linux reste nécessaire avant de déclarer cette
distribution validée sur ce système.
