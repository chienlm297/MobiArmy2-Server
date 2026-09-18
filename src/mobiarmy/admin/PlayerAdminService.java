package mobiarmy.admin;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mobiarmy.server.Equip;
import mobiarmy.server.Exp;
import mobiarmy.server.Glass;
import mobiarmy.server.Item;
import mobiarmy.server.LinhTinh;
import mobiarmy.server.Server;
import mobiarmy.server.SessionManager;
import mobiarmy.server.User;
import org.mindrot.jbcrypt.BCrypt;

public final class PlayerAdminService {

    private static final Gson GSON = new Gson();
    private static final int MAX_ITEM_QUANTITY = 255;
    private static final int MAX_ABILITY = 100_000;

    private PlayerAdminService() {
    }

    public static void resetPassword(int userId, String password, String confirmPassword,
                                     String reason, String adminUsername) throws SQLException {
        requireReason(reason);
        if (password == null || password.length() < 6
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Mật khẩu phải dài từ 6 đến 72 byte");
        }
        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Mật khẩu nhập lại không khớp");
        }
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(12));
        try (Connection connection = Server.dbManager.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireUser(connection, userId);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE user SET password = ? WHERE id = ?")) {
                    statement.setString(1, hash);
                    statement.setInt(2, userId);
                    statement.executeUpdate();
                }
                insertAudit(connection, adminUsername, "RESET_PASSWORD", userId,
                        "Đặt lại mật khẩu", reason, null, null);
                connection.commit();
            } catch (Exception exception) {
                rollback(connection, exception);
            } finally {
                connection.setAutoCommit(true);
            }
        }
        disconnect(userId);
    }

    public static void changeAccountState(int userId, String status, String reason,
                                          String adminUsername) throws SQLException {
        changeAccountState(userId, status, reason, adminUsername, true);
    }

    public static void changeAccountState(int userId, String status, String reason,
                                          String adminUsername, boolean allowRestore) throws SQLException {
        requireReason(reason);
        if (!"ACTIVE".equals(status) && !"LOCKED".equals(status) && !"DELETED".equals(status)) {
            throw new IllegalArgumentException("Trạng thái tài khoản không hợp lệ");
        }
        String before;
        try (Connection connection = Server.dbManager.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireUser(connection, userId);
                before = readAccountStatus(connection, userId);
                if ("DELETED".equals(before) && !allowRestore)
                    throw new SecurityException("Không có quyền thay đổi tài khoản đã xóa mềm");
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO user_account_state
                            (user_id, status, reason, updated_by, updated_at, deleted_at, deleted_by)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP,
                                CASE WHEN ? = 'DELETED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                                CASE WHEN ? = 'DELETED' THEN ? ELSE NULL END)
                        ON DUPLICATE KEY UPDATE status = VALUES(status), reason = VALUES(reason),
                            updated_by = VALUES(updated_by), updated_at = CURRENT_TIMESTAMP,
                            deleted_at = VALUES(deleted_at), deleted_by = VALUES(deleted_by)
                        """)) {
                    statement.setInt(1, userId);
                    statement.setString(2, status);
                    statement.setString(3, reason.trim());
                    statement.setString(4, adminUsername);
                    statement.setString(5, status);
                    statement.setString(6, status);
                    statement.setString(7, adminUsername);
                    statement.executeUpdate();
                }
                insertAudit(connection, adminUsername, "ACCOUNT_" + status, userId,
                        stateAction(status), reason, json("status", before), json("status", status));
                connection.commit();
            } catch (Exception exception) {
                rollback(connection, exception);
                return;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        if (!"ACTIVE".equals(status)) {
            disconnect(userId);
        }
    }

    public static RenameResult renameCharacter(int userId, String newName, String reason,
                                                String adminUsername) throws SQLException {
        requireReason(reason);
        String normalized = newName == null ? "" : newName.trim();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 3 || length > 32 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Tên nhân vật phải dài 3-32 ký tự hợp lệ");
        }
        User liveUser = SessionManager.findUserById(userId);
        synchronized (AdminService.PLAYER_IDENTITY_LOCK) {
            String oldName;
            try (Connection connection = Server.dbManager.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    oldName = readCharacterName(connection, userId, true);
                    try (PreparedStatement duplicate = connection.prepareStatement(
                            "SELECT user_id FROM user_ WHERE LOWER(name) = LOWER(?) AND user_id <> ? LIMIT 1")) {
                        duplicate.setString(1, normalized);
                        duplicate.setInt(2, userId);
                        try (ResultSet result = duplicate.executeQuery()) {
                            if (result.next()) {
                                throw new IllegalArgumentException("Tên nhân vật đã tồn tại");
                            }
                        }
                    }
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE user_ SET name = ? WHERE user_id = ?")) {
                        update.setString(1, normalized);
                        update.setInt(2, userId);
                        update.executeUpdate();
                    }
                    insertAudit(connection, adminUsername, "RENAME_CHARACTER", userId,
                            oldName + " → " + normalized, reason,
                            json("name", oldName), json("name", normalized));
                    connection.commit();
                } catch (Exception exception) {
                    rollback(connection, exception);
                    return null;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            if (liveUser != null) {
                SessionManager.renameUser(liveUser, normalized);
                disconnect(userId);
            }
            return new RenameResult(oldName, normalized);
        }
    }

    public static ValueChange adjustCup(int userId, long amount, String reason,
                                        String adminUsername) throws SQLException {
        requireReason(reason);
        if (amount == 0) {
            throw new IllegalArgumentException("Số cup thay đổi phải khác 0");
        }
        User liveUser = SessionManager.findUserById(userId);
        Object lock = liveUser == null ? PlayerAdminService.class : liveUser;
        synchronized (lock) {
            long before = liveUser == null ? readCup(userId) : liveUser.cup;
            long after;
            try {
                after = Math.addExact(before, amount);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Số cup vượt giới hạn");
            }
            if (after < 0 || after > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Cup sau thay đổi phải từ 0 đến " + Integer.MAX_VALUE);
            }
            try (Connection connection = Server.dbManager.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE user_ SET cup = ? WHERE user_id = ?")) {
                        update.setLong(1, after);
                        update.setInt(2, userId);
                        if (update.executeUpdate() != 1) {
                            throw new IllegalArgumentException("Không tìm thấy user");
                        }
                    }
                    insertAudit(connection, adminUsername, "ADJUST_CUP", userId,
                            signed(amount) + ": " + before + " → " + after, reason,
                            json("cup", before), json("cup", after));
                    connection.commit();
                } catch (Exception exception) {
                    rollback(connection, exception);
                    return null;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            if (liveUser != null) {
                liveUser.cup = (int) after;
                refreshOnline(liveUser);
            }
            return new ValueChange(before, after);
        }
    }

    public static CharacterChange updateCharacter(int userId, int glassId, int exp, int point,
                                                  String abilityJson, String reason,
                                                  String adminUsername) throws SQLException {
        requireReason(reason);
        if (glassId < 0 || glassId > 127 || point < 0 || point > Short.MAX_VALUE || exp < 0) {
            throw new IllegalArgumentException("Thông tin nhân vật không hợp lệ");
        }
        int maxExp = Exp.entrys[Exp.entrys.length - 1].exp;
        if (exp > maxExp) {
            throw new IllegalArgumentException("EXP không được vượt " + maxExp);
        }
        int[] ability = parseAbility(abilityJson);
        int level = Exp.getLevelExp(exp);
        User liveUser = SessionManager.findUserById(userId);
        Object lock = liveUser == null ? PlayerAdminService.class : liveUser;
        synchronized (lock) {
            CharacterChange before;
            try (Connection connection = Server.dbManager.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    before = readCharacter(connection, userId, glassId);
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE user_glass SET exp = ?, level = ?, point = ?, ability = ?
                            WHERE user_id = ? AND glassID = ?
                            """)) {
                        update.setInt(1, exp);
                        update.setInt(2, level);
                        update.setInt(3, point);
                        update.setString(4, GSON.toJson(ability));
                        update.setInt(5, userId);
                        update.setInt(6, glassId);
                        if (update.executeUpdate() != 1) {
                            throw new IllegalArgumentException("User chưa mở nhân vật này");
                        }
                    }
                    CharacterChange after = new CharacterChange(exp, level, point, GSON.toJson(ability));
                    insertAudit(connection, adminUsername, "UPDATE_CHARACTER", userId,
                            "Nhân vật #" + glassId + ": Lv." + before.level() + " → Lv." + level,
                            reason, GSON.toJson(before), GSON.toJson(after));
                    connection.commit();
                } catch (Exception exception) {
                    rollback(connection, exception);
                    return null;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            if (liveUser != null) {
                Glass glass = liveUser.getGlass((byte) glassId);
                if (glass != null) {
                    glass.exp = exp;
                    glass.level = level;
                    glass.point = point;
                    glass.ability = ability.clone();
                    glass.updateAll();
                    refreshOnline(liveUser);
                }
            }
            return new CharacterChange(exp, level, point, GSON.toJson(ability));
        }
    }

    public static ValueChange adjustInventory(int userId, String kind, int itemId, long amount,
                                              String reason, String adminUsername) throws SQLException {
        requireReason(reason);
        if (amount == 0) {
            throw new IllegalArgumentException("Số lượng thay đổi phải khác 0");
        }
        boolean special = "SPECIAL".equals(kind);
        if (!special && !"ITEM".equals(kind)) {
            throw new IllegalArgumentException("Loại vật phẩm không hợp lệ");
        }
        User liveUser = SessionManager.findUserById(userId);
        Object lock = liveUser == null ? PlayerAdminService.class : liveUser;
        synchronized (lock) {
            long before;
            long after;
            String itemName;
            try (Connection connection = Server.dbManager.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    requireUser(connection, userId);
                    itemName = readDefinitionName(connection, special, itemId);
                    before = readQuantity(connection, special, userId, itemId);
                    after = Math.addExact(before, amount);
                    long limit = special ? Integer.MAX_VALUE : MAX_ITEM_QUANTITY;
                    if (after < 0 || after > limit) {
                        throw new IllegalArgumentException("Số lượng sau thay đổi phải từ 0 đến " + limit);
                    }
                    writeQuantity(connection, special, userId, itemId, after);
                    insertAudit(connection, adminUsername,
                            special ? "ADJUST_SPECIAL_ITEM" : "ADJUST_ITEM", userId,
                            itemName + " " + signed(amount) + ": " + before + " → " + after,
                            reason, json("quantity", before), json("quantity", after));
                    connection.commit();
                } catch (Exception exception) {
                    rollback(connection, exception);
                    return null;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            syncInventory(liveUser, special, itemId, (int) after, (int) amount);
            return new ValueChange(before, after);
        }
    }

    public static EquipmentChange addEquipment(int userId, int glassId, int equipmentId,
                                               String reason, String adminUsername) throws SQLException {
        requireReason(reason);
        User liveUser = SessionManager.findUserById(userId);
        Object lock = liveUser == null ? PlayerAdminService.class : liveUser;
        synchronized (lock) {
            EquipmentChange created;
            try (Connection connection = Server.dbManager.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    requireUser(connection, userId);
                    created = readEquipmentDefinition(connection, glassId, equipmentId);
                    int dbKey = nextEquipmentKey(connection, userId);
                    long renewalDate = System.currentTimeMillis();
                    created = new EquipmentChange(glassId, equipmentId, dbKey, created.name(),
                            renewalDate);
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO user_equip
                                (user_id, glassID, equipID, level2, inv_ability, inv_percen,
                                 slot, dbKey, isUse, renewalDate)
                            SELECT ?, glassID, id, 0, COALESCE(inv_ability, '[0,0,0,0,0]'),
                                   COALESCE(inv_percen, '[0,0,0,0,0]'), '[-1,-1,-1]', ?, 0, ?
                            FROM equip WHERE glassID = ? AND id = ?
                            """)) {
                        insert.setInt(1, userId);
                        insert.setInt(2, dbKey);
                        insert.setLong(3, renewalDate);
                        insert.setInt(4, glassId);
                        insert.setInt(5, equipmentId);
                        if (insert.executeUpdate() != 1) {
                            throw new IllegalArgumentException("Trang bị không tồn tại");
                        }
                    }
                    insertAudit(connection, adminUsername, "ADD_EQUIPMENT", userId,
                            "Thêm " + created.name() + " (key " + dbKey + ")", reason,
                            null, GSON.toJson(created));
                    connection.commit();
                } catch (Exception exception) {
                    rollback(connection, exception);
                    return null;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            syncAddedEquipment(liveUser, created);
            return created;
        }
    }

    public static EquipmentChange removeEquipment(int userId, int glassId, int equipmentId,
                                                  int dbKey, String reason,
                                                  String adminUsername) throws SQLException {
        requireReason(reason);
        User liveUser = SessionManager.findUserById(userId);
        Object lock = liveUser == null ? PlayerAdminService.class : liveUser;
        synchronized (lock) {
            EquipmentChange removed;
            try (Connection connection = Server.dbManager.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    removed = readOwnedEquipment(connection, userId, glassId, equipmentId, dbKey);
                    try (PreparedStatement delete = connection.prepareStatement("""
                            DELETE FROM user_equip
                            WHERE user_id = ? AND glassID = ? AND equipID = ? AND dbKey = ?
                            """)) {
                        delete.setInt(1, userId);
                        delete.setInt(2, glassId);
                        delete.setInt(3, equipmentId);
                        delete.setInt(4, dbKey);
                        delete.executeUpdate();
                    }
                    insertAudit(connection, adminUsername, "REMOVE_EQUIPMENT", userId,
                            "Xóa " + removed.name() + " (key " + dbKey + ")", reason,
                            GSON.toJson(removed), null);
                    connection.commit();
                } catch (Exception exception) {
                    rollback(connection, exception);
                    return null;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            syncRemovedEquipment(liveUser, glassId, equipmentId, dbKey);
            return removed;
        }
    }

    public static String loginBlockMessage(int userId) throws SQLException {
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT status, reason FROM user_account_state WHERE user_id = ?
                     """)) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || "ACTIVE".equals(result.getString("status"))) {
                    return AdminService.activeBanMessage(userId);
                }
                String status = result.getString("status");
                return "DELETED".equals(status)
                        ? "Tài khoản đã bị xóa: " + result.getString("reason")
                        : "Tài khoản đã bị khóa: " + result.getString("reason");
            }
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Phải nhập lý do thao tác");
        }
        if (reason.trim().length() > 500) {
            throw new IllegalArgumentException("Lý do không được vượt 500 ký tự");
        }
    }

    private static void requireUser(Connection connection, int userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM user WHERE id = ?")) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Không tìm thấy user");
                }
            }
        }
    }

    private static String readAccountStatus(Connection connection, int userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM user_account_state WHERE user_id = ? FOR UPDATE")) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("status") : "ACTIVE";
            }
        }
    }

    private static String readCharacterName(Connection connection, int userId, boolean lock)
            throws SQLException {
        String sql = "SELECT name FROM user_ WHERE user_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Không tìm thấy user");
                }
                return result.getString("name");
            }
        }
    }

    private static long readCup(int userId) throws SQLException {
        try (Connection connection = Server.dbManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT cup FROM user_ WHERE user_id = ?")) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Không tìm thấy user");
                }
                return result.getLong("cup");
            }
        }
    }

    private static CharacterChange readCharacter(Connection connection, int userId, int glassId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT exp, level, point, ability FROM user_glass
                WHERE user_id = ? AND glassID = ? FOR UPDATE
                """)) {
            statement.setInt(1, userId);
            statement.setInt(2, glassId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("User chưa mở nhân vật này");
                }
                return new CharacterChange(result.getInt("exp"), result.getInt("level"),
                        result.getInt("point"), result.getString("ability"));
            }
        }
    }

    private static int[] parseAbility(String json) {
        try {
            int[] values = GSON.fromJson(json, int[].class);
            if (values == null || values.length != 5) {
                throw new IllegalArgumentException("Ability phải là mảng gồm đúng 5 số");
            }
            for (int value : values) {
                if (value < 0 || value > MAX_ABILITY) {
                    throw new IllegalArgumentException(
                            "Mỗi chỉ số ability phải từ 0 đến " + MAX_ABILITY);
                }
            }
            return values;
        } catch (JsonSyntaxException exception) {
            throw new IllegalArgumentException("Ability phải là JSON hợp lệ, ví dụ [0,0,10,10,10]");
        }
    }

    private static String readDefinitionName(Connection connection, boolean special, int itemId)
            throws SQLException {
        String table = special ? "linhtinh" : "item";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM " + table + " WHERE id = ?")) {
            statement.setInt(1, itemId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Vật phẩm không tồn tại");
                }
                return result.getString("name");
            }
        }
    }

    private static long readQuantity(Connection connection, boolean special, int userId, int itemId)
            throws SQLException {
        String table = special ? "user_linhtinh" : "user_item";
        String column = special ? "linhtinh_id" : "item_id";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT num FROM " + table + " WHERE user_id = ? AND " + column + " = ? FOR UPDATE")) {
            statement.setInt(1, userId);
            statement.setInt(2, itemId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong("num") : 0;
            }
        }
    }

    private static void writeQuantity(Connection connection, boolean special, int userId, int itemId,
                                      long quantity) throws SQLException {
        String table = special ? "user_linhtinh" : "user_item";
        String column = special ? "linhtinh_id" : "item_id";
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table + " (user_id, " + column + ", num) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE num = VALUES(num)")) {
            statement.setInt(1, userId);
            statement.setInt(2, itemId);
            statement.setLong(3, quantity);
            statement.executeUpdate();
        }
    }

    private static EquipmentChange readEquipmentDefinition(Connection connection, int glassId,
                                                            int equipmentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(NULLIF(name, ''), CONCAT('Trang bị #', glassID, ':', id)) AS name "
                        + "FROM equip WHERE glassID = ? AND id = ?")) {
            statement.setInt(1, glassId);
            statement.setInt(2, equipmentId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Trang bị không tồn tại");
                }
                return new EquipmentChange(glassId, equipmentId, 0,
                        result.getString("name"), 0);
            }
        }
    }

    private static EquipmentChange readOwnedEquipment(Connection connection, int userId, int glassId,
                                                       int equipmentId, int dbKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(NULLIF(e.name, ''),
                                CONCAT('Trang bị #', ue.glassID, ':', ue.equipID)) AS name,
                       ue.renewalDate
                FROM user_equip ue
                LEFT JOIN equip e ON e.glassID = ue.glassID AND e.id = ue.equipID
                WHERE ue.user_id = ? AND ue.glassID = ? AND ue.equipID = ? AND ue.dbKey = ?
                FOR UPDATE
                """)) {
            statement.setInt(1, userId);
            statement.setInt(2, glassId);
            statement.setInt(3, equipmentId);
            statement.setInt(4, dbKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Không tìm thấy trang bị của user");
                }
                return new EquipmentChange(glassId, equipmentId, dbKey,
                        result.getString("name"), result.getLong("renewalDate"));
            }
        }
    }

    private static int nextEquipmentKey(Connection connection, int userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(dbKey), 0) + 1 AS next_key FROM user_equip WHERE user_id = ? FOR UPDATE")) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt("next_key");
            }
        }
    }

    private static void syncInventory(User user, boolean special, int itemId, int quantity, int delta) {
        if (user == null) {
            return;
        }
        if (special) {
            LinhTinh value = user.getLinhTinh(itemId);
            if (value == null) {
                LinhTinh definition = LinhTinh.get(itemId);
                if (definition != null) {
                    value = definition.deepCopy();
                    user.linhtinhs.add(value);
                }
            }
            if (value != null) {
                value.num = quantity;
                if (user.session != null && user.session.connected) {
                    if (delta > 0 && delta <= 127) {
                        user.session.sessionHandler.addLinhTinh(value, delta);
                    } else if (delta < 0 && -delta <= 127) {
                        user.session.sessionHandler.downLinhTinh(itemId, -delta);
                    } else {
                        user.session.requestDisconnect();
                    }
                }
            }
        } else {
            Item value = user.getItem(itemId);
            if (value == null) {
                Item definition = Item.get(itemId);
                if (definition != null) {
                    value = definition.deepCopy();
                    user.items.add(value);
                }
            }
            if (value != null) {
                value.num = quantity;
                if (user.session != null && user.session.connected) {
                    user.session.sessionHandler.updateItem(value, (int) user.xu, (int) user.luong);
                }
            }
        }
    }

    private static void syncAddedEquipment(User user, EquipmentChange change) {
        if (user == null) {
            return;
        }
        Equip definition = Equip.get((byte) change.glassId(), (short) change.equipmentId());
        if (definition == null) {
            return;
        }
        Equip equip = definition.deepCopy();
        equip.level2 = 0;
        equip.inv_ability = definition.inv_ability == null ? new byte[5] : definition.inv_ability.clone();
        equip.inv_percen = definition.inv_percen == null ? new byte[5] : definition.inv_percen.clone();
        equip.slot = new short[]{-1, -1, -1};
        equip.dbKey = change.databaseKey();
        equip.isUse = false;
        equip.renewalDate = change.renewalDate();
        user.equips.add(equip);
        if (user.session != null && user.session.connected) {
            user.session.sessionHandler.addEquip(equip);
        }
    }

    private static void syncRemovedEquipment(User user, int glassId, int equipmentId, int dbKey) {
        if (user == null) {
            return;
        }
        Equip found = null;
        for (Equip equip : user.equips) {
            if (equip.glassID == glassId && equip.id == equipmentId && equip.dbKey == dbKey) {
                found = equip;
                break;
            }
        }
        if (found != null) {
            user.equips.remove(found);
            Glass glass = user.getGlass((byte) glassId);
            if (glass != null) {
                glass.updateAll();
            }
            if (user.session != null && user.session.connected) {
                user.session.sessionHandler.removeEquip(found);
            }
        }
    }

    private static void refreshOnline(User user) {
        if (user.session != null && user.session.connected) {
            user.session.sessionHandler.loadInfoAll();
        }
    }

    private static void disconnect(int userId) {
        User user = SessionManager.findUserById(userId);
        if (user != null && user.session != null && user.session.connected) {
            user.session.requestDisconnect();
        }
    }

    private static void insertAudit(Connection connection, String adminUsername, String action,
                                    int userId, String detail, String reason,
                                    String beforeData, String afterData) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO admin_audit_log
                    (admin_username, action, target_user_id, detail, reason,
                     before_data, after_data, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, adminUsername);
            statement.setString(2, action);
            statement.setInt(3, userId);
            statement.setString(4, detail);
            statement.setString(5, reason.trim());
            statement.setString(6, beforeData);
            statement.setString(7, afterData);
            statement.setString(8, UUID.randomUUID().toString());
            statement.executeUpdate();
        }
    }

    private static String json(String key, Object value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key, value);
        return GSON.toJson(values);
    }

    private static String signed(long value) {
        return (value > 0 ? "+" : "") + value;
    }

    private static String stateAction(String status) {
        return switch (status) {
            case "LOCKED" -> "Khóa tài khoản";
            case "DELETED" -> "Xóa mềm tài khoản";
            default -> "Khôi phục/mở khóa tài khoản";
        };
    }

    private static void rollback(Connection connection, Exception exception) throws SQLException {
        connection.rollback();
        if (exception instanceof SQLException sqlException) {
            throw sqlException;
        }
        if (exception instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new SQLException(exception);
    }

    public record ValueChange(long before, long after) {
    }

    public record RenameResult(String before, String after) {
    }

    public record CharacterChange(int exp, int level, int point, String ability) {
    }

    public record EquipmentChange(int glassId, int equipmentId, int databaseKey,
                                  String name, long renewalDate) {
    }
}
