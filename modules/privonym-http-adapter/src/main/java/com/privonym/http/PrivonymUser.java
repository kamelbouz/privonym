package com.privonym.http;

import java.util.Set;

/** Authenticated Privonym application identity, independent from database accounts. */
public record PrivonymUser(long id, String username, String displayName,
        boolean administrator, boolean enabled, Set<String> privileges) {
    public PrivonymUser {
        username = username == null ? "" : username;
        displayName = displayName == null ? "" : displayName;
        privileges = Set.copyOf(privileges);
    }

    public boolean has(String privilege) {
        return administrator || privileges.contains(privilege);
    }
}
