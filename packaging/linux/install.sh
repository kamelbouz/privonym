#!/bin/sh
set -eu
umask 077
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
prefix=${HOME}/.local/opt/privonym
config=
key=
port=8080
systemd=no
service_user=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --prefix|--config|--key|--port|--user)
            [ "$#" -ge 2 ] || { echo "Missing argument for $1" >&2; exit 2; }
            case "$1" in --prefix) prefix=$2;; --config) config=$2;; --key) key=$2;; --port) port=$2;; --user) service_user=$2;; esac
            shift 2;;
        --systemd) systemd=yes; shift;;
        *) echo "Usage: sh install.sh [--prefix DIR] [--config FILE --key FILE] [--port N] [--systemd --user EXISTING_USER]" >&2; exit 2;;
    esac
done
case "$port" in ''|*[!0-9]*) echo 'Invalid port' >&2; exit 2;; esac
[ "$port" -ge 1 ] && [ "$port" -le 65535 ] || { echo 'Invalid port' >&2; exit 2; }
case "$prefix" in /*) ;; *) echo 'An absolute installation directory is required' >&2; exit 2;; esac
[ "$prefix" != / ] && [ ! -L "$prefix" ] || { echo 'Unsafe installation directory' >&2; exit 2; }
if [ -e "$prefix" ] && [ -n "$(ls -A "$prefix")" ]; then
    echo 'Destination must be empty; no installation or running job will be replaced.' >&2; exit 2
fi
if [ -n "$config" ]; then
    [ -f "$config" ] || { echo 'Configuration not found' >&2; exit 2; }
    key=${key:-"$(dirname -- "$config")/privonym-secret.key"}
    [ -f "$key" ] || { echo 'The original encryption key is required for an existing repository' >&2; exit 2; }
fi
if [ "$systemd" = yes ]; then
    [ "$(id -u)" = 0 ] || { echo 'Root is required to register systemd' >&2; exit 2; }
    case "$service_user" in ''|*[!A-Za-z0-9_-]*) echo 'Specify an existing service user' >&2; exit 2;; esac
    [ "$(id -u "$service_user")" != 0 ] || { echo 'Use a non-root service account' >&2; exit 2; }
    case "$prefix" in *[!A-Za-z0-9_./-]*) echo 'For systemd use a path without spaces or shell metacharacters' >&2; exit 2;; esac
    [ ! -e /etc/systemd/system/privonym.service ] || { echo 'Existing systemd unit will not be replaced' >&2; exit 2; }
    [ "$(systemctl show -p LoadState --value privonym.service)" = not-found ] || { echo 'An existing service will not be replaced' >&2; exit 2; }
fi
mkdir -p "$prefix"
for directory in target lib database; do cp -R "$source_dir/$directory" "$prefix/"; done
mkdir -p "$prefix/config" "$prefix/jobs" "$prefix/reports" "$prefix/logs"
cp "$source_dir"/config/*.example "$prefix/config/"
for file in privonym-server.sh privonym-job.sh privonym-init-postgresql.sh INSTALLATION_KITS.md REFERENTIEL_POSTGRESQL.md; do
    cp "$source_dir/$file" "$prefix/"
done
if [ -n "$config" ]; then
    cp "$config" "$prefix/config/privonym.properties"
    cp "$config" "$prefix/config/privonym-job.properties"
    cp "$key" "$prefix/config/privonym-secret.key"
else
    cp "$prefix/config/privonym-postgresql.properties.example" "$prefix/config/privonym.properties"
    cp "$prefix/config/privonym-job-postgresql.properties.example" "$prefix/config/privonym-job.properties"
    if [ -n "$key" ]; then cp "$key" "$prefix/config/privonym-secret.key"; fi
    # For a new repository the launcher creates its key on first application connection.
fi
printf '\nserver.port=%s\n' "$port" >> "$prefix/config/privonym.properties"
chmod 700 "$prefix/config"
chmod 600 "$prefix"/config/*
chmod +x "$prefix"/*.sh
if [ "$systemd" = yes ]; then
    chown -R "$service_user:" "$prefix"
    cat > /etc/systemd/system/privonym.service <<EOF
[Unit]
Description=Privonym server
After=network.target
[Service]
Type=simple
User=$service_user
WorkingDirectory=$prefix
Environment=PRIVONYM_CONFIG=$prefix/config/privonym.properties
ExecStart=/bin/sh $prefix/privonym-server.sh
KillMode=control-group
TimeoutStopSec=90
Restart=on-failure
[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    echo 'systemd unit registered but not enabled or started. No existing service was stopped.'
fi
echo "Privonym installed in $prefix. Configure PostgreSQL, then run: sh $prefix/privonym-server.sh"
