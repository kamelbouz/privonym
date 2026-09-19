package com.privonym.application.service;

/** Complete editable definition of an existing target connection. */
public record ConnectionUpdateCommand(long environmentId, String name, String technology,
        String schemaName, String host, int port, String service, String database,
        String username, String secretProvider, String secretReference, char[] password) {
    public ConnectionUpdateCommand {
        password = password == null ? new char[0] : password.clone();
    }

    @Override
    public char[] password() {
        return password.clone();
    }
}
