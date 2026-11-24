#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
if [ -n "${JAVA_HOME:-}" ]; then java_command=$JAVA_HOME/bin/java; else java_command=java; fi
exec "$java_command" -classpath "$root/target/*:$root/lib/*" \
    com.privonym.install.PostgresqlRepositoryInstaller \
    "${PRIVONYM_CONFIG:-$root/config/privonym.properties}" "$root"
