package com.letterforge.model;

public record AuditEntry(
        long   eventTime,
        String eventType,
        String actorName,
        String targetId,
        String detail
) {}
