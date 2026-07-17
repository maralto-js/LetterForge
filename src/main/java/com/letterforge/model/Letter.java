package com.letterforge.model;

import java.util.List;
import java.util.UUID;

public record Letter(
        UUID id,
        UUID senderUUID,
        String senderName,
        UUID recipientUUID,   // null para broadcasts
        String recipientName, // "TODOS" para broadcasts
        List<String> pages,
        long sentAt,
        long deliverAt,
        boolean read,
        boolean delivered,
        LetterType type,
        LetterPriority priority,
        long expiresAt,       // epoch ms; 0 = sem expiracao
        int edition           // numero da edicao (so JORNAL; 0 nos demais)
) {
    public boolean isBroadcast() {
        return type == LetterType.BROADCAST;
    }

    public String displaySender() {
        return type == LetterType.ANONYMOUS ? "???" : senderName;
    }

    public Letter withRead(boolean newRead) {
        return new Letter(id, senderUUID, senderName, recipientUUID, recipientName,
                pages, sentAt, deliverAt, newRead, delivered, type, priority, expiresAt, edition);
    }

    public Letter withDelivered(boolean newDelivered) {
        return new Letter(id, senderUUID, senderName, recipientUUID, recipientName,
                pages, sentAt, deliverAt, read, newDelivered, type, priority, expiresAt, edition);
    }

    public Letter withEdition(int newEdition) {
        return new Letter(id, senderUUID, senderName, recipientUUID, recipientName,
                pages, sentAt, deliverAt, read, delivered, type, priority, expiresAt, newEdition);
    }
}
