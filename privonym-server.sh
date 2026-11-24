#!/bin/sh
# Java 17 HTTP server, Linux. Configuration and reports stay in the installation directory.
set -u
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 2
if [ -n "${JAVA_HOME:-}" ]; then java_command=$JAVA_HOME/bin/java; else java_command=java; fi
if [ -d "$root/app" ]; then app=$root/app; else app=$root/target; fi
export PRIVONYM_CONFIG=${PRIVONYM_CONFIG:-"$root/config/privonym.properties"}
if [ ! -f "$PRIVONYM_CONFIG" ]; then
    echo "Configuration missing: copy config/privonym-postgresql.properties.example to config/privonym.properties." >&2
    exit 2
fi
run=$(mktemp -d "${TMPDIR:-/tmp}/privonym-server.XXXXXXXX") || exit 2
cleanup() { [ -n "$run" ] && [ -d "$run" ] && rm -rf -- "$run"; }
trap cleanup EXIT
for jar in privonym-domain privonym-application privonym-http-adapter privonym-oracle-adapter; do
    if ! cp "$app/$jar.jar" "$run/"; then exit 2; fi
done
mkdir "$run/lib" || exit 2
cp "$root"/lib/*.jar "$run/lib/" || exit 2
cd "$root" || exit 2
"$java_command" "-Dprivonym.secret.key.file=${PRIVONYM_SECRET_KEY_FILE:-$root/config/privonym-secret.key}" \
    -classpath "$run/*:$run/lib/*" com.privonym.server.PrivonymServerLauncher &
server_pid=$!
stop_server() { kill -TERM "$server_pid" 2>/dev/null || :; }
trap stop_server HUP INT TERM
status=0
wait "$server_pid" || status=$?
# A signal may interrupt wait before the child has stopped.
if kill -0 "$server_pid" 2>/dev/null; then wait "$server_pid" || status=$?; fi
exit "$status"
