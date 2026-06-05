package com.nemonicmail.storage;

import com.nemonicmail.model.AuditEntry;
import com.nemonicmail.model.Letter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StorageProvider {

    void init() throws Exception;

    void close();

    void insertLetter(Letter letter);

    /** Inbox completo do jogador (max 50), ordenado por data desc. */
    List<Letter> getInbox(UUID playerUUID);

    /** Inbox sem limite de quantidade para uso administrativo. */
    List<Letter> getInboxAdmin(UUID playerUUID);

    /** Cartas diretas ainda não entregues cujo deliverAt <= currentTime. */
    List<Letter> getPendingDirectLetters(UUID playerUUID, long currentTime);

    /** Broadcasts que o jogador ainda não recebeu. */
    List<Letter> getPendingBroadcasts(UUID playerUUID);

    void markDelivered(UUID letterId);

    void markRead(UUID letterId);

    /** Registra que um broadcast foi visto por este jogador. */
    void markBroadcastSeen(UUID letterId, UUID playerUUID);

    /** Conta cartas diretas não lidas. */
    int countUnread(UUID playerUUID);

    /** Conta broadcasts não vistos. */
    int countUnreadBroadcasts(UUID playerUUID);

    /** Remove cartas antigas e entradas de auditoria conforme política de limpeza. */
    void cleanup(long directOlderThan, long broadcastOlderThan, long auditOlderThan);

    /**
     * Retorna todas as cartas diretas não entregues cujo deliverAt <= currentTime,
     * independente do destinatário.
     */
    List<Letter> getPendingLettersReadyToDeliver(long currentTime);

    /**
     * Marca a carta como entregue atomicamente.
     * @return true se marcada (estava delivered=0), false se já estava entregue.
     */
    boolean tryMarkDelivered(UUID letterId);

    // --- Admin ---

    /** Busca uma carta pelo ID. Inclui remetente real mesmo para cartas anônimas. */
    Optional<Letter> getLetterById(UUID letterId);

    /** Soft-revoke: define expires_at = 1 para que a carta desapareça imediatamente. */
    void revokeLetterById(UUID letterId);

    // --- Audit log ---

    void logAudit(String eventType, String actorUuid, String actorName,
                  String targetId, String detail);

    List<AuditEntry> getRecentAuditLog(int limit);
}
