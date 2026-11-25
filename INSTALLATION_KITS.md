# Kits d'installation Privonym Windows et Linux

Deux distributions contiennent le même moteur Java et les mêmes fonctions.
La connexion à Privonym reste celle du compte applicatif local ; aucun SSO
n'est activé. Le référentiel utilise PostgreSQL. Oracle, SQL Server et PostgreSQL
restent disponibles comme bases cibles.

## Prérequis

- Java 17 ou supérieur, à installer séparément. Les kits ne contiennent pas de JRE.
- PostgreSQL 17, local ou distant, et une base dédiée à Privonym.
- Pour un référentiel existant : sa configuration privée et sa clé
  `privonym-secret.key`. Ne jamais remplacer cette clé par une nouvelle.

Les kits ne contiennent aucune configuration de cette machine, aucun mot de passe,
aucune clé de chiffrement et aucune copie des données du référentiel.

## Windows 64 bits

Extraire `Privonym-Windows-3.0.0-preview.1.zip`, puis ouvrir PowerShell dans
le répertoire extrait :

```powershell
.\Install-Privonym.ps1 -Destination 'C:\Applications\Privonym'
```

Pour utiliser un référentiel déjà configuré :

```powershell
.\Install-Privonym.ps1 -Destination 'C:\Applications\Privonym' `
  -RepositoryConfig 'D:\configuration-privee\privonym.properties' `
  -EncryptionKey 'D:\configuration-privee\privonym-secret.key'
```

Les fichiers de configuration sont protégés par les ACL Windows. La destination
doit être vide : l'installateur refuse de remplacer une installation existante.
Pour préparer une installation parallèle, choisir un autre répertoire et un autre
port avec `-Port 8082`. L'installation ne démarre pas de serveur.

L'option `-RegisterService` exige un PowerShell administrateur. Elle utilise le
composant WinSW fourni et vérifié, sous le compte `LocalService` :

```powershell
.\Install-Privonym.ps1 -Destination 'C:\Applications\Privonym' `
  -RegisterService -ServiceName Privonym -JavaHome 'C:\Program Files\Java\jdk-17'
```

Un service existant n'est jamais arrêté ni remplacé. Le nouveau service est
enregistré en démarrage manuel et reste arrêté. Après configuration et vérification
du port, le démarrer avec `Start-Service Privonym`. Sans service, lancer
`privonym-server.cmd` depuis le répertoire installé, avec Java 17 dans `PATH`.

## Linux

```sh
tar -xzf Privonym-Linux-3.0.0-preview.1.tar.gz
cd Privonym-3.0.0-preview.1
sh install.sh --prefix "$HOME/applications/privonym"
```

Avec un référentiel existant :

```sh
sh install.sh --prefix "$HOME/applications/privonym" \
  --config /chemin/prive/privonym.properties \
  --key /chemin/prive/privonym-secret.key
```

Les fichiers privés sont accessibles uniquement au propriétaire (`600`, répertoire
`700`). La destination doit être vide. `--port 8082` permet de préparer un autre
port sans toucher à un serveur déjà lancé.

Pour enregistrer une unité systemd, un administrateur doit d'abord créer un compte
système non-root, par exemple `privonym`, puis exécuter :

```sh
sudo sh install.sh --prefix /opt/privonym --systemd --user privonym
```

L'installateur refuse toute unité Privonym déjà présente. Il ne démarre ni n'active
le service. Après configuration : `sudo systemctl enable --now privonym`.
Sans systemd : `sh /chemin/installation/privonym-server.sh`.

## Référentiel neuf ou existant

Pour une installation neuve, créer une base PostgreSQL vide et un rôle dédié.
Dans `psql`, avec un compte administrateur, on peut utiliser :

```sql
CREATE ROLE privonym LOGIN;
\password privonym
CREATE DATABASE privonym OWNER privonym ENCODING 'UTF8' TEMPLATE template0;
```

Adapter `config/privonym.properties`, puis initialiser une seule fois avec
`privonym-init-postgresql.cmd` ou `sh privonym-init-postgresql.sh`. Le mot de passe
est demandé dans le terminal. Après démarrage, ouvrir `/login` pour créer le premier
administrateur applicatif.

Pour un référentiel existant, **ne pas réinitialiser la base**. Reprendre son URL,
sa configuration et sa clé originale. Après démarrage, les mêmes comptes Privonym
et les mêmes droits restent disponibles. Deux serveurs actifs partagent alors le
même référentiel ; ne pas lancer un même job depuis deux ordinateurs.

## Jobs lancés par scripts Windows ou Unix

Chaque job créé pour une base possède son identifiant. Les deux kits incluent
les lanceurs non interactifs existants :

```bat
privonym-job.cmd 123 "C:\configuration-privee\privonym-job.properties"
```

```sh
sh /opt/privonym/privonym-job.sh 123 /chemin/prive/privonym-job.properties
```

Le deuxième argument peut être omis : le lanceur utilise alors sa configuration
locale ou `PRIVONYM_JOB_CONFIG`. Adapter l'exemple
`config/privonym-job-postgresql.properties.example`, notamment l'URL, le rôle et
`repository.password`, dans un fichier privé protégé. Le fichier de clé doit aussi
être accessible au compte exécutant le job. Aucun navigateur n'est nécessaire.

Le processus attend la fin du job. Code de sortie `0` pour un succès, et un code
non nul pour un échec (`1` pour un échec d'exécution ; `2` pour un usage invalide
détecté par le runner). Les traitements ne se lancent pas pendant
l'installation. Les identifiants des jobs et leurs configurations ne sont pas
intégrés aux kits partagés.

## Vérification et fabrication

Chaque archive possède un fichier externe `.sha256` et un manifeste `SHA256SUMS`
des fichiers inclus. Sur Linux, après extraction : `sha256sum -c SHA256SUMS`.

```powershell
.\build.ps1 -JdkHome 'C:\Program Files\Java\jdk-17'
.\packaging\Build-PrivonymPortablePackages.ps1
```

Les archives sont produites dans `target`. Elles n'installent ni Java ni PostgreSQL,
et leur fabrication ne modifie pas le service ni les exécutions de cette machine.
La première fabrication télécharge WinSW depuis sa publication officielle et
vérifie son SHA-256 avant inclusion. Les kits déjà fabriqués contiennent ce
composant et n'ont pas besoin de le télécharger pendant l'installation.
