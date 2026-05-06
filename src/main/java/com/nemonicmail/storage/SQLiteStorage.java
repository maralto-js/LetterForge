package com.nemonicmail.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nemonicmail.image.MapImageEntry;
import com.nemonicmail.model.AuditEntry;
import com.nemonicmail.model.Letter;
import com.nemonicmail.model.LetterPriority;
import com.nemonicmail.model.LetterType;

import java.io.File;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

public class SQLiteStorage implements StorageProvider {

    private static final Type LIST_STRING_TYPE = new TypeToken<List<String>>() {}.getType();

    private final File dataFolder;
    private final Logger logger;
    private final Gson gson = new Gson();
    private Connection connection;

    public SQLiteStorage(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    @Override
    public synchronized void init() throws Exception {
        Class.forName("com.nemonicmail.libs.sqlite.JDBC");

        File dbFile = new File(dataFolder, "letters.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA cache_size=10000");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA temp_store=MEMORY");
            s.execute("PRAGMA mmap_size=67108864");      // 64 MB memory-mapped I/O
            s.execute("PRAGMA wal_autocheckpoint=1000"); // checkpoint every 1000 WAL pages
        }

        createTables();
        logger.info("[NemonicMail] Banco de dados inicializado.");
    }

    private void createTables() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS letters (
                        id             TEXT PRIMARY KEY,
                        sender_uuid    TEXT NOT NULL,
                        sender_name    TEXT NOT NULL,
                        recipient_uuid TEXT,
                        recipient_name TEXT NOT NULL,
                        pages          TEXT NOT NULL,
                        sent_at        INTEGER NOT NULL,
                        deliver_at     INTEGER NOT NULL,
                        read           INTEGER NOT NULL DEFAULT 0,
                        delivered      INTEGER NOT NULL DEFAULT 0,
                        type           TEXT NOT NULL DEFAULT 'DIRECT',
                        priority       TEXT NOT NULL DEFAULT 'NORMAL',
                        expires_at     INTEGER NOT NULL DEFAULT 0
                    )""");

            s.execute("""
                    CREATE TABLE IF NOT EXISTS broadcast_seen (
                        letter_id   TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        PRIMARY KEY (letter_id, player_uuid)
                    )""");

            try {
                s.execute("ALTER TABLE letters ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {}

            s.execute("CREATE INDEX IF NOT EXISTS idx_recipient ON letters(recipient_uuid, delivered, deliver_at)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_type_delivered ON letters(type, delivered)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_cleanup ON letters(type, read, sent_at)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_bc_seen ON broadcast_seen(player_uuid)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_expires ON letters(expires_at)");

            s.execute("""
                    CREATE TABLE IF NOT EXISTS letter_map_images (
                        letter_id   TEXT    NOT NULL,
                        tile_index  INTEGER NOT NULL DEFAULT 0,
                        map_id      INTEGER NOT NULL UNIQUE,
                        pixels      BLOB    NOT NULL,
                        grid_w      INTEGER NOT NULL DEFAULT 1,
                        grid_h      INTEGER NOT NULL DEFAULT 1,
                        created_at  INTEGER NOT NULL,
                        PRIMARY KEY (letter_id, tile_index),
                        FOREIGN KEY (letter_id) REFERENCES letters(id) ON DELETE CASCADE
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_map_images ON letter_map_images(letter_id)");

            s.execute("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_time  INTEGER NOT NULL,
                        event_type  TEXT    NOT NULL,
                        actor_uuid  TEXT,
                        actor_name  TEXT,
                        target_id   TEXT,
                        detail      TEXT
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(event_time DESC)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_audit_type ON audit_log(event_type)");
        }
    }

    // -----------------------------------------------------------------------
    // Letters — core CRUD
    // -----------------------------------------------------------------------

