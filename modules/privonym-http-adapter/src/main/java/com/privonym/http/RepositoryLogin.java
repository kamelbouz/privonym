package com.privonym.http;

/** Runtime authentication boundary for the Privonym repository. */
public interface RepositoryLogin {
    boolean isConnected();

    void login(char[] password);

    void logout();
}
