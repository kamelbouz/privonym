package com.privonym.application.port.in;

import com.privonym.application.service.DiscoveryConnection;
import com.privonym.application.service.DiscoveryCreationResult;
import com.privonym.application.service.DiscoveryScan;
import com.privonym.application.service.DiscoverySelection;
import com.privonym.application.service.EnvironmentOption;
import com.privonym.application.service.ConnectionCreationResult;
import com.privonym.application.service.ConnectionUpdateCommand;
import java.util.List;

public interface DiscoveryUseCase {
    List<DiscoveryConnection> listConnections();
    List<EnvironmentOption> listEnvironmentOptions();
    ConnectionCreationResult createConnection(long environmentId, String name, String technology,
            String schemaName, String host, int port, String service, String database, String user,
            String secretProvider, String secretReference, char[] targetPassword);
    void deleteConnection(long dataSourceId);
    void updateConnection(long dataSourceId, ConnectionUpdateCommand command);
    DiscoveryScan scan(long dataSourceId);
    default DiscoveryScan analyzeContent(long dataSourceId) { return scan(dataSourceId); }
    default DiscoveryCreationResult createDraftVersion(long rulesetId,List<DiscoverySelection> selections) {
        throw new UnsupportedOperationException("Discovery version creation is unavailable");
    }
    DiscoveryCreationResult createRuleset(long dataSourceId, String code, String name,
            List<DiscoverySelection> selections);
    DiscoveryCreationResult createEmptyRuleset(long dataSourceId, String code, String name);
}
