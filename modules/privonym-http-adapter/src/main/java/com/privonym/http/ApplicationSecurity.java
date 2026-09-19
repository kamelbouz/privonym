package com.privonym.http;

import java.util.List;
import java.util.Set;

/** Application-user authentication and authorization boundary. */
public interface ApplicationSecurity extends AutoCloseable {
    String USER_MANAGE = "USER_MANAGE";
    String ENVIRONMENT_MANAGE = "ENVIRONMENT_MANAGE";
    String CONNECTION_MANAGE = "CONNECTION_MANAGE";
    String DISCOVERY_RUN = "DISCOVERY_RUN";
    String RULESET_CREATE = "RULESET_CREATE";
    String RULESET_MODIFY_OWN = "RULESET_MODIFY_OWN";
    String RULESET_PUBLISH_OWN = "RULESET_PUBLISH_OWN";
    String RULESET_DELETE_OWN = "RULESET_DELETE_OWN";
    String JOB_MANAGE = "JOB_MANAGE";
    String JOB_EXECUTE = "JOB_EXECUTE";
    String ALGORITHM_MANAGE = "ALGORITHM_MANAGE";

    Set<String> AVAILABLE_PRIVILEGES = Set.of(USER_MANAGE, ENVIRONMENT_MANAGE,
            CONNECTION_MANAGE, DISCOVERY_RUN, RULESET_CREATE, RULESET_MODIFY_OWN,
            RULESET_PUBLISH_OWN, RULESET_DELETE_OWN, JOB_MANAGE, JOB_EXECUTE,
            ALGORITHM_MANAGE);

    boolean isInitialized();

    PrivonymUser createInitialAdministrator(String username, String displayName, char[] password);

    PrivonymUser authenticate(String username, char[] password);

    List<PrivonymUser> listUsers();

    PrivonymUser createUser(String actor, String username, String displayName,
            char[] password, boolean administrator, Set<String> privileges);

    PrivonymUser updateUserPrivileges(String actor, long userId,
            boolean administrator, Set<String> privileges);

    void setUserEnabled(String actor, long userId, boolean enabled);

    boolean ownsRuleset(long userId, long rulesetId);

    boolean ownsRulesetVersion(long userId, long rulesetVersionId);

    void assignRulesetOwner(long rulesetId, long userId);

    void assignRulesetVersionOwner(long rulesetVersionId, long userId);

    void record(String username, String action, String objectType,
            String objectReference, String outcome);

    @Override
    void close();
}
