package mobiarmy.admin;

import com.google.gson.Gson;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mobiarmy.server.Server;
import mobiarmy.server.Session;
import mobiarmy.server.SessionManager;
import mobiarmy.server.User;
import org.mindrot.jbcrypt.BCrypt;

public final class AdminService {

    public static final long MAX_BALANCE = Integer.MAX_VALUE;
    static final Object PLAYER_IDENTITY_LOCK = new Object();
    private static final Gson GSON = new Gson();

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
                        reason VARCHAR(500) NULL,
                        before_data JSON NULL,
                        after_data JSON NULL,
                        request_id VARCHAR(64) NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        INDEX audit_created_idx (created_at),
                        INDEX audit_target_idx (target_user_id, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS user_account_state (
                        user_id INT NOT NULL PRIMARY KEY,
                        status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                        reason VARCHAR(500) NULL,
                        updated_by VARCHAR(100) NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                            ON UPDATE CURRENT_TIMESTAMP,
                        deleted_at TIMESTAMP NULL,
                        deleted_by VARCHAR(100) NULL,
                        CONSTRAINT user_account_state_user_fk FOREIGN KEY (user_id)
                            REFERENCES user(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            ensureColumn(connection, statement, "admin_audit_log", "reason",
                    "ALTER TABLE admin_audit_log ADD COLUMN reason VARCHAR(500) NULL AFTER detail");
            ensureColumn(connection, statement, "admin_audit_log", "before_data",
                    "ALTER TABLE admin_audit_log ADD COLUMN before_data JSON NULL AFTER reason");
            ensureColumn(connection, statement, "admin_audit_log", "after_data",
                    "ALTER TABLE admin_audit_log ADD COLUMN after_data JSON NULL AFTER before_data");
            ensureColumn(connection, statement, "admin_audit_log", "request_id",
                    "ALTER TABLE admin_audit_log ADD COLUMN request_id VARCHAR(64) NULL AFTER after_data");
        }
    }

    private static void ensureColumn(Connection connection, Statement statement, String table,
                                     String column, String alterSql) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, table, column)) {
            if (!columns.next()) {
                statement.executeUpdate(alterSql);
            }
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
                       b.reason AS ban_reason, b.banned_until,
                       COALESCE(s.status, 'ACTIVE') AS account_status,
                       s.reason AS account_reason, s.deleted_at
                FROM user u
                JOIN user_ p ON p.user_id = u.id
                LEFT JOIN user_ban b ON b.user_id = u.id
                LEFT JOIN user_account_state s ON s.user_id = u.id
                WHERE (? = '' OR CAST(u.id AS CHAR) = ?
                       OR LOWER(u.username) LIKE LOWER(?) OR LOWER(p.name) LIKE LOWER(?))
                ORDER BY u.id
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
                    int cup = result.getInt("cup");
                    String name = result.getString("name");
                    if (liveUser != null) {
                        synchronized (liveUser) {
                            xu = liveUser.xu;
                            luong = liveUser.luong;
                            cup = liveUser.cup;
                            name = liveUser.name;
                        }
                    }
                    users.add(new UserSummary(
                            result.getInt("id"),
                            result.getString("username"),
                            name,
                            xu,
                            luong,
                            cup,
                            online,
                            result.getBoolean("banned"),
                            result.getString("ban_reason"),
                            result.getTimestamp("banned_until"),
                            result.getString("account_status"),
                            result.getString("account_reason"),
                            result.getTimestamp("deleted_at")
                    ));
                }
            }
        }
        return users;
    }

    public record UserPage(List<UserSummary> rows, int total, int page, int pages, String filter) {}

    public static UserPage searchUsers(String query, String filter, int page) throws SQLException {
        if (!java.util.Set.of("ALL", "ONLINE", "OFFLINE", "BANNED", "LOCKED", "DELETED").contains(filter))
            throw new IllegalArgumentException("Bộ lọc không hợp lệ");
        List<UserSummary> matches = findUsers(query).stream().filter(u -> switch (filter) {
            case "ONLINE" -> u.online();
            case "OFFLINE" -> !u.online();
            case "BANNED" -> u.banned();
            case "LOCKED" -> "LOCKED".equals(u.accountStatus());
            case "DELETED" -> "DELETED".equals(u.accountStatus());
            default -> true;
        }).toList();
        int pages = Math.max(1, (matches.size() + 24) / 25);
        page = Math.max(1, Math.min(page, pages));
        return new UserPage(matches.subList((page - 1) * 25, Math.min(page * 25, matches.size())),
                matches.size(), page, pages, filter);
    }

    public static UserDetail getUserDetail(int userId) throws SQLException {
        UserSummary summary = findUserSummary(userId);
        if (summary == null) {
            return null;
        }

        User liveUser = SessionManager.findUserById(userId);
        int selectedGlass = readSelectedGlass(userId);
        String remoteAddress = "—";
        String clientVersion = "—";
        if (liveUser != null) {
            synchronized (liveUser) {
                selectedGlass = liveUser.selectGlass;
                summary = new UserSummary(summary.id(), summary.username(), liveUser.name,
                        liveUser.xu, liveUser.luong, liveUser.cup, summary.online(),
                        summary.banned(), summary.banReason(), summary.bannedUntil(),
                        summary.accountStatus(), summary.accountReason(), summary.deletedAt());
                if (liveUser.session != null) {
                    clientVersion = blankValue(liveUser.session.version);
                    if (liveUser.session.socket != null
                            && liveUser.session.socket.getRemoteSocketAddress() != null) {
                        remoteAddress = liveUser.session.socket.getRemoteSocketAddress().toString();
                    }
                }
            }
        }

        return new UserDetail(summary, selectedGlass, remoteAddress, clientVersion,
                readCharacters(userId, selectedGlass), readEquipment(userId),
                readEquipmentCatalog(),
                readInventory(userId), readMissions(userId), readFriends(userId),
                readWalletHistory(userId), readAudit(userId));
    }

    public static CreateUserResult createUser(String username, String password,
                                              String confirmPassword, String characterName,
                                              long initialXu, long initialLuong,
                                              String reason,
                                              String adminUsername) throws SQLException {
        String normalizedUsername = username == null ? "" : username.trim();
        String normalizedName = characterName == null ? "" : characterName.trim();
        validateNewUser(normalizedUsername, password, confirmPassword, normalizedName,
                initialXu, initialLuong);
        requireReason(reason);

        String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(12));
        synchronized (PLAYER_IDENTITY_LOCK) {
            int userId;
            try (Connection connection = Server.dbManager.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    ensureUserAvailable(connection, normalizedUsername, normalizedName);
                    userId = insertAccount(connection, normalizedUsername, passwordHash);
                    insertProfile(connection, userId, normalizedName, initialXu, initialLuong);
                    insertDefaultCharacters(connection, userId);
                    insertDefaultItems(connection, userId);
                    insertDefaultMissions(connection, userId);
                    insertAudit(connection, adminUsername, "CREATE_USER", userId,
                            "Tạo tài khoản " + normalizedUsername + " / " + normalizedName
                                    + " (xu=" + initialXu + ", lượng=" + initialLuong + ")",
                            reason, null, GSON.toJson(Map.of(
                                    "username", normalizedUsername,
                                    "name", normalizedName,
                                    "xu", initialXu,
                                    "luong", initialLuong)));
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

            User user = new User(userId, normalizedName);
            user.setWallet(initialXu, initialLuong, false);
            user.cup = 0;
            user.selectGlass = 0;
            SessionManager.addUser(user);
            return new CreateUserResult(userId, normalizedUsername, normalizedName);
        }
    }

    private static void validateNewUser(String username, String password, String confirmPassword,
                                        String characterName, long initialXu, long initialLuong) {
        if (!username.matches("[A-Za-z0-9_]{3,32}")) {
            throw new IllegalArgumentException(
                    "Tài khoản phải dài 3-32 ký tự và chỉ gồm chữ, số hoặc dấu gạch dưới");
        }
        if (password == null || password.length() < 6
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Mật khẩu phải dài từ 6 đến 72 byte");
        }
        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Mật khẩu nhập lại không khớp");
        }
        int nameLength = characterName.codePointCount(0, characterName.length());
        if (nameLength < 3 || nameLength > 32 || characterName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Tên nhân vật phải dài 3-32 ký tự và không chứa ký tự điều khiển");
        }
        if (initialXu < 0 || initialXu > MAX_BALANCE
                || initialLuong < 0 || initialLuong > MAX_BALANCE) {
            throw new IllegalArgumentException("Xu và lượng ban đầu phải từ 0 đến " + MAX_BALANCE);
        }
    }

    private static void ensureUserAvailable(Connection connection, String username,
                                            String characterName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT u.username, p.name
                FROM user u LEFT JOIN user_ p ON p.user_id = u.id
                WHERE LOWER(u.username) = LOWER(?) OR LOWER(p.name) = LOWER(?)
                LIMIT 1 FOR UPDATE
                """)) {
            statement.setString(1, username);
            statement.setString(2, characterName);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    if (username.equalsIgnoreCase(result.getString("username"))) {
                        throw new IllegalArgumentException("Tài khoản đã tồn tại");
                    }
                    throw new IllegalArgumentException("Tên nhân vật đã tồn tại");
                }
            }
        }
    }

    private static int insertAccount(Connection connection, String username, String passwordHash)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO user (username, password) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Không lấy được ID user vừa tạo");
                }
                return keys.getInt(1);
            }
        }
    }

    private static void insertProfile(Connection connection, int userId, String characterName,
                                      long initialXu, long initialLuong) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_ (user_id, name, xu, luong, cup, glass)
                VALUES (?, ?, ?, ?, 0, 0)
                """)) {
            statement.setInt(1, userId);
            statement.setString(2, characterName);
            statement.setLong(3, initialXu);
            statement.setLong(4, initialLuong);
            statement.executeUpdate();
        }
    }

    private static void insertDefaultCharacters(Connection connection, int userId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_glass
                    (user_id, glassID, ability, equipID, data, point, level, exp)
                SELECT ?, id, ability, equipID, 'null', 0, 1, 0
                FROM glass WHERE xu = 0 AND luong = 0
                """)) {
            statement.setInt(1, userId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Không có nhân vật mặc định để khởi tạo user");
            }
        }
    }

    private static void insertDefaultItems(Connection connection, int userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_item (user_id, item_id, num)
                SELECT ?, id, CASE WHEN id < 2 THEN 99 ELSE 0 END FROM item
                """)) {
            statement.setInt(1, userId);
            statement.executeUpdate();
        }
    }

    private static void insertDefaultMissions(Connection connection, int userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_mission
                    (user_id, mission_id, level, have, isComplete, isGetReward)
                SELECT ?, id, level, 0, 0, 0 FROM mission WHERE level = 1
                """)) {
            statement.setInt(1, userId);
            statement.executeUpdate();
        }
    }

    private static UserSummary findUserSummary(int userId) throws SQLException {
        String sql = """
                SELECT u.id, u.username, p.name, p.xu, p.luong, p.cup,
                       CASE WHEN b.user_id IS NOT NULL
                                 AND b.revoked_at IS NULL
                                 AND (b.banned_until IS NULL OR b.banned_until > CURRENT_TIMESTAMP)
                            THEN 1 ELSE 0 END AS banned,
                       b.reason AS ban_reason, b.banned_until,
                       COALESCE(s.status, 'ACTIVE') AS account_status,
                       s.reason AS account_reason, s.deleted_at
                FROM user u
                JOIN user_ p ON p.user_id = u.id
                LEFT JOIN user_ban b ON b.user_id = u.id
                LEFT JOIN user_account_state s ON s.user_id = u.id
                WHERE u.id = ?
                """;
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                User liveUser = SessionManager.findUserById(userId);
                boolean online = liveUser != null && liveUser.session != null
                        && liveUser.session.connected;
                return new UserSummary(result.getInt("id"), result.getString("username"),
                        result.getString("name"), result.getLong("xu"),
                        result.getLong("luong"), result.getInt("cup"), online,
                        result.getBoolean("banned"), result.getString("ban_reason"),
                        result.getTimestamp("banned_until"),
                        result.getString("account_status"), result.getString("account_reason"),
                        result.getTimestamp("deleted_at"));
            }
        }
    }

    private static int readSelectedGlass(int userId) throws SQLException {
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT glass FROM user_ WHERE user_id = ?")) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt("glass") : 0;
            }
        }
    }

    private static List<CharacterDetail> readCharacters(int userId, int selectedGlass)
            throws SQLException {
        ArrayList<CharacterDetail> rows = new ArrayList<>();
        String sql = """
                SELECT ug.glassID, COALESCE(g.name, CONCAT('Nhân vật #', ug.glassID)) AS name,
                       ug.level, ug.exp, ug.point, ug.ability, ug.equipID
                FROM user_glass ug
                LEFT JOIN glass g ON g.id = ug.glassID
                WHERE ug.user_id = ? ORDER BY ug.glassID
                """;
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new CharacterDetail(result.getInt("glassID"),
                            result.getString("name"), result.getInt("level"),
                            result.getLong("exp"), result.getInt("point"),
                            result.getString("ability"), result.getString("equipID"),
                            result.getInt("glassID") == selectedGlass));
                }
            }
        }
        return rows;
    }

    private static List<EquipmentDetail> readEquipment(int userId) throws SQLException {
        ArrayList<EquipmentDetail> rows = new ArrayList<>();
        String sql = """
                SELECT ue.glassID, COALESCE(g.name, CONCAT('#', ue.glassID)) AS glass_name,
                       ue.equipID,
                       COALESCE(NULLIF(e.name, ''),
                                CONCAT('Trang bị #', ue.glassID, ':', ue.equipID)) AS equip_name,
                       COALESCE(e.type, -1) AS equip_type, ue.level2, ue.inv_ability,
                       ue.inv_percen, ue.slot, ue.dbKey, ue.isUse, ue.renewalDate,
                       COALESCE(e.date, 0) AS duration_days
                FROM user_equip ue
                LEFT JOIN glass g ON g.id = ue.glassID
                LEFT JOIN equip e ON e.glassID = ue.glassID AND e.id = ue.equipID
                WHERE ue.user_id = ?
                ORDER BY ue.isUse DESC, ue.glassID, e.type, ue.equipID
                """;
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new EquipmentDetail(result.getInt("glassID"),
                            result.getString("glass_name"), result.getInt("equipID"),
                            result.getString("equip_name"), result.getInt("equip_type"),
                            result.getInt("level2"), result.getString("inv_ability"),
                            result.getString("inv_percen"), result.getString("slot"),
                            result.getInt("dbKey"), result.getBoolean("isUse"),
                            result.getLong("renewalDate"), result.getInt("duration_days")));
                }
            }
        }
        return rows;
    }

    private static List<EquipmentCatalog> readEquipmentCatalog() throws SQLException {
        ArrayList<EquipmentCatalog> rows = new ArrayList<>();
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT e.glassID, e.id,
                            COALESCE(NULLIF(e.name, ''),
                                     CONCAT('Trang bị #', e.glassID, ':', e.id)) AS name,
                            e.type,
                            COALESCE(g.name, CONCAT('Nhân vật #', e.glassID)) AS glass_name
                     FROM equip e LEFT JOIN glass g ON g.id = e.glassID
                     ORDER BY e.glassID, e.type, e.id
                     """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(new EquipmentCatalog(result.getInt("glassID"), result.getInt("id"),
                        result.getString("name"), result.getInt("type"),
                        result.getString("glass_name")));
            }
        }
        return rows;
    }

    private static List<InventoryItem> readInventory(int userId) throws SQLException {
        ArrayList<InventoryItem> rows = new ArrayList<>();
        String sql = """
                SELECT 'ITEM' AS kind, ui.item_id AS item_id,
                       COALESCE(i.name, CONCAT('Item #', ui.item_id)) AS name,
                       ui.num AS quantity, '' AS detail
                FROM user_item ui LEFT JOIN item i ON i.id = ui.item_id
                WHERE ui.user_id = ? AND ui.num > 0
                UNION ALL
                SELECT 'SPECIAL' AS kind, ul.linhtinh_id AS item_id,
                       COALESCE(l.name, CONCAT('Đặc biệt #', ul.linhtinh_id)) AS name,
                       ul.num AS quantity, COALESCE(l.detail, '') AS detail
                FROM user_linhtinh ul LEFT JOIN linhtinh l ON l.id = ul.linhtinh_id
                WHERE ul.user_id = ? AND ul.num > 0
                ORDER BY kind, item_id
                """;
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            statement.setInt(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new InventoryItem(result.getString("kind"),
                            result.getInt("item_id"), result.getString("name"),
                            result.getLong("quantity"), result.getString("detail")));
                }
            }
        }
        return rows;
    }

    private static List<MissionDetail> readMissions(int userId) throws SQLException {
        ArrayList<MissionDetail> rows = new ArrayList<>();
        String sql = """
                SELECT um.mission_id, um.level,
                       COALESCE(m.name, CONCAT('Nhiệm vụ #', um.mission_id)) AS name,
                       um.have, COALESCE(m.require, 0) AS required,
                       COALESCE(m.reward, '') AS reward, um.isComplete, um.isGetReward
                FROM user_mission um
                LEFT JOIN mission m ON m.id = um.mission_id AND m.level = um.level
                WHERE um.user_id = ? ORDER BY um.isComplete, um.mission_id, um.level
                """;
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new MissionDetail(result.getInt("mission_id"),
                            result.getInt("level"), result.getString("name"),
                            result.getLong("have"), result.getLong("required"),
                            result.getString("reward"), result.getBoolean("isComplete"),
                            result.getBoolean("isGetReward")));
                }
            }
        }
        return rows;
    }

    private static List<FriendDetail> readFriends(int userId) throws SQLException {
        ArrayList<FriendDetail> rows = new ArrayList<>();
        String sql = """
                SELECT f.friend_id, u.username, p.name
                FROM user_friend f
                LEFT JOIN user u ON u.id = f.friend_id
                LEFT JOIN user_ p ON p.user_id = f.friend_id
                WHERE f.user_id = ? ORDER BY p.name, u.username
                """;
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int friendId = result.getInt("friend_id");
                    User friend = SessionManager.findUserById(friendId);
                    boolean online = friend != null && friend.session != null
                            && friend.session.connected;
                    rows.add(new FriendDetail(friendId, result.getString("username"),
                            result.getString("name"), online));
                }
            }
        }
        return rows;
    }

    private static List<WalletEntry> readWalletHistory(int userId) throws SQLException {
        ArrayList<WalletEntry> rows = new ArrayList<>();
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT currency, amount, balance_before, balance_after, reason,
                            admin_username, created_at
                     FROM wallet_transaction WHERE user_id = ? ORDER BY id DESC LIMIT 50
                     """)) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new WalletEntry(result.getString("currency"),
                            result.getLong("amount"), result.getLong("balance_before"),
                            result.getLong("balance_after"), result.getString("reason"),
                            result.getString("admin_username"), result.getTimestamp("created_at")));
                }
            }
        }
        return rows;
    }

    private static List<AuditEntry> readAudit(int userId) throws SQLException {
        ArrayList<AuditEntry> rows = new ArrayList<>();
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT admin_username, action, target_user_id, detail, reason,
                            before_data, after_data, request_id, created_at
                     FROM admin_audit_log WHERE target_user_id = ? ORDER BY id DESC LIMIT 50
                     """)) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new AuditEntry(result.getString("admin_username"),
                            result.getString("action"), userId, result.getString("detail"),
                            result.getString("reason"), result.getString("before_data"),
                            result.getString("after_data"), result.getString("request_id"),
                            result.getTimestamp("created_at")));
                }
            }
        }
        return rows;
    }

    private static String blankValue(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Phải nhập lý do thao tác");
        }
        if (reason.trim().length() > 500) {
            throw new IllegalArgumentException("Lý do không được vượt 500 ký tự");
        }
    }

    public static WalletResult adjustWallet(int userId, String currency, long amount,
                                            String reason, String adminUsername) throws SQLException {
        if (!"xu".equals(currency) && !"luong".equals(currency)) {
            throw new IllegalArgumentException("Loại tiền không hợp lệ");
        }
        if (amount == 0) {
            throw new IllegalArgumentException("Số tiền thay đổi phải khác 0");
        }
        requireReason(reason);

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
        requireReason(reason);
        User user = SessionManager.findUserById(userId);
        Session session = user == null ? null : user.session;
        boolean online = session != null && session.connected;
        audit(adminUsername, "KICK", userId, "Ngắt kết nối", reason,
                null, GSON.toJson(Map.of("online", false)));
        if (online) {
            session.requestDisconnect();
        }
        return online;
    }

    public static void ban(int userId, String reason, long minutes, String adminUsername) throws SQLException {
        requireReason(reason);
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
                minutes == 0 ? "Khóa vĩnh viễn" : "Khóa " + minutes + " phút",
                reason, null, GSON.toJson(Map.of("minutes", minutes)));
        User user = SessionManager.findUserById(userId);
        if (user != null && user.session != null && user.session.connected) {
            user.session.requestDisconnect();
        }
    }

    public static void unban(int userId, String reason, String adminUsername) throws SQLException {
        requireReason(reason);
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
        audit(adminUsername, "UNBAN", userId, "Gỡ ban", reason,
                GSON.toJson(Map.of("banned", true)), GSON.toJson(Map.of("banned", false)));
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
                     SELECT admin_username, action, target_user_id, detail, reason,
                            before_data, after_data, request_id, created_at
                     FROM admin_audit_log ORDER BY id DESC LIMIT 50
                     """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                entries.add(new AuditEntry(
                        result.getString("admin_username"),
                        result.getString("action"),
                        (Integer) result.getObject("target_user_id"),
                        result.getString("detail"),
                        result.getString("reason"),
                        result.getString("before_data"),
                        result.getString("after_data"),
                        result.getString("request_id"),
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
                        (amount > 0 ? "+" : "") + amount + ": " + before + " → " + after,
                        reason, GSON.toJson(Map.of(currency, before)),
                        GSON.toJson(Map.of(currency, after)));
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
                              String detail, String reason, String beforeData,
                              String afterData) throws SQLException {
        try (Connection connection = Server.dbManager.getConnection()) {
            insertAudit(connection, adminUsername, action, targetUserId, detail,
                    reason, beforeData, afterData);
        }
    }

    static void insertAudit(Connection connection, String adminUsername, String action,
                                    Integer targetUserId, String detail, String reason,
                                    String beforeData, String afterData) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO admin_audit_log
                    (admin_username, action, target_user_id, detail, reason,
                     before_data, after_data, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, adminUsername);
            statement.setString(2, action);
            if (targetUserId == null) {
                statement.setNull(3, java.sql.Types.INTEGER);
            } else {
                statement.setInt(3, targetUserId);
            }
            statement.setString(4, detail == null ? "" : detail);
            statement.setString(5, reason == null ? null : reason.trim());
            statement.setString(6, beforeData);
            statement.setString(7, afterData);
            statement.setString(8, UUID.randomUUID().toString());
            statement.executeUpdate();
        }
    }

    public record Dashboard(long startedAt, int sessions, int onlineUsers, int loadedUsers) {
    }

    public record UserSummary(int id, String username, String name, long xu, long luong, int cup,
                              boolean online, boolean banned, String banReason, Timestamp bannedUntil,
                              String accountStatus, String accountReason, Timestamp deletedAt) {
    }

    public record WalletResult(long before, long after) {
    }

    public record AuditEntry(String adminUsername, String action, Integer targetUserId,
                             String detail, String reason, String beforeData, String afterData,
                             String requestId, Timestamp createdAt) {
    }

    public record UserDetail(UserSummary summary, int selectedGlass, String remoteAddress,
                             String clientVersion, List<CharacterDetail> characters,
                             List<EquipmentDetail> equipment,
                             List<EquipmentCatalog> equipmentCatalog,
                             List<InventoryItem> inventory,
                             List<MissionDetail> missions, List<FriendDetail> friends,
                             List<WalletEntry> walletHistory, List<AuditEntry> audit) {
    }

    public record CharacterDetail(int glassId, String name, int level, long exp, int point,
                                  String ability, String equipment, boolean selected) {
    }

    public record EquipmentDetail(int glassId, String glassName, int equipmentId,
                                  String name, int type, int upgradeLevel, String ability,
                                  String percent, String slots, int databaseKey, boolean inUse,
                                  long renewalDate, int durationDays) {
    }

    public record EquipmentCatalog(int glassId, int equipmentId, String name, int type,
                                   String glassName) {
    }

    public record InventoryItem(String kind, int itemId, String name, long quantity,
                                String detail) {
    }

    public record MissionDetail(int missionId, int level, String name, long progress,
                                long required, String reward, boolean complete,
                                boolean rewardClaimed) {
    }

    public record FriendDetail(int userId, String username, String name, boolean online) {
    }

    public record WalletEntry(String currency, long amount, long before, long after,
                              String reason, String adminUsername, Timestamp createdAt) {
    }

    public record CreateUserResult(int userId, String username, String characterName) {
    }
}
