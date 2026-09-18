package mobiarmy.admin;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import mobiarmy.server.Server;
import org.mindrot.jbcrypt.BCrypt;

final class AdminAccounts {
    enum Role {
        OWNER, ADMIN, MODERATOR, VIEWER;
        boolean allows(String path) {
            if (path.equals("/logout")) return true;
            if (path.startsWith("/admin/accounts")) return this == OWNER;
            if (path.equals("/admin/broadcast") || path.equals("/admin/bots")) return this == OWNER || this == ADMIN;
            if (path.equals("/admin/users") || path.equals("/admin/users/new")) return this == OWNER || this == ADMIN;
            if (path.matches("/admin/users/\\d+/[a-z-]+")) {
                String action = path.substring(path.lastIndexOf('/') + 1);
                if (!Set.of("wallet", "kick", "ban", "unban", "password", "lock", "unlock",
                        "restore", "delete", "rename", "cup", "character", "inventory",
                        "equipment-add", "equipment-remove").contains(action)) return false;
                return this == OWNER || this == ADMIN || (this == MODERATOR &&
                        Set.of("kick", "ban", "unban", "lock", "unlock").contains(action));
            }
            return false;
        }
    }
    record Account(int id, String username, Role role, boolean enabled, int version) {}

    static void initialize(String username, String password) throws SQLException {
        try (Connection c = Server.dbManager.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS admin_account (
                    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    username VARCHAR(32) NOT NULL UNIQUE,
                    password_hash VARCHAR(100) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    session_version INT NOT NULL DEFAULT 1,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM admin_account")) {
                rs.next();
                if (rs.getInt(1) != 0) return;
            }
            validateUsername(username);
            validatePassword(password);
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO admin_account (username,password_hash,role) VALUES (?,?,'OWNER')")) {
                p.setString(1, username); p.setString(2, BCrypt.hashpw(password, BCrypt.gensalt(12)));
                p.executeUpdate();
            }
        }
    }

    static Account login(String username, String password) throws SQLException {
        if (username == null || password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) return null;
        try (Connection c = Server.dbManager.getConnection(); PreparedStatement p = c.prepareStatement(
                "SELECT * FROM admin_account WHERE username=? AND enabled=TRUE")) {
            p.setString(1, username);
            try (ResultSet r = p.executeQuery()) {
                return r.next() && BCrypt.checkpw(password, r.getString("password_hash")) ? account(r) : null;
            }
        }
    }

    static Account get(int id) throws SQLException {
        try (Connection c = Server.dbManager.getConnection(); PreparedStatement p = c.prepareStatement(
                "SELECT * FROM admin_account WHERE id=?")) {
            p.setInt(1, id);
            try (ResultSet r = p.executeQuery()) { return r.next() ? account(r) : null; }
        }
    }

    static List<Account> list() throws SQLException {
        List<Account> rows = new ArrayList<>();
        try (Connection c = Server.dbManager.getConnection(); Statement p = c.createStatement();
             ResultSet r = p.executeQuery("SELECT * FROM admin_account ORDER BY id")) {
            while (r.next()) rows.add(account(r));
        }
        return rows;
    }

    static synchronized void save(Account actor, Map<String,String> form) throws SQLException {
        String reason = form.getOrDefault("reason", "").trim();
        if (reason.isEmpty() || reason.length() > 500) throw new IllegalArgumentException("Nhập lý do, tối đa 500 ký tự");
        int id = Integer.parseInt(form.getOrDefault("id", "0"));
        Role role = Role.valueOf(form.getOrDefault("role", "VIEWER"));
        boolean enabled = "true".equals(form.get("enabled"));
        String password = form.getOrDefault("password", "");
        if (id == 0 || !password.isEmpty()) validatePassword(password);
        String hash = password.isEmpty() ? null : BCrypt.hashpw(password, BCrypt.gensalt(12));
        try (Connection c = Server.dbManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                List<Account> accounts = new ArrayList<>();
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM admin_account ORDER BY id FOR UPDATE")) {
                    while (r.next()) accounts.add(account(r));
                }
                Account currentActor = accounts.stream().filter(a -> a.id() == actor.id()).findFirst().orElseThrow();
                if (!currentActor.enabled() || currentActor.role() != Role.OWNER || currentActor.version() != actor.version())
                    throw new SecurityException("Quyền quản trị đã thay đổi, hãy đăng nhập lại");
                String detail;
                if (id == 0) {
                    String username = form.getOrDefault("username", "").trim();
                    validateUsername(username);
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO admin_account (username,password_hash,role,enabled) VALUES (?,?,?,?)")) {
                        p.setString(1, username); p.setString(2, hash); p.setString(3, role.name()); p.setBoolean(4, enabled); p.executeUpdate();
                    }
                    detail = "Tạo admin " + username + " / " + role;
                } else {
                    Account before = accounts.stream().filter(a -> a.id() == id).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy admin"));
                    long owners = accounts.stream().filter(a -> a.enabled() && a.role() == Role.OWNER).count();
                    if (before.enabled() && before.role() == Role.OWNER && (!enabled || role != Role.OWNER) && owners <= 1)
                        throw new IllegalArgumentException("Phải giữ ít nhất một OWNER đang hoạt động");
                    try (PreparedStatement p = c.prepareStatement("UPDATE admin_account SET role=?,enabled=?,password_hash=COALESCE(?,password_hash),session_version=session_version+1 WHERE id=?")) {
                        p.setString(1, role.name()); p.setBoolean(2, enabled); p.setString(3, hash); p.setInt(4, id); p.executeUpdate();
                    }
                    detail = "Admin " + before.username() + ": " + before.role() + "/" + before.enabled()
                            + " → " + role + "/" + enabled + (hash == null ? "" : " / đổi mật khẩu");
                }
                AdminService.insertAudit(c, actor.username(), "ADMIN_ACCOUNT", null, detail, reason, null, null);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SQLIntegrityConstraintViolationException)
                    throw new IllegalArgumentException("Tên admin đã tồn tại");
                throw e;
            }
        }
    }

    private static Account account(ResultSet r) throws SQLException {
        return new Account(r.getInt("id"), r.getString("username"), Role.valueOf(r.getString("role")),
                r.getBoolean("enabled"), r.getInt("session_version"));
    }
    private static void validateUsername(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{3,32}"))
            throw new IllegalArgumentException("Tên admin phải gồm 3–32 chữ, số hoặc dấu gạch dưới");
    }
    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Mật khẩu admin tối thiểu 8 ký tự, tối đa 72 byte");
    }
}
