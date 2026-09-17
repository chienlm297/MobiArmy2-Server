package mobiarmy.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public final class AdminServer {

    private static final String SESSION_COOKIE = "mobiarmy_admin";
    private static final long SESSION_TTL_MILLIS = Duration.ofHours(8).toMillis();
    private static final Set<String> USER_TABS = Set.of(
            "overview", "characters", "equipment", "inventory", "missions", "friends", "history");

    private final HttpServer server;
    private final String adminUsername;
    private final String adminPassword;
    private final ConcurrentHashMap<String, AdminSession> sessions = new ConcurrentHashMap<>();

    private AdminServer(String host, int port, String adminUsername, String adminPassword)
            throws IOException {
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "mobiarmy-admin-http");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public static AdminServer startIfEnabled() throws IOException {
        if (!Boolean.parseBoolean(env("ADMIN_ENABLED", "false"))) {
            return null;
        }
        String password = env("ADMIN_PASSWORD", "");
        if (password.isBlank()) {
            throw new IllegalStateException("ADMIN_PASSWORD is required when ADMIN_ENABLED=true");
        }
        String host = env("ADMIN_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("ADMIN_PORT", "8080"));
        AdminServer adminServer = new AdminServer(
                host, port, env("ADMIN_USERNAME", "admin"), password);
        adminServer.server.start();
        System.out.println("Web admin: http://" + host + ":" + port);
        return adminServer;
    }

    public void stop() {
        server.stop(1);
        sessions.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        AdminSession session = null;
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("/login".equals(path)) {
                if ("GET".equals(method)) {
                    sendHtml(exchange, 200, AdminView.login(null));
                } else if ("POST".equals(method)) {
                    login(exchange);
                } else {
                    methodNotAllowed(exchange);
                }
                return;
            }

            session = authenticate(exchange);
            if (session == null) {
                redirect(exchange, "/login");
                return;
            }

            if ("/logout".equals(path) && "POST".equals(method)) {
                logout(exchange, session);
                return;
            }
            if (("/".equals(path) || "/admin".equals(path)) && "GET".equals(method)) {
                dashboard(exchange, session);
                return;
            }
            if ("/admin/users/new".equals(path) && "GET".equals(method)) {
                sendHtml(exchange, 200, AdminView.createUser(null, Map.of(),
                        session.csrf(), session.username()));
                return;
            }
            if ("/admin/users".equals(path) && "POST".equals(method)) {
                createUser(exchange, session);
                return;
            }
            if (path.matches("/admin/users/\\d+") && "GET".equals(method)) {
                userDetail(exchange, session, path);
                return;
            }
            if (path.startsWith("/admin/users/") && "POST".equals(method)) {
                userAction(exchange, session, path);
                return;
            }

            sendHtml(exchange, 404,
                    AdminView.error("Không tìm thấy", "Đường dẫn không tồn tại.", true, session.csrf()));
        } catch (IllegalArgumentException exception) {
            sendHtml(exchange, 400, AdminView.error("Dữ liệu không hợp lệ",
                    exception.getMessage(), session != null, session == null ? null : session.csrf()));
        } catch (Exception exception) {
            exception.printStackTrace();
            sendHtml(exchange, 500, AdminView.error("Lỗi server",
                    "Xem log server để biết chi tiết.", session != null,
                    session == null ? null : session.csrf()));
        } finally {
            exchange.close();
        }
    }

    private void login(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        if (!secureEquals(adminUsername, form.get("username"))
                || !secureEquals(adminPassword, form.get("password"))) {
            sendHtml(exchange, 401, AdminView.login("Sai tài khoản hoặc mật khẩu"));
            return;
        }
        String token = UUID.randomUUID().toString();
        AdminSession session = new AdminSession(token, adminUsername, UUID.randomUUID().toString(),
                System.currentTimeMillis() + SESSION_TTL_MILLIS);
        sessions.put(token, session);
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800");
        redirect(exchange, "/admin");
    }

    private void logout(HttpExchange exchange, AdminSession session) throws IOException {
        Map<String, String> form = readForm(exchange);
        requireCsrf(session, form);
        sessions.remove(session.token());
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        redirect(exchange, "/login");
    }

    private void dashboard(HttpExchange exchange, AdminSession session) throws IOException, SQLException {
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String search = query.getOrDefault("q", "");
        sendHtml(exchange, 200, AdminView.dashboard(
                AdminService.dashboard(), AdminService.findUsers(search), AdminService.recentAudit(),
                search, query.get("message"), session.csrf(), session.username()));
    }

    private void userDetail(HttpExchange exchange, AdminSession session, String path)
            throws IOException, SQLException {
        int userId = parseUserId(path);
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String tab = query.getOrDefault("tab", "overview");
        if (!USER_TABS.contains(tab)) {
            tab = "overview";
        }
        AdminService.UserDetail detail = AdminService.getUserDetail(userId);
        if (detail == null) {
            sendHtml(exchange, 404, AdminView.error("Không tìm thấy người chơi",
                    "Không có tài khoản mang ID " + userId + ".", true, session.csrf()));
            return;
        }
        sendHtml(exchange, 200, AdminView.userDetail(detail, tab, query.get("message"),
                session.csrf(), session.username()));
    }

    private void createUser(HttpExchange exchange, AdminSession session)
            throws IOException, SQLException {
        Map<String, String> form = readForm(exchange);
        requireCsrf(session, form);
        try {
            long initialXu = Long.parseLong(form.getOrDefault("initial_xu", "1000"));
            long initialLuong = Long.parseLong(form.getOrDefault("initial_luong", "1000"));
            AdminService.CreateUserResult result = AdminService.createUser(
                    form.get("username"), form.get("password"), form.get("confirm_password"),
                    form.get("character_name"), initialXu, initialLuong,
                    form.get("reason"), session.username());
            String message = "Đã tạo tài khoản " + result.username()
                    + " với ID " + result.userId();
            redirect(exchange, "/admin/users/" + result.userId()
                    + "?message=" + AdminView.url(message));
        } catch (NumberFormatException exception) {
            sendHtml(exchange, 400, AdminView.createUser(
                    "Xu và lượng phải là số nguyên", form, session.csrf(), session.username()));
        } catch (IllegalArgumentException exception) {
            sendHtml(exchange, 400, AdminView.createUser(
                    exception.getMessage(), form, session.csrf(), session.username()));
        }
    }

    private void userAction(HttpExchange exchange, AdminSession session, String path)
            throws IOException, SQLException {
        String[] parts = path.split("/");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Đường dẫn không hợp lệ");
        }
        int userId = Integer.parseInt(parts[3]);
        String action = parts[4];
        Map<String, String> form = readForm(exchange);
        requireCsrf(session, form);
        String message;
        switch (action) {
            case "wallet" -> {
                String currency = form.get("currency");
                long amount = Long.parseLong(form.getOrDefault("amount", "0"));
                AdminService.WalletResult result = AdminService.adjustWallet(
                        userId, currency, amount, form.get("reason"), session.username());
                message = "Đã cập nhật " + currency + " user " + userId + ": "
                        + result.before() + " → " + result.after();
            }
            case "kick" -> {
                boolean kicked = AdminService.kick(userId, form.get("reason"), session.username());
                message = kicked ? "Đã kick user " + userId : "User " + userId + " đang offline";
            }
            case "ban" -> {
                long minutes = Long.parseLong(form.getOrDefault("minutes", "0"));
                AdminService.ban(userId, form.get("reason"), minutes, session.username());
                message = "Đã ban user " + userId;
            }
            case "unban" -> {
                AdminService.unban(userId, form.get("reason"), session.username());
                message = "Đã gỡ ban user " + userId;
            }
            case "password" -> {
                PlayerAdminService.resetPassword(userId, form.get("password"),
                        form.get("confirm_password"), form.get("reason"), session.username());
                message = "Đã đặt lại mật khẩu user " + userId;
            }
            case "lock" -> {
                PlayerAdminService.changeAccountState(userId, "LOCKED", form.get("reason"),
                        session.username());
                message = "Đã khóa đăng nhập user " + userId;
            }
            case "unlock", "restore" -> {
                PlayerAdminService.changeAccountState(userId, "ACTIVE", form.get("reason"),
                        session.username());
                message = "Đã mở khóa/khôi phục user " + userId;
            }
            case "delete" -> {
                PlayerAdminService.changeAccountState(userId, "DELETED", form.get("reason"),
                        session.username());
                message = "Đã xóa mềm user " + userId;
            }
            case "rename" -> {
                PlayerAdminService.RenameResult result = PlayerAdminService.renameCharacter(
                        userId, form.get("new_name"), form.get("reason"), session.username());
                message = "Đã đổi tên " + result.before() + " → " + result.after();
            }
            case "cup" -> {
                long amount = Long.parseLong(form.getOrDefault("amount", "0"));
                PlayerAdminService.ValueChange result = PlayerAdminService.adjustCup(
                        userId, amount, form.get("reason"), session.username());
                message = "Đã cập nhật cup: " + result.before() + " → " + result.after();
            }
            case "character" -> {
                int glassId = Integer.parseInt(form.getOrDefault("glass_id", "-1"));
                int exp = Integer.parseInt(form.getOrDefault("exp", "-1"));
                int point = Integer.parseInt(form.getOrDefault("point", "-1"));
                PlayerAdminService.CharacterChange result = PlayerAdminService.updateCharacter(
                        userId, glassId, exp, point, form.get("ability"), form.get("reason"),
                        session.username());
                message = "Đã cập nhật nhân vật #" + glassId + " lên cấp " + result.level();
            }
            case "inventory" -> {
                int itemId = Integer.parseInt(form.getOrDefault("item_id", "-1"));
                long amount = Long.parseLong(form.getOrDefault("amount", "0"));
                PlayerAdminService.ValueChange result = PlayerAdminService.adjustInventory(
                        userId, form.get("kind"), itemId, amount, form.get("reason"),
                        session.username());
                message = "Đã cập nhật vật phẩm #" + itemId + ": "
                        + result.before() + " → " + result.after();
            }
            case "equipment-add" -> {
                String[] key = form.getOrDefault("equipment_key", "").split(":", 2);
                if (key.length != 2) {
                    throw new IllegalArgumentException("Phải chọn trang bị cần thêm");
                }
                int glassId = Integer.parseInt(key[0]);
                int equipmentId = Integer.parseInt(key[1]);
                PlayerAdminService.EquipmentChange result = PlayerAdminService.addEquipment(
                        userId, glassId, equipmentId, form.get("reason"), session.username());
                message = "Đã thêm " + result.name() + " (key " + result.databaseKey() + ")";
            }
            case "equipment-remove" -> {
                int glassId = Integer.parseInt(form.getOrDefault("glass_id", "-1"));
                int equipmentId = Integer.parseInt(form.getOrDefault("equipment_id", "-1"));
                int databaseKey = Integer.parseInt(form.getOrDefault("database_key", "-1"));
                PlayerAdminService.EquipmentChange result = PlayerAdminService.removeEquipment(
                        userId, glassId, equipmentId, databaseKey, form.get("reason"),
                        session.username());
                message = "Đã xóa " + result.name() + " (key " + databaseKey + ")";
            }
            default -> throw new IllegalArgumentException("Action không hợp lệ");
        }
        String next = safeReturnPath(form.get("next"));
        redirect(exchange, next + (next.contains("?") ? "&" : "?")
                + "message=" + AdminView.url(message));
    }

    private static int parseUserId(String path) {
        String[] parts = path.split("/");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Đường dẫn không hợp lệ");
        }
        return Integer.parseInt(parts[3]);
    }

    private static String safeReturnPath(String value) {
        if (value != null && value.matches("/admin/users/\\d+(\\?tab=[a-z]+)?")) {
            return value;
        }
        return "/admin";
    }

    private AdminSession authenticate(HttpExchange exchange) {
        String token = cookie(exchange, SESSION_COOKIE);
        if (token == null) {
            return null;
        }
        AdminSession session = sessions.get(token);
        if (session == null || session.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return session;
    }

    private static void requireCsrf(AdminSession session, Map<String, String> form) {
        if (!secureEquals(session.csrf(), form.get("csrf"))) {
            throw new IllegalArgumentException("CSRF token không hợp lệ");
        }
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(64 * 1024 + 1);
        if (bytes.length > 64 * 1024) {
            throw new IllegalArgumentException("Request quá lớn");
        }
        return parseForm(new String(bytes, StandardCharsets.UTF_8));
    }

    private static Map<String, String> parseQuery(URI uri) {
        return parseForm(uri.getRawQuery() == null ? "" : uri.getRawQuery());
    }

    private static Map<String, String> parseForm(String value) {
        HashMap<String, String> result = new HashMap<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String pair : value.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String item = parts.length == 1 ? ""
                    : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            result.put(key, item);
        }
        return result;
    }

    private static String cookie(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String header : headers) {
            for (String part : header.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && name.equals(pair[0])) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    private static void sendHtml(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
    }

    private static void methodNotAllowed(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Allow", "GET, POST");
        exchange.sendResponseHeaders(405, -1);
    }

    private static boolean secureEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record AdminSession(String token, String username, String csrf, long expiresAt) {
    }
}