    @Override
    public synchronized void insertLetter(Letter letter) {
        String sql = """
                INSERT INTO letters(id, sender_uuid, sender_name, recipient_uuid, recipient_name,
                                    pages, sent_at, deliver_at, read, delivered, type, priority, expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, letter.id().toString());
            ps.setString(2, letter.senderUUID().toString());
            ps.setString(3, letter.senderName());
            ps.setString(4, letter.recipientUUID() != null ? letter.recipientUUID().toString() : null);
            ps.setString(5, letter.recipientName());
            ps.setString(6, gson.toJson(letter.pages()));
            ps.setLong(7, letter.sentAt());
            ps.setLong(8, letter.deliverAt());
            ps.setInt(9, letter.read() ? 1 : 0);
            ps.setInt(10, letter.delivered() ? 1 : 0);
            ps.setString(11, letter.type().name());
            ps.setString(12, letter.priority().name());
            ps.setLong(13, letter.expiresAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao inserir carta: " + e.getMessage());
        }
    }

    @Override
    public synchronized List<Letter> getInbox(UUID playerUUID) {
        long now = System.currentTimeMillis();
        String sql = """
                SELECT * FROM letters
                WHERE recipient_uuid = ?
                  AND (expires_at = 0 OR expires_at > ?)
                ORDER BY sent_at DESC LIMIT 50""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUUID.toString());
            ps.setLong(2, now);
            return mapAll(ps.executeQuery());
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao carregar inbox: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public synchronized List<Letter> getInboxAdmin(UUID playerUUID) {
        String sql = """
                SELECT * FROM letters
                WHERE recipient_uuid = ?
                ORDER BY sent_at DESC LIMIT 100""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUUID.toString());
            return mapAll(ps.executeQuery());
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao carregar inbox (admin): " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public synchronized List<Letter> getPendingDirectLetters(UUID playerUUID, long currentTime) {
        String sql = """
                SELECT * FROM letters
                WHERE recipient_uuid = ?
                  AND delivered = 0
                  AND deliver_at <= ?
                  AND type != 'BROADCAST'""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUUID.toString());
            ps.setLong(2, currentTime);
            return mapAll(ps.executeQuery());
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao buscar pendentes: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public synchronized List<Letter> getPendingBroadcasts(UUID playerUUID) {
        long now = System.currentTimeMillis();
        String sql = """
                SELECT * FROM letters
                WHERE type = 'BROADCAST'
                  AND (expires_at = 0 OR expires_at > ?)
                  AND id NOT IN (
                      SELECT letter_id FROM broadcast_seen WHERE player_uuid = ?
                  )
                ORDER BY sent_at DESC""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setString(2, playerUUID.toString());
            return mapAll(ps.executeQuery());
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao buscar broadcasts: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public synchronized void markDelivered(UUID letterId) {
        execute("UPDATE letters SET delivered = 1 WHERE id = ?", letterId.toString());
    }

    @Override
    public synchronized void markRead(UUID letterId) {
        execute("UPDATE letters SET read = 1 WHERE id = ?", letterId.toString());
    }

    @Override
    public synchronized void markBroadcastSeen(UUID letterId, UUID playerUUID) {
        String sql = "INSERT OR IGNORE INTO broadcast_seen(letter_id, player_uuid) VALUES (?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, letterId.toString());
            ps.setString(2, playerUUID.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao marcar broadcast visto: " + e.getMessage());
        }
    }

    @Override
    public synchronized int countUnread(UUID playerUUID) {
        String sql = "SELECT COUNT(*) FROM letters WHERE recipient_uuid = ? AND read = 0 AND type != 'BROADCAST'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUUID.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public synchronized int countUnreadBroadcasts(UUID playerUUID) {
        String sql = """
                SELECT COUNT(*) FROM letters
                WHERE type = 'BROADCAST'
                  AND id NOT IN (SELECT letter_id FROM broadcast_seen WHERE player_uuid = ?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUUID.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public synchronized List<Letter> getPendingLettersReadyToDeliver(long currentTime) {
        String sql = """
                SELECT * FROM letters
                WHERE delivered = 0
                  AND deliver_at <= ?
                  AND type != 'BROADCAST'
                  AND recipient_uuid IS NOT NULL""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, currentTime);
            return mapAll(ps.executeQuery());
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao buscar cartas prontas: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public synchronized boolean tryMarkDelivered(UUID letterId) {
        String sql = "UPDATE letters SET delivered = 1 WHERE id = ? AND delivered = 0";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, letterId.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao marcar como entregue: " + e.getMessage());
            return false;
        }
    }

    @Override
    public synchronized void cleanup(long directOlderThan, long broadcastOlderThan) {
        long now = System.currentTimeMillis();
        try (Statement s = connection.createStatement()) {
            s.execute("DELETE FROM letters WHERE expires_at > 0 AND expires_at < " + now);
            s.execute("DELETE FROM letters WHERE type != 'BROADCAST' AND read = 1 AND sent_at < " + directOlderThan);
            s.execute("DELETE FROM letters WHERE type = 'BROADCAST' AND sent_at < " + broadcastOlderThan);
            s.execute("DELETE FROM broadcast_seen WHERE letter_id NOT IN (SELECT id FROM letters)");
            // Prune audit log older than 90 days
            long auditCutoff = now - 90L * 24 * 3600 * 1000;
            s.execute("DELETE FROM audit_log WHERE event_time < " + auditCutoff);
        } catch (SQLException e) {
            logger.warning("[NemonicMail] Erro durante cleanup: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Admin
    // -----------------------------------------------------------------------

    @Override
    public synchronized Optional<Letter> getLetterById(UUID letterId) {
        String sql = "SELECT * FROM letters WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, letterId.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao buscar carta por ID: " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public synchronized void revokeLetterById(UUID letterId) {
        // expires_at = 1 → already expired for everyone
        String sql = "UPDATE letters SET expires_at = 1 WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, letterId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao revogar carta: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Audit log
    // -----------------------------------------------------------------------

    @Override
    public synchronized void logAudit(String eventType, String actorUuid, String actorName,
                                      String targetId, String detail) {
        String sql = """
                INSERT INTO audit_log(event_time, event_type, actor_uuid, actor_name, target_id, detail)
                VALUES (?,?,?,?,?,?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, eventType);
            ps.setString(3, actorUuid);
            ps.setString(4, actorName);
            ps.setString(5, targetId);
            ps.setString(6, detail);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[NemonicMail] Erro ao gravar audit_log: " + e.getMessage());
        }
    }

    @Override
    public synchronized List<AuditEntry> getRecentAuditLog(int limit) {
        String sql = "SELECT event_time, event_type, actor_name, target_id, detail " +
                     "FROM audit_log ORDER BY event_time DESC LIMIT ?";
        List<AuditEntry> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new AuditEntry(
                        rs.getLong("event_time"),
                        rs.getString("event_type"),
                        rs.getString("actor_name"),
                        rs.getString("target_id"),
                        rs.getString("detail")));
            }
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao buscar audit_log: " + e.getMessage());
        }
        return list;
    }

    // -----------------------------------------------------------------------
    // Map image attachments
    // -----------------------------------------------------------------------

    public synchronized void insertMapImage(UUID letterId, int tileIndex, int mapId,
                                            byte[] pixels, int gridW, int gridH) {
        String sql = """
                INSERT OR IGNORE INTO letter_map_images
                    (letter_id, tile_index, map_id, pixels, grid_w, grid_h, created_at)
                VALUES (?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, letterId.toString());
            ps.setInt(2, tileIndex);
            ps.setInt(3, mapId);
            ps.setBytes(4, pixels);
            ps.setInt(5, gridW);
            ps.setInt(6, gridH);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao salvar tile: " + e.getMessage());
        }
    }

    /** Batch insert — single transaction for all tiles of a letter. */
    public synchronized void insertMapImageBatch(UUID letterId, int[] mapIds,
                                                  byte[][] tilesData, int gridW, int gridH) {
        String sql = """
                INSERT OR IGNORE INTO letter_map_images
                    (letter_id, tile_index, map_id, pixels, grid_w, grid_h, created_at)
                VALUES (?,?,?,?,?,?,?)""";
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                long now = System.currentTimeMillis();
                for (int i = 0; i < mapIds.length; i++) {
                    ps.setString(1, letterId.toString());
                    ps.setInt(2, i);
                    ps.setInt(3, mapIds[i]);
                    ps.setBytes(4, tilesData[i]);
                    ps.setInt(5, gridW);
                    ps.setInt(6, gridH);
                    ps.setLong(7, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            logger.severe("[NemonicMail] Erro ao salvar tiles em batch: " + e.getMessage());
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    public synchronized List<MapImageEntry> getMapImagesForLetter(UUID letterId) {
        String sql = """
                SELECT tile_index, map_id, pixels, grid_w, grid_h
                FROM letter_map_images
                WHERE letter_id = ?
                ORDER BY tile_index""";
        List<MapImageEntry> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, letterId.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new MapImageEntry(
                        letterId,
                        rs.getInt("tile_index"),
                        rs.getInt("map_id"),
                        rs.getBytes("pixels"),
                        rs.getInt("grid_w"),
                        rs.getInt("grid_h")));
            }
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao buscar tiles: " + e.getMessage());
        }
        return list;
    }

    public synchronized List<MapImageEntry> getAllMapImages() {
        String sql = "SELECT letter_id, tile_index, map_id, pixels, grid_w, grid_h FROM letter_map_images";
        List<MapImageEntry> list = new ArrayList<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new MapImageEntry(
                        UUID.fromString(rs.getString("letter_id")),
                        rs.getInt("tile_index"),
                        rs.getInt("map_id"),
                        rs.getBytes("pixels"),
                        rs.getInt("grid_w"),
                        rs.getInt("grid_h")));
            }
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro ao carregar tiles: " + e.getMessage());
        }
        return list;
    }

    // -----------------------------------------------------------------------
    // Connection lifecycle
    // -----------------------------------------------------------------------

    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {}
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void execute(String sql, String param) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("[NemonicMail] Erro SQL: " + e.getMessage());
        }
    }

    private List<Letter> mapAll(ResultSet rs) throws SQLException {
        List<Letter> list = new ArrayList<>();
        while (rs.next()) list.add(mapRow(rs));
        return list;
    }

    private Letter mapRow(ResultSet rs) throws SQLException {
        String recipStr = rs.getString("recipient_uuid");
        List<String> pages = gson.fromJson(rs.getString("pages"), LIST_STRING_TYPE);
        return new Letter(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("sender_uuid")),
                rs.getString("sender_name"),
                recipStr != null ? UUID.fromString(recipStr) : null,
                rs.getString("recipient_name"),
                pages,
                rs.getLong("sent_at"),
                rs.getLong("deliver_at"),
                rs.getInt("read") == 1,
                rs.getInt("delivered") == 1,
                LetterType.valueOf(rs.getString("type")),
                LetterPriority.valueOf(rs.getString("priority")),
                rs.getLong("expires_at")
        );
    }
}
