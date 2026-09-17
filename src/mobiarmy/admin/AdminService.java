package mobiarmy.admin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import mobiarmy.server.Server;
import mobiarmy.server.Session;
import mobiarmy.server.SessionManager;
import mobiarmy.server.User;

public final class AdminService {

    public static final long MAX_BALANCE = Integer.MAX_VALUE;

    private AdminService() {
    }

    public static void ensureSchema() throws SQLException {
        try (Connection connection = Server.dbManager.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS user_ban (
                        user_id INT NOT NULL PRIMARY KEY,
                        reason VARCHAR(500) NOT NULL,
                        banned_by VARCHAR(100) NOT NULL,
                        banned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        banned_until TIMESTAMP NULL,
                        revoked_at TIMESTAMP NULL,
                        revoked_by VARCHAR(100) NULL,
                        CONSTRAINT user_ban_user_fk FOREIGN KEY (user_id)
                            REFERENCES user(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS wallet_transaction (
                        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        user_id INT NOT NULL,
                        currency VARCHAR(16) NOT NULL,
                        amount BIGINT NOT NULL,
                        balance_before BIGINT NOT NULL,
                        balance_after BIGINT NOT NULL,
                        reason VARCHAR(500) NOT NULL,
                        admin_username VARCHAR(100) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        INDEX wallet_user_created_idx (user_id, created_at),
                        CONSTRAINT wallet_transaction_user_fk FOREIGN KEY (user_id)
                            REFERENCES user(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS admin_audit_log (
                        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        admin_username VARCHAR(100) NOT NULL,
                        action VARCHAR(64) NOT NULL,
                        target_user_id INT NULL,
                        detail VARCHAR(1000) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        INDEX audit_created_idx (created_at),
                        INDEX audit_target_idx (target_user_id, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }
    }

    public static Dashboard dashboard() {
        return new Dashboard(
                Server.getStartedAt(),
                SessionManager.getSessionsSize(),
                SessionManager.getOnlineUsersSize(),
                SessionManager.getUsersIdSize()
        );
    }

    public static List<UserSummary> findUsers(String query) throws SQLException {
        String normalized = query == null ? "" : query.trim();
        String pattern = "%" + normalized + "%";
        ArrayList<UserSummary> users = new ArrayList<>();
        String sql = """
                SELECT u.id, u.username, p.name, p.xu, p.luong, p.cup,
                       CASE WHEN b.user_id IS NOT NULL
                                 AND b.revoked_at IS NULL
                                 AND (b.banned_until IS NULL OR b.banned_until > CURRENT_TIMESTAMP)
                            THEN 1 ELSE 0 END AS banned,
                       b.reason AS ban_reason, b.banned_until
                FROM user u
                JOIN user_ p ON p.user_id = u.id
                LEFT JOIN user_ban b ON b.user_id = u.id
                WHERE (? = '' OR CAST(u.id AS CHAR) = ?
                       OR LOWER(u.username) LIKE LOWER(?) OR LOWER(p.name) LIKE LOWER(?))
                ORDER BY u.id
                LIMIT 200
                """;
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalized);
            statement.setString(2, normalized);
            statement.setString(3, pattern);
            statement.setString(4, pattern);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    User liveUser = SessionManager.findUserById(result.getInt("id"));
                    boolean online = liveUser != null
                            && liveUser.session != null
                            && liveUser.session.connected;
                    long xu = result.getLong("xu");
                    long luong = result.getLong("luong");
                    if (liveUser != null) {
                        synchronized (liveUser) {
                            xu = liveUser.xu;
                            luong = liveUser.luong;
                        }
                    }
                    users.add(new UserSummary(
                            result.getInt("id"),
                            result.getString("username"),
                            result.getString("name"),
                            xu,
                            luong,
                            result.getInt("cup"),
                            online,
                            result.getBoolean("banned"),
                            result.getString("ban_reason"),
                            result.getTimestamp("banned_until")
                    ));
                }
            }
        }
        return users;
    }

    public static WalletResult adjustWallet(int userId, String currency, long amount,
                                            String reason, String adminUsername) throws SQLException {
        if (!"xu".equals(currency) && !"luong".equals(currency)) {
            throw new IllegalArgumentException("Loại tiền không hợp lệ");
        }
        if (amount == 0) {
            throw new IllegalArgumentException("Số tiền thay đổi phải khác 0");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Phải nhập lý do");
        }

        User liveUser = SessionManager.findUserById(userId);
        Object lock = liveUser == null ? AdminService.class : liveUser;
        synchronized (lock) {
            long currentXu;
            long currentLuong;
            if (liveUser != null) {
                currentXu = liveUser.xu;
                currentLuong = liveUser.luong;
            } else {
                long[] wallet = readWallet(userId);
                currentXu = wallet[0];
                currentLuong = wallet[1];
            }

            long before = "xu".equals(currency) ? currentXu : currentLuong;
            long after;
            try {
                after = Math.addExact(before, amount);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Số tiền vượt giới hạn");
            }
            if (after < 0 || after > MAX_BALANCE) {
                throw new IllegalArgumentException("Số dư sau thay đổi phải từ 0 đến " + MAX_BALANCE);
            }

            long newXu = "xu".equals(currency) ? after : currentXu;
            long newLuong = "luong".equals(currency) ? after : currentLuong;
            writeWalletTransaction(userId, currency, amount, before, after, newXu, newLuong,
                    reason.trim(), adminUsername);

            if (liveUser != null) {
                liveUser.setWallet(newXu, newLuong, true);
            }
            return new WalletResult(before, after);
        }
    }

    public static boolean kick(int userId, String reason, String adminUsername) throws SQLException {
        User user = SessionManager.findUserById(userId);
        Session session = user == null ? null : user.session;
        boolean online = session != null && session.connected;
        audit(adminUsername, "KICK", userId, reason == null ? "" : reason.trim());
        if (online) {
            session.requestDisconnect();
        }
        return online;
    }

    public static void ban(int userId, String reason, long minutes, String adminUsername) throws SQLException {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Phải nhập lý do ban");
        }
        if (minutes < 0) {
            throw new IllegalArgumentException("Thời gian ban không hợp lệ");
        }
        String sql = """
                INSERT INTO user_ban
                    (user_id, reason, banned_by, banned_at, banned_until, revoked_at, revoked_by)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP,
                        CASE WHEN ? = 0 THEN NULL ELSE TIMESTAMPADD(MINUTE, ?, CURRENT_TIMESTAMP) END,
                        NULL, NULL)
                ON DUPLICATE KEY UPDATE
                    reason = VALUES(reason), banned_by = VALUES(banned_by),
                    banned_at = CURRENT_TIMESTAMP, banned_until = VALUES(banned_until),
                    revoked_at = NULL, revoked_by = NULL
                """;
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            statement.setString(2, reason.trim());
            statement.setString(3, adminUsername);
            statement.setLong(4, minutes);
            statement.setLong(5, minutes);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Không tìm thấy user");
            }
        }
        audit(adminUsername, "BAN", userId,
                reason.trim() + (minutes == 0 ? " (vĩnh viễn)" : " (" + minutes + " phút)"));
        User user = SessionManager.findUserById(userId);
        if (user != null && user.session != null && user.session.connected) {
            user.session.requestDisconnect();
        }
    }

    public static void unban(int userId, String adminUsername) throws SQLException {
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE user_ban
                     SET revoked_at = CURRENT_TIMESTAMP, revoked_by = ?
                     WHERE user_id = ? AND revoked_at IS NULL
                     """)) {
            statement.setString(1, adminUsername);
            statement.setInt(2, userId);
            statement.executeUpdate();
        }
        audit(adminUsername, "UNBAN", userId, "Gỡ ban");
    }

    public static String activeBanMessage(int userId) throws SQLException {
        String sql = """
                SELECT reason, banned_until
                FROM user_ban
                WHERE user_id = ? AND revoked_at IS NULL
                  AND (banned_until IS NULL OR banned_until > CURRENT_TIMESTAMP)
                """;
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Timestamp until = result.getTimestamp("banned_until");
                return until == null
                        ? "Tài khoản đã bị khóa vĩnh viễn: " + result.getString("reason")
                        : "Tài khoản bị khóa đến " + until + ": " + result.getString("reason");
            }
        }
    }

    public static List<AuditEntry> recentAudit() throws SQLException {
        ArrayList<AuditEntry> entries = new ArrayList<>();
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT admin_username, action, target_user_id, detail, created_at
                     FROM admin_audit_log ORDER BY id DESC LIMIT 50
                     """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                entries.add(new AuditEntry(
                        result.getString("admin_username"),
                        result.getString("action"),
                        (Integer) result.getObject("target_user_id"),
                        result.getString("detail"),
                        result.getTimestamp("created_at")
                ));
            }
        }
        return entries;
    }

    private static long[] readWallet(int userId) throws SQLException {
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT xu, luong FROM user_ WHERE user_id = ?")) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Không tìm thấy user");
                }
                return new long[]{result.getLong("xu"), result.getLong("luong")};
            }
        }
    }

    private static void writeWalletTransaction(int userId, String currency, long amount,
                                               long before, long after, long newXu, long newLuong,
                                               String reason, String adminUsername) throws SQLException {
        try (Connection connection = Server.dbManager.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE user_ SET xu = ?, luong = ? WHERE user_id = ?")) {
                    update.setLong(1, newXu);
                    update.setLong(2, newLuong);
                    update.setInt(3, userId);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Không tìm thấy user");
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO wallet_transaction
                            (user_id, currency, amount, balance_before, balance_after, reason, admin_username)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setInt(1, userId);
                    insert.setString(2, currency);
                    insert.setLong(3, amount);
                    insert.setLong(4, before);
                    insert.setLong(5, after);
                    insert.setString(6, reason);
                    insert.setString(7, adminUsername);
                    insert.executeUpdate();
                }
                insertAudit(connection, adminUsername, "WALLET_" + currency.toUpperCase(), userId,
                        (amount > 0 ? "+" : "") + amount + ": " + before + " -> " + after + " (" + reason + ")");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void audit(String adminUsername, String action, Integer targetUserId,
                              String detail) throws SQLException {
        try (Connection connection = Server.dbManager.getConnection()) {
            insertAudit(connection, adminUsername, action, targetUserId, detail);
        }
    }

    private static void insertAudit(Connection connection, String adminUsername, String action,
                                    Integer targetUserId, String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO admin_audit_log (admin_username, action, target_user_id, detail)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, adminUsername);
            statement.setString(2, action);
            if (targetUserId == null) {
                statement.setNull(3, java.sql.Types.INTEGER);
            } else {
                statement.setInt(3, targetUserId);
            }
            statement.setString(4, detail == null ? "" : detail);
            statement.executeUpdate();
        }
    }

    public record Dashboard(long startedAt, int sessions, int onlineUsers, int loadedUsers) {
    }

    public record UserSummary(int id, String username, String name, long xu, long luong, int cup,
                              boolean online, boolean banned, String banReason, Timestamp bannedUntil) {
    }

    public record WalletResult(long before, long after) {
    }

    public record AuditEntry(String adminUsername, String action, Integer targetUserId,
                             String detail, Timestamp createdAt) {
    }
}
