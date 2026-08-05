package com.nexusagent.audit.api;

public interface AuditLogWriter {

    void write(AuditLogCommand command);
}
