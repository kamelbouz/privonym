package com.privonym.application.port.out;

import com.privonym.application.service.ApplicationAuditEvent;

public interface AuditEventSink {
    void append(ApplicationAuditEvent event);
}
