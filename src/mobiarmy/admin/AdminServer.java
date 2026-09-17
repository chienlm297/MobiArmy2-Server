package mobiarmy.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public final class AdminServer {

    private static final String SESSION_COOKIE = "mobiarmy_admin";
    private static final long SESSION_TTL_MILLIS = Duration.ofHours(8).toMillis();

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
                host,
                port,
                env("ADMIN_USERNAME", "admin"),
                password
        );
        adminServer.server.start();
        System.out.println("Web admin: http://" + host + ":" + port);
        return adminServer;
    }

    public void stop() {
        server.stop(1);
        sessions.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("/login".equals(path)) {
                if ("GET".equals(method)) {
                    sendHtml(exchange, 200, loginPage(null));
                } else if ("POST".equals(method)) {
                    login(exchange);
                } else {
                    methodNotAllowed(exchange);
                }
                return;
            }

            AdminSession session = authenticate(exchange);
            if (session == null) {
                redirect(exchange, "/login");
                return;
            }

            if ("/logout".equals(path) && "POST".equals(method)) {
                Map<String, String> form = readForm(exchange);
                requireCsrf(session, form);
                sessions.remove(session.token());
                exchange.getResponseHeaders().add("Set-Cookie",
                        SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                redirect(exchange, "/login");
                return;
            }

            if (("/".equals(path) || "/admin".equals(path)) && "GET".equals(method)) {
                dashboard(exchange, session);
                return;
            }

            if (path.startsWith("/admin/users/") && "POST".equals(method)) {
                userAction(exchange, session, path);
                return;
            }

            sendHtml(exchange, 404, layout("Không tìm thấy", "<h1>404</h1>", session));
        } catch (IllegalArgumentException exception) {
            sendHtml(exchange, 400, errorPage("Dữ liệu không hợp lệ", exception.getMessage()));
        } catch (Exception exception) {
            exception.printStackTrace();
            sendHtml(exchange, 500, errorPage("Lỗi server", "Xem log server để biết chi tiết."));
        } finally {
            exchange.close();
        }
    }

    private void login(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        if (!secureEquals(adminUsername, form.get("username"))
                || !secureEquals(adminPassword, form.get("password"))) {
            sendHtml(exchange, 401, loginPage("Sai tài khoản hoặc mật khẩu"));
            return;
        }
        String token = UUID.randomUUID().toString();
        AdminSession session = new AdminSession(
                token,
                adminUsername,
                UUID.randomUUID().toString(),
                System.currentTimeMillis() + SESSION_TTL_MILLIS
        );
        sessions.put(token, session);
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800");
        redirect(exchange, "/admin");
    }

    private void dashboard(HttpExchange exchange, AdminSession session) throws IOException, SQLException {
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String search = query.getOrDefault("q", "");
        String message = query.get("message");
        AdminService.Dashboard dashboard = AdminService.dashboard();
        List<AdminService.UserSummary> users = AdminService.findUsers(search);
        List<AdminService.AuditEntry> audit = AdminService.recentAudit();

        StringBuilder body = new StringBuilder();
        body.append("<div class='top'><div><h1>MobiArmy Admin</h1><p>Quản trị game server</p></div>")
                .append("<form method='post' action='/logout'><input type='hidden' name='csrf' value='")
                .append(html(session.csrf())).append("'><button class='secondary'>Đăng xuất</button></form></div>");
        if (message != null && !message.isBlank()) {
            body.append("<div class='notice'>").append(html(message)).append("</div>");
        }
        long uptime = Math.max(0, System.currentTimeMillis() - dashboard.startedAt());
        body.append("<div class='cards'>")
                .append(card("Uptime", formatDuration(uptime)))
                .append(card("TCP sessions", Integer.toString(dashboard.sessions())))
                .append(card("Online", Integer.toString(dashboard.onlineUsers())))
                .append(card("User đã load", Integer.toString(dashboard.loadedUsers())))
                .append("</div>");

        body.append("<section><div class='section-title'><h2>Người chơi</h2>")
                .append("<form method='get' action='/admin' class='search'>")
                .append("<input name='q' value='").append(html(search))
                .append("' placeholder='ID, username hoặc tên nhân vật'>")
                .append("<button>Tìm</button></form></div>")
                .append("<div class='table-wrap'><table><thead><tr>")
                .append("<th>ID</th><th>Tài khoản</th><th>Nhân vật</th><th>Trạng thái</th>")
                .append("<th>Xu</th><th>Lượng</th><th>Cup</th><th>Thao tác</th>")
                .append("</tr></thead><tbody>");
        for (AdminService.UserSummary user : users) {
            body.append("<tr><td>").append(user.id()).append("</td><td>")
                    .append(html(user.username())).append("</td><td>").append(html(user.name()))
                    .append("</td><td>");
            if (user.banned()) {
                body.append("<span class='badge danger'>Banned</span>");
            } else if (user.online()) {
                body.append("<span class='badge online'>Online</span>");
            } else {
                body.append("<span class='badge'>Offline</span>");
            }
            body.append("</td><td>").append(user.xu()).append("</td><td>")
                    .append(user.luong()).append("</td><td>").append(user.cup()).append("</td><td>")
                    .append(actions(user, session)).append("</td></tr>");
        }
        body.append("</tbody></table></div></section>");

        body.append("<section><h2>Audit log gần nhất</h2><div class='table-wrap'><table><thead><tr>")
                .append("<th>Thời gian</th><th>Admin</th><th>Action</th><th>User</th><th>Chi tiết</th>")
                .append("</tr></thead><tbody>");
        for (AdminService.AuditEntry entry : audit) {
            body.append("<tr><td>").append(html(String.valueOf(entry.createdAt())))
                    .append("</td><td>").append(html(entry.adminUsername()))
                    .append("</td><td>").append(html(entry.action()))
                    .append("</td><td>").append(entry.targetUserId() == null ? "" : entry.targetUserId())
                    .append("</td><td>").append(html(entry.detail())).append("</td></tr>");
        }
        body.append("</tbody></table></div></section>");
        sendHtml(exchange, 200, layout("MobiArmy Admin", body.toString(), session));
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
                AdminService.unban(userId, session.username());
                message = "Đã gỡ ban user " + userId;
            }
            default -> throw new IllegalArgumentException("Action không hợp lệ");
        }
        redirect(exchange, "/admin?message=" + url(message));
    }

    private String actions(AdminService.UserSummary user, AdminSession session) {
        String csrf = html(session.csrf());
        String base = "/admin/users/" + user.id();
        StringBuilder html = new StringBuilder("<details><summary>Quản trị</summary><div class='actions'>");
        html.append("<form method='post' action='").append(base).append("/wallet'>")
                .append("<input type='hidden' name='csrf' value='").append(csrf).append("'>")
                .append("<select name='currency'><option value='xu'>Xu</option><option value='luong'>Lượng</option></select>")
                .append("<input type='number' name='amount' placeholder='+/- số tiền' required>")
                .append("<input name='reason' placeholder='Lý do' required><button>Cập nhật</button></form>");
        html.append("<form method='post' action='").append(base).append("/kick'>")
                .append("<input type='hidden' name='csrf' value='").append(csrf).append("'>")
                .append("<input name='reason' placeholder='Lý do kick'><button class='warning'>Kick</button></form>");
        if (user.banned()) {
            html.append("<form method='post' action='").append(base).append("/unban'>")
                    .append("<input type='hidden' name='csrf' value='").append(csrf).append("'>")
                    .append("<button class='secondary'>Unban</button></form>");
        } else {
            html.append("<form method='post' action='").append(base).append("/ban'>")
                    .append("<input type='hidden' name='csrf' value='").append(csrf).append("'>")
                    .append("<input type='number' min='0' name='minutes' value='0' title='0 = vĩnh viễn'>")
                    .append("<input name='reason' placeholder='Lý do ban' required><button class='danger-button'>Ban</button></form>");
        }
        return html.append("</div></details>").toString();
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

    private String loginPage(String error) {
        String message = error == null ? "" : "<div class='error'>" + html(error) + "</div>";
        return """
                <!doctype html><html lang='vi'><head><meta charset='utf-8'>
                <meta name='viewport' content='width=device-width,initial-scale=1'>
                <title>Đăng nhập MobiArmy Admin</title><style>%s</style></head>
                <body class='login-body'><main class='login-card'><h1>MobiArmy Admin</h1>
                <p>Đăng nhập để quản trị server</p>%s
                <form method='post' action='/login'><label>Tài khoản</label>
                <input name='username' autocomplete='username' required><label>Mật khẩu</label>
                <input type='password' name='password' autocomplete='current-password' required>
                <button>Đăng nhập</button></form></main></body></html>
                """.formatted(css(), message);
    }

    private static String layout(String title, String body, AdminSession session) {
        return "<!doctype html><html lang='vi'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + html(title) + "</title><style>" + css() + "</style></head>"
                + "<body><main class='container'>" + body + "</main></body></html>";
    }

    private static String errorPage(String title, String message) {
        return "<!doctype html><html lang='vi'><head><meta charset='utf-8'><title>"
                + html(title) + "</title><style>" + css() + "</style></head><body class='login-body'>"
                + "<main class='login-card'><h1>" + html(title) + "</h1><p>" + html(message)
                + "</p><a href='/admin'>Quay lại</a></main></body></html>";
    }

    private static String card(String label, String value) {
        return "<div class='card'><span>" + html(label) + "</span><strong>" + html(value) + "</strong></div>";
    }

    private static String css() {
        return """
                :root{font-family:Inter,system-ui,sans-serif;color:#172033;background:#f3f6fb}
                *{box-sizing:border-box}body{margin:0}.container{max-width:1500px;margin:auto;padding:28px}
                h1,h2{margin:0 0 10px}.top,.section-title{display:flex;justify-content:space-between;align-items:center;gap:16px}
                .cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin:24px 0}
                .card,section,.login-card{background:white;border:1px solid #dce3ee;border-radius:12px;box-shadow:0 4px 18px #1b31500d}
                .card{padding:18px}.card span{display:block;color:#657085}.card strong{display:block;font-size:28px;margin-top:8px}
                section{padding:20px;margin:18px 0}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;min-width:950px}
                th,td{padding:11px;border-bottom:1px solid #e6ebf2;text-align:left;vertical-align:top}th{color:#566177;font-size:13px}
                input,select,button{font:inherit;border:1px solid #cbd4e1;border-radius:7px;padding:8px 10px}
                button{cursor:pointer;background:#2459d3;color:white;border-color:#2459d3;font-weight:600}
                button.secondary{background:#64748b;border-color:#64748b}.warning{background:#d97706;border-color:#d97706}
                .danger-button{background:#c62828;border-color:#c62828}.search,.actions form{display:flex;gap:7px;flex-wrap:wrap}
                .actions{display:grid;gap:10px;padding-top:10px;min-width:360px}details summary{cursor:pointer;color:#2459d3}
                .badge{display:inline-block;padding:4px 8px;background:#e8edf5;border-radius:99px;font-size:12px}
                .badge.online{background:#d9f6e5;color:#087a3d}.badge.danger{background:#ffe0e0;color:#a11616}
                .notice,.error{padding:12px;border-radius:8px;margin:12px 0}.notice{background:#dce9ff}.error{background:#ffe0e0;color:#8e1111}
                .login-body{min-height:100vh;display:grid;place-items:center}.login-card{width:min(420px,92vw);padding:28px}
                .login-card form{display:grid;gap:10px}.login-card label{font-weight:600;margin-top:5px}
                @media(max-width:900px){.cards{grid-template-columns:repeat(2,1fr)}.top,.section-title{align-items:flex-start;flex-direction:column}}
                """;
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
            String item = parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
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

    private static String html(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean secureEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String formatDuration(long millis) {
        Duration duration = Duration.ofMillis(millis);
        return "%dd %02dh %02dm".formatted(
                duration.toDays(), duration.toHoursPart(), duration.toMinutesPart());
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record AdminSession(String token, String username, String csrf, long expiresAt) {
    }
}
