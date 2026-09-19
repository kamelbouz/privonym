#!/bin/sh
# Portable Privonym Job runner for Linux and AIX (POSIX shell, Java 17).

if [ "$#" -lt 1 ] || [ -z "$1" ]; then
    echo "Usage: privonym-job.sh JOB_ID [CONFIG_FILE]" >&2
    exit 2
fi

job_id=$1
script_dir=$(CDPATH= cd "$(dirname "$0")" 2>/dev/null && pwd -P)
if [ -z "$script_dir" ]; then
    echo "Unable to resolve the Privonym installation directory." >&2
    exit 2
fi

if [ -f "$script_dir/app/privonym-oracle-adapter.jar" ]; then
    app_dir=$script_dir/app
else
    app_dir=$script_dir/target
fi
if [ ! -f "$app_dir/privonym-oracle-adapter.jar" ]; then
    echo "Privonym is not built or installed under $script_dir." >&2
    exit 2
fi

if [ "$#" -ge 2 ] && [ -n "$2" ]; then
    config_file=$2
elif [ -n "${PRIVONYM_JOB_CONFIG:-}" ]; then
    config_file=$PRIVONYM_JOB_CONFIG
else
    config_file=$script_dir/config/privonym-job.properties
fi

if [ -n "${PRIVONYM_SECRET_KEY_FILE:-}" ]; then
    secret_key_file=$PRIVONYM_SECRET_KEY_FILE
else
    secret_key_file=$script_dir/config/privonym-secret.key
fi
if [ ! -f "$secret_key_file" ]; then
    echo "Privonym credential key was not found: $secret_key_file" >&2
    exit 2
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    java_command=$JAVA_HOME/bin/java
else
    java_command=java
fi

exec "$java_command" "-Dprivonym.secret.key.file=$secret_key_file" \
    -classpath "$app_dir/*:$script_dir/lib/*" \
    com.privonym.cli.PrivonymConfiguredJobRunner "$job_id" "$config_file"
