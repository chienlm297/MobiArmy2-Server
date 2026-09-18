package mobiarmy.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

final class AdminView {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static final ThreadLocal<AdminAccounts.Role> ROLE = ThreadLocal.withInitial(() -> AdminAccounts.Role.VIEWER);
    static void setRole(AdminAccounts.Role role) { ROLE.set(role); }
    static void clearRole() { ROLE.remove(); }

    private AdminView() {
    }

    static String login(String error) {
        String message = error == null ? "" : "<div class='alert alert-error'>" + h(error) + "</div>";
        return """
                <!doctype html><html lang='vi'><head><meta charset='utf-8'>
                <meta name='viewport' content='width=device-width,initial-scale=1'>
                <title>Đăng nhập · MobiArmy Admin</title><style>%s</style></head>
                <body class='login-page'><main class='login-shell'>
                  <section class='login-brand'><div class='brand-mark'>MA</div>
                    <p class='eyebrow'>CONTROL CENTER</p><h1>Điều hành server<br>trong một màn hình.</h1>
                    <p>Theo dõi người chơi, tài nguyên và các thao tác quản trị an toàn.</p>
                    <div class='login-points'><span>● Trạng thái thời gian thực</span><span>● Lịch sử thao tác đầy đủ</span></div>
                  </section>
                  <section class='login-card'><p class='eyebrow'>MOBIARMY ADMIN</p><h2>Chào mừng trở lại</h2>
                    <p class='muted'>Đăng nhập bằng tài khoản quản trị server.</p>%s
                    <form method='post' action='/login' class='stack-form'>
                      <label>Tài khoản<input name='username' autocomplete='username' autofocus required></label>
                      <label>Mật khẩu<input type='password' name='password' autocomplete='current-password' required></label>
                      <button class='btn btn-primary btn-wide'>Đăng nhập</button>
                    </form>
                  </section>
                </main></body></html>
                """.formatted(css(), message);
    }

    static String dashboard(AdminService.Dashboard dashboard,
                            AdminService.UserPage page,
                            List<AdminService.AuditEntry> audit,
                            String search, String message, String csrf, String username) {
        StringBuilder body = new StringBuilder();
        body.append(pageHeader("Tổng quan", "Theo dõi hoạt động và quản lý người chơi", username));
        notice(body, message);
        long uptime = Math.max(0, System.currentTimeMillis() - dashboard.startedAt());
        body.append("<div class='metric-grid'>")
                .append(metric("Uptime", formatDuration(uptime), "Server đang hoạt động", "violet"))
                .append(metric("Kết nối TCP", n(dashboard.sessions()), "Session hiện tại", "blue"))
                .append(metric("Đang online", n(dashboard.onlineUsers()), "Người chơi trong game", "green"))
                .append(metric("Tổng người chơi", n(dashboard.loadedUsers()), "Tài khoản đã nạp", "amber"))
                .append("</div>");

        body.append("<section class='panel' id='players'><div class='panel-head'><div><p class='eyebrow'>PLAYERS</p>")
                .append("<h2>Danh sách người chơi</h2></div><div class='panel-actions'>")
                .append("<form method='get' action='/admin' class='search-form'>")
                .append("<input name='q' value='").append(h(search))
                .append("' placeholder='ID, tài khoản hoặc tên nhân vật'>")
                .append(filterOptions(page.filter()))
                .append("<button class='btn btn-primary'>Lọc</button></form>")
                .append("<a class='btn btn-create' href='/admin/users/new'>＋ Tạo người chơi</a></div></div>")
                .append("<div class='table-wrap'><table><thead><tr>")
                .append("<th>Người chơi</th><th>Trạng thái</th><th>Xu</th><th>Lượng</th><th>Cup</th><th></th>")
                .append("</tr></thead><tbody>");
        body.append("<!-- filtered users -->");
        for (AdminService.UserSummary user : page.rows()) {
            body.append("<tr><td><div class='identity'><span class='avatar'>")
                    .append(initial(user.name())).append("</span><div><strong>")
                    .append(h(user.name())).append("</strong><small>#").append(user.id())
                    .append(" · ").append(h(user.username())).append("</small></div></div></td><td>")
                    .append(status(user)).append("</td><td class='money'>").append(n(user.xu()))
                    .append("</td><td class='money'>").append(n(user.luong()))
                    .append("</td><td>").append(n(user.cup())).append("</td><td class='align-right'>")
                    .append("<a class='btn btn-soft' href='/admin/users/").append(user.id())
                    .append("'>Xem chi tiết →</a></td></tr>");
        }
        if (page.rows().isEmpty()) {
            body.append(emptyRow(6, "Không tìm thấy người chơi phù hợp."));
        }
        body.append("</tbody></table></div><div class='pagination'><span>").append(page.total())
                .append(" người chơi · Trang ").append(page.page()).append(" / ").append(page.pages()).append("</span>");
        if (page.page() > 1) body.append("<a class='btn btn-soft' href='/admin?q=").append(url(search))
                .append("&amp;filter=").append(page.filter()).append("&amp;page=").append(page.page()-1).append("'>← Trước</a>");
        if (page.page() < page.pages()) body.append("<a class='btn btn-soft' href='/admin?q=").append(url(search))
                .append("&amp;filter=").append(page.filter()).append("&amp;page=").append(page.page()+1).append("'>Tiếp →</a>");
        body.append("</div></section>");

        body.append("<section class='panel'><div class='panel-head'><div><p class='eyebrow'>SECURITY</p>")
                .append("<h2>Hoạt động quản trị gần đây</h2></div></div>")
                .append(auditTable(audit, true)).append("</section>");
        return layout("Tổng quan", body.toString(), "dashboard", csrf);
    }

    static String userDetail(AdminService.UserDetail detail, String tab, String message,
                             String csrf, String username, List<AdminOperations.Catalog> catalog) {
        AdminService.UserSummary user = detail.summary();
        StringBuilder body = new StringBuilder();
        body.append(pageHeader("Chi tiết người chơi", "Hồ sơ #" + user.id(), username));
        notice(body, message);
        body.append("<a class='back-link' href='/admin'>← Danh sách người chơi</a>")
                .append("<section class='profile-hero'><div class='profile-main'><span class='avatar avatar-xl'>")
                .append(initial(user.name())).append("</span><div><div class='profile-title'><h2>")
                .append(h(user.name())).append("</h2>").append(status(user)).append("</div><p>")
                .append(h(user.username())).append(" · ID ").append(user.id()).append("</p></div></div>")
                .append("<div class='profile-stats'><div><span>Xu</span><strong>").append(n(user.xu()))
                .append("</strong></div><div><span>Lượng</span><strong>").append(n(user.luong()))
                .append("</strong></div><div><span>Cup</span><strong>").append(n(user.cup()))
                .append("</strong></div></div></section>")
                .append(tabs(user.id(), tab, detail));

        switch (tab) {
            case "characters" -> characters(body, detail.characters(), user.id(), csrf);
            case "equipment" -> equipment(body, detail.equipment(), detail.equipmentCatalog(),
                    user.id(), csrf);
            case "inventory" -> inventory(body, detail.inventory(), user.id(), csrf, catalog);
            case "missions" -> missions(body, detail.missions());
            case "friends" -> friends(body, detail.friends());
            case "history" -> history(body, detail);
            default -> overview(body, detail, csrf);
        }
        return layout(user.name(), body.toString(), "users", csrf);
    }

    static String createUser(String error, java.util.Map<String, String> values,
                             String csrf, String username) {
        String account = values.getOrDefault("username", "");
        String characterName = values.getOrDefault("character_name", "");
        String initialXu = values.getOrDefault("initial_xu", "1000");
        String initialLuong = values.getOrDefault("initial_luong", "1000");
        String reason = values.getOrDefault("reason", "");
        StringBuilder body = new StringBuilder();
        body.append(pageHeader("Tạo người chơi", "Khởi tạo tài khoản game mới", username))
                .append("<a class='back-link' href='/admin#players'>← Danh sách người chơi</a>");
        if (error != null && !error.isBlank()) {
            body.append("<div class='alert alert-error'>").append(h(error)).append("</div>");
        }
        body.append("<div class='create-layout'><section class='panel create-panel'>")
                .append("<div class='panel-head'><div><p class='eyebrow'>NEW PLAYER</p><h2>Thông tin tài khoản</h2></div></div>")
                .append("<form method='post' action='/admin/users' class='create-form'>")
                .append("<input type='hidden' name='csrf' value='").append(h(csrf)).append("'>")
                .append("<label>Tài khoản<span>Dùng để đăng nhập game</span><input name='username' value='")
                .append(h(account)).append("' minlength='3' maxlength='32' pattern='[A-Za-z0-9_]+' ")
                .append("autocomplete='off' placeholder='VD: player001' required></label>")
                .append("<label>Tên nhân vật<span>Tên hiển thị trong game</span><input name='character_name' value='")
                .append(h(characterName)).append("' minlength='3' maxlength='32' autocomplete='off' required></label>")
                .append("<div class='create-grid'><label>Mật khẩu<span>Tối thiểu 6 ký tự</span>")
                .append("<input type='password' name='password' minlength='6' maxlength='72' autocomplete='new-password' required></label>")
                .append("<label>Nhập lại mật khẩu<span>Phải trùng mật khẩu</span>")
                .append("<input type='password' name='confirm_password' minlength='6' maxlength='72' autocomplete='new-password' required></label></div>")
                .append("<div class='create-grid'><label>Xu ban đầu<span>0 đến 2.147.483.647</span>")
                .append("<input type='number' name='initial_xu' min='0' max='2147483647' value='")
                .append(h(initialXu)).append("' required></label><label>Lượng ban đầu<span>0 đến 2.147.483.647</span>")
                .append("<input type='number' name='initial_luong' min='0' max='2147483647' value='")
                .append(h(initialLuong)).append("' required></label></div>")
                .append("<label>Lý do tạo tài khoản<span>Được lưu trong audit log</span>")
                .append("<input name='reason' value='").append(h(reason))
                .append("' maxlength='500' placeholder='VD: Tạo tài khoản theo yêu cầu hỗ trợ' required></label>")
                .append("<div class='form-submit'><a class='btn btn-soft' href='/admin'>Hủy</a>")
                .append("<button class='btn btn-primary'>Tạo người chơi</button></div></form></section>")
                .append("<aside class='panel create-note'><span class='item-icon'>✓</span><h3>Dữ liệu được tạo tự động</h3>")
                .append("<p>Tài khoản mới có thể đăng nhập ngay mà không cần khởi động lại server.</p>")
                .append("<ul><li>Mật khẩu được băm bằng BCrypt</li><li>Ba nhân vật miễn phí cấp 1</li>")
                .append("<li>99 HP và 99 Teleport</li><li>Toàn bộ nhiệm vụ cấp 1</li>")
                .append("<li>Ghi nhận đầy đủ trong audit log</li></ul></aside></div>");
        return layout("Tạo người chơi", body.toString(), "users", csrf);
    }

    static String error(String title, String message, boolean authenticated, String csrf) {
        String content = "<section class='error-card'><span class='error-code'>!</span><h1>"
                + h(title) + "</h1><p>" + h(message)
                + "</p><a class='btn btn-primary' href='/admin'>Quay lại trang quản trị</a></section>";
        if (authenticated) {
            return layout(title, content, "", csrf == null ? "" : csrf);
        }
        return "<!doctype html><html lang='vi'><head><meta charset='utf-8'><meta name='viewport' "
                + "content='width=device-width,initial-scale=1'><title>" + h(title)
                + "</title><style>" + css() + "</style></head><body class='login-page'>"
                + content + "</body></html>";
    }

    private static void overview(StringBuilder body, AdminService.UserDetail detail, String csrf) {
        AdminService.UserSummary user = detail.summary();
        body.append("<div class='detail-grid'><div class='content-stack'>")
                .append("<section class='panel'><div class='panel-head'><div><p class='eyebrow'>ACCOUNT</p><h2>Thông tin tài khoản</h2></div></div>")
                .append("<dl class='info-grid'><div><dt>Tài khoản</dt><dd>").append(h(user.username()))
                .append("</dd></div><div><dt>Tên nhân vật</dt><dd>").append(h(user.name()))
                .append("</dd></div><div><dt>Nhân vật đang chọn</dt><dd>#").append(detail.selectedGlass())
                .append("</dd></div><div><dt>Phiên bản client</dt><dd>").append(h(detail.clientVersion()))
                .append("</dd></div><div><dt>Địa chỉ kết nối</dt><dd>").append(h(detail.remoteAddress()))
                .append("</dd></div><div><dt>Trạng thái tài khoản</dt><dd>")
                .append(accountStatusText(user)).append("</dd></div></dl>");
        if (!"ACTIVE".equals(user.accountStatus())) {
            body.append("<div class='ban-note'><strong>Lý do trạng thái:</strong> ")
                    .append(h(user.accountReason())).append("</div>");
        }
        if (user.banned()) {
            body.append("<div class='ban-note'><strong>Lý do khóa:</strong> ").append(h(user.banReason()))
                    .append("<br><strong>Đến:</strong> ")
                    .append(user.bannedUntil() == null ? "Vĩnh viễn" : date(user.bannedUntil().toInstant()))
                    .append("</div>");
        }
        body.append("</section><section class='panel'><div class='panel-head'><div><p class='eyebrow'>SNAPSHOT</p>")
                .append("<h2>Dữ liệu hiện có</h2></div></div><div class='mini-stat-grid'>")
                .append(miniStat("Nhân vật", detail.characters().size()))
                .append(miniStat("Trang bị", detail.equipment().size()))
                .append(miniStat("Vật phẩm", detail.inventory().size()))
                .append(miniStat("Nhiệm vụ", detail.missions().size()))
                .append(miniStat("Bạn bè", detail.friends().size())).append("</div></section></div>")
                .append("<aside class='content-stack'><section class='panel action-panel'><p class='eyebrow'>QUICK ACTIONS</p>")
                .append("<h2>Điều chỉnh tài khoản</h2>")
                .append(walletForm(user.id(), csrf)).append(kickForm(user.id(), csrf))
                .append(banForm(user, csrf)).append(accountStateForm(user, csrf))
                .append("</section></aside></div>")
                .append("<section class='panel'><div class='panel-head'><div><p class='eyebrow'>PLAYER MANAGEMENT</p>")
                .append("<h2>Bảo mật và hồ sơ</h2></div></div><div class='manage-grid'>")
                .append(renameForm(user, csrf)).append(passwordForm(user.id(), csrf))
                .append(cupForm(user.id(), csrf)).append(softDeleteForm(user, csrf))
                .append("</div></section>");
    }

    private static void characters(StringBuilder body, List<AdminService.CharacterDetail> rows,
                                   int userId, String csrf) {
        body.append("<div class='character-grid'>");
        for (AdminService.CharacterDetail row : rows) {
            body.append("<article class='character-card ").append(row.selected() ? "selected" : "")
                    .append("'><div class='character-head'><span class='avatar'>")
                    .append(initial(row.name())).append("</span><div><h3>").append(h(row.name()))
                    .append("</h3><span>#").append(row.glassId()).append("</span></div>")
                    .append(row.selected() ? "<span class='badge badge-online'>Đang chọn</span>" : "")
                    .append("</div><div class='character-level'><strong>Lv. ").append(row.level())
                    .append("</strong><span>").append(n(row.exp())).append(" EXP</span></div>")
                    .append("<dl class='compact-info'><div><dt>Điểm còn lại</dt><dd>").append(row.point())
                    .append("</dd></div><div><dt>HP · Sức mạnh · Phòng thủ · May mắn · Đồng đội</dt><dd><code>").append(h(row.ability()))
                    .append("</code></dd></div><div><dt>Trang bị</dt><dd><code>")
                    .append(h(row.equipment())).append("</code></dd></div></dl>")
                    .append("<form method='post' action='/admin/users/").append(userId)
                    .append("/character' class='card-admin-form'>")
                    .append(hidden(csrf, userId, "characters"))
                    .append("<input type='hidden' name='glass_id' value='").append(row.glassId()).append("'>")
                    .append("<div class='form-row'><label>EXP<input type='number' min='0' name='exp' value='")
                    .append(row.exp()).append("' required></label><label>Điểm<input type='number' min='0' max='32767' name='point' value='")
                    .append(row.point()).append("' required></label></div>")
                    .append(abilityFields(row.ability()))
                    .append("<p class='muted'>Điểm gốc trước trang bị. Chỉ số trận đang diễn ra giữ theo lúc bắt đầu trận.</p>")
                    .append("<input name='reason' maxlength='500' placeholder='Lý do thay đổi' required>")
                    .append("<button class='btn btn-soft btn-wide'>Lưu nhân vật</button></form></article>");
        }
        if (rows.isEmpty()) {
            body.append(emptyState("Chưa có dữ liệu nhân vật."));
        }
        body.append("</div>");
    }

    private static void equipment(StringBuilder body, List<AdminService.EquipmentDetail> rows,
                                  List<AdminService.EquipmentCatalog> catalog,
                                  int userId, String csrf) {
        body.append("<section class='panel'><div class='panel-head'><div><p class='eyebrow'>EQUIPMENT</p><h2>Trang bị sở hữu</h2></div>")
                .append("<span class='count-pill'>").append(rows.size()).append(" món</span></div>")
                .append("<form method='post' action='/admin/users/").append(userId)
                .append("/equipment-add' class='inline-admin-form'>")
                .append(hidden(csrf, userId, "equipment"))
                .append("<div class='picker'><label>Tìm trang bị<input type='search' class='catalog-search' placeholder='Nhập tên, nhân vật hoặc ID'></label>")
                .append("<select name='equipment_key' aria-label='Trang bị' required><option value=''>Chọn trang bị cần thêm</option>");
        for (AdminService.EquipmentCatalog item : catalog) {
            body.append("<option value='").append(item.glassId()).append(":").append(item.equipmentId())
                    .append("'>").append(h(item.glassName())).append(" · ")
                    .append(h(item.name())).append(" (#").append(item.equipmentId()).append(")</option>");
        }
        body.append("</select><small class='picker-count' aria-live='polite'></small></div><input name='reason' maxlength='500' placeholder='Lý do thêm trang bị' required>")
                .append("<button class='btn btn-primary'>Thêm trang bị</button></form>")
                .append("<div class='table-wrap'><table><thead><tr><th>Trang bị</th><th>Nhân vật</th><th>Loại</th>")
                .append("<th>Nâng cấp</th><th>Chỉ số</th><th>Slot</th><th>Trạng thái</th><th>Thao tác</th></tr></thead><tbody>");
        for (AdminService.EquipmentDetail row : rows) {
            body.append("<tr><td><strong>").append(h(row.name())).append("</strong><small class='block'>ID ")
                    .append(row.equipmentId()).append(" · Key ").append(row.databaseKey())
                    .append("</small></td><td>").append(h(row.glassName())).append("</td><td>")
                    .append(equipmentType(row.type())).append("</td><td>+").append(row.upgradeLevel())
                    .append("</td><td><code>").append(h(row.ability())).append("</code><small class='block'>% ")
                    .append(h(row.percent())).append("</small></td><td><code>").append(h(row.slots()))
                    .append("</code></td><td>").append(row.inUse()
                            ? "<span class='badge badge-online'>Đang dùng</span>"
                            : "<span class='badge'>Trong kho</span>")
                    .append("<small class='block'>").append(expiry(row)).append("</small></td><td>")
                    .append("<form method='post' action='/admin/users/").append(userId)
                    .append("/equipment-remove' class='remove-form'>")
                    .append(hidden(csrf, userId, "equipment"))
                    .append("<input type='hidden' name='glass_id' value='").append(row.glassId()).append("'>")
                    .append("<input type='hidden' name='equipment_id' value='").append(row.equipmentId()).append("'>")
                    .append("<input type='hidden' name='database_key' value='").append(row.databaseKey()).append("'>")
                    .append("<input name='reason' maxlength='500' placeholder='Lý do xóa' required>")
                    .append("<button class='btn btn-danger'>Xóa</button></form></td></tr>");
        }
        if (rows.isEmpty()) {
            body.append(emptyRow(8, "Người chơi chưa có trang bị riêng."));
        }
        body.append("</tbody></table></div></section>");
    }

    private static void inventory(StringBuilder body, List<AdminService.InventoryItem> rows,
                                  int userId, String csrf, List<AdminOperations.Catalog> catalog) {
        body.append("<section class='panel'><div class='panel-head'><div><p class='eyebrow'>INVENTORY</p><h2>Kho vật phẩm</h2></div>")
                .append("<span class='count-pill'>").append(rows.size()).append(" loại</span></div>")
                .append("<form method='post' action='/admin/users/").append(userId)
                .append("/inventory' class='inline-admin-form inventory-editor'>")
                .append(hidden(csrf, userId, "inventory"))
                .append(catalogPicker(catalog))
                .append("<input type='number' name='amount' placeholder='+10 hoặc -10' required>")
                .append("<input name='reason' maxlength='500' placeholder='Lý do điều chỉnh' required>")
                .append("<button class='btn btn-primary'>Cập nhật kho</button></form>")
                .append("<div class='inventory-grid'>");
        for (AdminService.InventoryItem row : rows) {
            body.append("<article class='inventory-card'><span class='item-icon'>")
                    .append("SPECIAL".equals(row.kind()) ? "◆" : "✦").append("</span><div><strong>")
                    .append(h(row.name())).append("</strong><small>")
                    .append("SPECIAL".equals(row.kind()) ? "Đồ đặc biệt" : "Item")
                    .append(" #").append(row.itemId()).append("</small>")
                    .append(row.detail().isBlank() ? "" : "<p>" + h(row.detail()) + "</p>")
                    .append("</div><b>× ").append(n(row.quantity())).append("</b></article>");
        }
        if (rows.isEmpty()) {
            body.append(emptyState("Kho vật phẩm đang trống."));
        }
        body.append("</div></section>");
    }

    private static void missions(StringBuilder body, List<AdminService.MissionDetail> rows) {
        body.append("<section class='panel'><div class='panel-head'><div><p class='eyebrow'>MISSIONS</p><h2>Tiến độ nhiệm vụ</h2></div></div>")
                .append("<div class='mission-list'>");
        for (AdminService.MissionDetail row : rows) {
            long percent = row.required() <= 0 ? 0 : Math.min(100, row.progress() * 100 / row.required());
            body.append("<article class='mission-row'><div class='mission-top'><div><strong>")
                    .append(h(row.name())).append("</strong><small>Nhiệm vụ #").append(row.missionId())
                    .append(" · cấp ").append(row.level()).append("</small></div>")
                    .append(row.rewardClaimed() ? "<span class='badge'>Đã nhận thưởng</span>"
                            : row.complete() ? "<span class='badge badge-online'>Hoàn thành</span>"
                            : "<span class='badge'>Đang làm</span>")
                    .append("</div><div class='progress'><span style='width:").append(percent)
                    .append("%'></span></div><div class='mission-foot'><span>")
                    .append(n(row.progress())).append(" / ").append(n(row.required()))
                    .append("</span><span>").append(h(row.reward())).append("</span></div></article>");
        }
        if (rows.isEmpty()) {
            body.append(emptyState("Chưa có dữ liệu nhiệm vụ."));
        }
        body.append("</div></section>");
    }

    private static void friends(StringBuilder body, List<AdminService.FriendDetail> rows) {
        body.append("<section class='panel'><div class='panel-head'><div><p class='eyebrow'>SOCIAL</p><h2>Danh sách bạn bè</h2></div>")
                .append("<span class='count-pill'>").append(rows.size()).append(" người</span></div>")
                .append("<div class='friend-grid'>");
        for (AdminService.FriendDetail row : rows) {
            body.append("<a class='friend-card' href='/admin/users/").append(row.userId()).append("'>")
                    .append("<span class='avatar'>").append(initial(row.name())).append("</span><div><strong>")
                    .append(h(row.name())).append("</strong><small>").append(h(row.username()))
                    .append(" · #").append(row.userId()).append("</small></div>")
                    .append(row.online() ? "<span class='presence'></span>" : "").append("</a>");
        }
        if (rows.isEmpty()) {
            body.append(emptyState("Người chơi chưa có bạn bè."));
        }
        body.append("</div></section>");
    }

    private static void history(StringBuilder body, AdminService.UserDetail detail) {
        body.append("<section class='panel'><div class='panel-head'><div><p class='eyebrow'>WALLET</p><h2>Lịch sử số dư</h2></div></div>")
                .append("<div class='table-wrap'><table><thead><tr><th>Thời gian</th><th>Loại</th><th>Thay đổi</th>")
                .append("<th>Số dư</th><th>Admin</th><th>Lý do</th></tr></thead><tbody>");
        for (AdminService.WalletEntry row : detail.walletHistory()) {
            body.append("<tr><td>").append(date(row.createdAt().toInstant())).append("</td><td>")
                    .append(h(row.currency())).append("</td><td class='")
                    .append(row.amount() >= 0 ? "positive" : "negative").append("'>")
                    .append(row.amount() >= 0 ? "+" : "").append(n(row.amount()))
                    .append("</td><td>").append(n(row.before())).append(" → ").append(n(row.after()))
                    .append("</td><td>").append(h(row.adminUsername())).append("</td><td>")
                    .append(h(row.reason())).append("</td></tr>");
        }
        if (detail.walletHistory().isEmpty()) {
            body.append(emptyRow(6, "Chưa có giao dịch số dư từ web admin."));
        }
        body.append("</tbody></table></div></section><section class='panel'><div class='panel-head'><div>")
                .append("<p class='eyebrow'>AUDIT</p><h2>Thao tác quản trị</h2></div></div>")
                .append(auditTable(detail.audit(), false)).append("</section>");
    }

    private static String tabs(int userId, String active, AdminService.UserDetail detail) {
        return "<nav class='tabs'>"
                + tab(userId, "overview", "Tổng quan", active, "")
                + tab(userId, "characters", "Nhân vật", active, detail.characters().size())
                + tab(userId, "equipment", "Trang bị", active, detail.equipment().size())
                + tab(userId, "inventory", "Vật phẩm", active, detail.inventory().size())
                + tab(userId, "missions", "Nhiệm vụ", active, detail.missions().size())
                + tab(userId, "friends", "Bạn bè", active, detail.friends().size())
                + tab(userId, "history", "Lịch sử", active, "") + "</nav>";
    }

    private static String tab(int userId, String value, String label, String active, Object count) {
        String suffix = count.toString().isBlank() ? "" : " <span>" + count + "</span>";
        return "<a class='" + (value.equals(active) ? "active" : "") + "' href='/admin/users/"
                + userId + "?tab=" + value + "'>" + label + suffix + "</a>";
    }

    private static String walletForm(int userId, String csrf) {
        return "<form method='post' action='/admin/users/" + userId + "/wallet' class='action-form'>"
                + hidden(csrf, userId) + "<h3>Cộng / trừ số dư</h3><div class='form-row'><select name='currency'>"
                + "<option value='xu'>Xu</option><option value='luong'>Lượng</option></select>"
                + "<input type='number' name='amount' placeholder='+1000 hoặc -1000' required></div>"
                + "<input name='reason' placeholder='Lý do điều chỉnh' maxlength='500' required>"
                + "<button class='btn btn-primary btn-wide'>Cập nhật số dư</button></form>";
    }

    private static String kickForm(int userId, String csrf) {
        return "<form method='post' action='/admin/users/" + userId + "/kick' class='action-form'>"
                + hidden(csrf, userId) + "<h3>Ngắt kết nối</h3>"
                + "<input name='reason' placeholder='Lý do kick' maxlength='500' required>"
                + "<button class='btn btn-warning btn-wide'>Kick người chơi</button></form>";
    }

    private static String banForm(AdminService.UserSummary user, String csrf) {
        String base = "/admin/users/" + user.id();
        if (user.banned()) {
            return "<form method='post' action='" + base + "/unban' class='action-form'>"
                    + hidden(csrf, user.id()) + "<h3>Mở khóa tài khoản</h3>"
                    + "<input name='reason' placeholder='Lý do gỡ ban' maxlength='500' required>"
                    + "<button class='btn btn-soft btn-wide'>Gỡ lệnh ban</button></form>";
        }
        return "<form method='post' action='" + base + "/ban' class='action-form'>"
                + hidden(csrf, user.id()) + "<h3>Khóa tài khoản</h3>"
                + "<input type='number' min='0' name='minutes' value='0' title='0 là vĩnh viễn'>"
                + "<input name='reason' placeholder='Lý do ban' maxlength='500' required>"
                + "<button class='btn btn-danger btn-wide'>Ban người chơi</button></form>";
    }

    private static String accountStateForm(AdminService.UserSummary user, String csrf) {
        String base = "/admin/users/" + user.id();
        if ("LOCKED".equals(user.accountStatus())) {
            return "<form method='post' action='" + base + "/unlock' class='action-form'>"
                    + hidden(csrf, user.id()) + "<h3>Mở khóa đăng nhập</h3>"
                    + "<input name='reason' placeholder='Lý do mở khóa' maxlength='500' required>"
                    + "<button class='btn btn-soft btn-wide'>Mở khóa nhanh</button></form>";
        }
        if ("DELETED".equals(user.accountStatus())) {
            return "<form method='post' action='" + base + "/restore' class='action-form'>"
                    + hidden(csrf, user.id()) + "<h3>Khôi phục tài khoản</h3>"
                    + "<input name='reason' placeholder='Lý do khôi phục' maxlength='500' required>"
                    + "<button class='btn btn-soft btn-wide'>Khôi phục</button></form>";
        }
        return "<form method='post' action='" + base + "/lock' class='action-form'>"
                + hidden(csrf, user.id()) + "<h3>Khóa đăng nhập nhanh</h3>"
                + "<input name='reason' placeholder='Lý do khóa' maxlength='500' required>"
                + "<button class='btn btn-warning btn-wide'>Khóa đăng nhập</button></form>";
    }

    private static String renameForm(AdminService.UserSummary user, String csrf) {
        return "<form method='post' action='/admin/users/" + user.id() + "/rename' class='manage-form'>"
                + hidden(csrf, user.id()) + "<h3>Đổi tên nhân vật</h3>"
                + "<input name='new_name' minlength='3' maxlength='32' value='" + h(user.name()) + "' required>"
                + "<input name='reason' maxlength='500' placeholder='Lý do đổi tên' required>"
                + "<button class='btn btn-primary btn-wide'>Đổi tên</button></form>";
    }

    private static String passwordForm(int userId, String csrf) {
        return "<form method='post' action='/admin/users/" + userId + "/password' class='manage-form'>"
                + hidden(csrf, userId) + "<h3>Đặt lại mật khẩu</h3>"
                + "<input type='password' name='password' minlength='6' maxlength='72' placeholder='Mật khẩu mới' required>"
                + "<input type='password' name='confirm_password' minlength='6' maxlength='72' placeholder='Nhập lại mật khẩu' required>"
                + "<input name='reason' maxlength='500' placeholder='Lý do đặt lại' required>"
                + "<button class='btn btn-primary btn-wide'>Đặt lại mật khẩu</button></form>";
    }

    private static String cupForm(int userId, String csrf) {
        return "<form method='post' action='/admin/users/" + userId + "/cup' class='manage-form'>"
                + hidden(csrf, userId) + "<h3>Cộng / trừ cup</h3>"
                + "<input type='number' name='amount' placeholder='+100 hoặc -100' required>"
                + "<input name='reason' maxlength='500' placeholder='Lý do điều chỉnh' required>"
                + "<button class='btn btn-primary btn-wide'>Cập nhật cup</button></form>";
    }

    private static String softDeleteForm(AdminService.UserSummary user, String csrf) {
        if ("DELETED".equals(user.accountStatus())) {
            return "<div class='manage-form state-note'><h3>Tài khoản đang xóa mềm</h3>"
                    + "<p>Dữ liệu vẫn được giữ nguyên. Dùng nút khôi phục ở phần thao tác nhanh.</p></div>";
        }
        return "<form method='post' action='/admin/users/" + user.id() + "/delete' class='manage-form danger-zone'>"
                + hidden(csrf, user.id()) + "<h3>Xóa mềm tài khoản</h3>"
                + "<p>Chặn đăng nhập nhưng giữ toàn bộ dữ liệu để có thể khôi phục.</p>"
                + "<input name='reason' maxlength='500' placeholder='Lý do xóa mềm' required>"
                + "<button class='btn btn-danger btn-wide'>Xóa mềm</button></form>";
    }

    private static String hidden(String csrf, int userId) {
        return hidden(csrf, userId, null);
    }

    private static String hidden(String csrf, int userId, String tab) {
        String next = "/admin/users/" + userId + (tab == null ? "" : "?tab=" + tab);
        return "<input type='hidden' name='csrf' value='" + h(csrf) + "'>"
                + "<input type='hidden' name='next' value='" + next + "'>";
    }

    private static String auditTable(List<AdminService.AuditEntry> rows, boolean showUser) {
        StringBuilder html = new StringBuilder("<div class='table-wrap'><table><thead><tr><th>Thời gian</th><th>Hành động</th>");
        if (showUser) {
            html.append("<th>User</th>");
        }
        html.append("<th>Admin</th><th>Chi tiết</th></tr></thead><tbody>");
        for (AdminService.AuditEntry row : rows) {
            html.append("<tr><td>").append(date(row.createdAt().toInstant())).append("</td><td><span class='badge'>")
                    .append(h(row.action())).append("</span></td>");
            if (showUser) {
                html.append("<td>");
                if (row.targetUserId() != null) {
                    html.append("<a href='/admin/users/").append(row.targetUserId()).append("'>#")
                            .append(row.targetUserId()).append("</a>");
                }
                html.append("</td>");
            }
            html.append("<td>").append(h(row.adminUsername())).append("</td><td>")
                    .append(h(row.detail()));
            if (row.reason() != null && !row.reason().isBlank()) {
                html.append("<small class='block'><strong>Lý do:</strong> ")
                        .append(h(row.reason())).append("</small>");
            }
            if ((row.beforeData() != null && !row.beforeData().isBlank())
                    || (row.afterData() != null && !row.afterData().isBlank())) {
                html.append("<details class='audit-data'><summary>Dữ liệu trước / sau</summary><code>")
                        .append(h(row.beforeData() == null ? "∅" : row.beforeData()))
                        .append(" → ").append(h(row.afterData() == null ? "∅" : row.afterData()))
                        .append("</code></details>");
            }
            html.append("</td></tr>");
        }
        if (rows.isEmpty()) {
            html.append(emptyRow(showUser ? 5 : 4, "Chưa có thao tác quản trị."));
        }
        return html.append("</tbody></table></div>").toString();
    }

    static String bots(AdminBots.Snapshot snapshot, List<AdminBots.Job> jobs,
                       java.util.Map<String,String> query, String csrf, String username) {
        return layout("Quản lý bot", BotView.render(snapshot, jobs, query, csrf, username), "bots", csrf);
    }

    private static String layout(String title, String body, String active, String csrf) {
        body = visibleForms(body);
        if (!ROLE.get().allows("/admin/users/new")) body = body.replaceAll("<a class='btn btn-create'[^>]*>.*?</a>", "");
        return "<!doctype html><html lang='vi'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + h(title) + " · MobiArmy Admin</title><style>" + css() + "</style><script src='/admin/assets/admin.js' defer></script></head>"
                + "<body><div class='app-shell'><aside class='sidebar'><a class='logo' href='/admin'>"
                + "<span>MA</span><div><strong>MobiArmy</strong><small>ADMIN CONSOLE</small></div></a>"
                + "<nav><p>QUẢN TRỊ</p><a class='" + ("dashboard".equals(active) ? "active" : "")
                + "' href='/admin'><i>⌂</i>Tổng quan</a><a class='" + ("users".equals(active) ? "active" : "")
                + "' href='/admin#players'><i>♟</i>Người chơi</a>"
                + "<a class='" + ("rooms".equals(active) ? "active" : "") + "' href='/admin/rooms'>◫ Phòng và trận</a><a class='" + ("broadcast".equals(active) ? "active" : "") + "' href='/admin/broadcast'>✉ Thông báo</a>"
                + "<a class='" + ("bots".equals(active) ? "active" : "") + "' href='/admin/bots'>♟ Quản lý bot</a>"
                + (ROLE.get() == AdminAccounts.Role.OWNER ? "<a class='" + ("accounts".equals(active) ? "active" : "") + "' href='/admin/accounts'>⚙ Tài khoản admin</a>" : "") + "</nav>"
                + "<div class='server-state'><span></span><div><strong>Game server</strong><small>" + (System.currentTimeMillis() - AdminOperations.rooms().capturedAt() < 5000 ? "Game loop hoạt động" : "Chưa có cập nhật mới") + "</small></div></div>"
                + "<form method='post' action='/logout'><input type='hidden' name='csrf' value='" + h(csrf)
                + "'><button class='logout'>↪ Đăng xuất</button></form></aside>"
                + "<main class='main-content'>" + body + "</main></div></body></html>";
    }

    private static String pageHeader(String title, String subtitle, String username) {
        return "<header class='page-head'><div><p class='eyebrow'>MOBIARMY CONTROL CENTER</p><h1>"
                + h(title) + "</h1><p>" + h(subtitle) + "</p></div><div class='admin-chip'><span>"
                + initial(username) + "</span><div><strong>" + h(username)
                + "</strong><small>" + ROLE.get() + "</small></div></div></header>";
    }

    private static String status(AdminService.UserSummary user) {
        if ("DELETED".equals(user.accountStatus())) {
            return "<span class='badge badge-danger'><i></i>Đã xóa mềm</span>";
        }
        if ("LOCKED".equals(user.accountStatus())) {
            return "<span class='badge badge-warning'><i></i>Khóa đăng nhập</span>";
        }
        if (user.banned()) {
            return "<span class='badge badge-danger'><i></i>Đã ban</span>";
        }
        if (user.online()) {
            return "<span class='badge badge-online'><i></i>Online</span>";
        }
        return "<span class='badge'><i></i>Offline</span>";
    }

    private static String accountStatusText(AdminService.UserSummary user) {
        return switch (user.accountStatus()) {
            case "LOCKED" -> "Khóa đăng nhập";
            case "DELETED" -> "Đã xóa mềm";
            default -> user.banned() ? "Đang bị ban" : "Hoạt động";
        };
    }

    private static String metric(String label, String value, String detail, String tone) {
        return "<article class='metric " + tone + "'><div><span>" + h(label) + "</span><strong>"
                + h(value) + "</strong><small>" + h(detail) + "</small></div><b>◆</b></article>";
    }

    private static String miniStat(String label, int value) {
        return "<div><strong>" + value + "</strong><span>" + h(label) + "</span></div>";
    }

    private static String equipmentType(int type) {
        return switch (type) {
            case 0 -> "Vũ khí";
            case 1 -> "Nón";
            case 2 -> "Áo";
            case 3 -> "Kính";
            case 4 -> "Cánh";
            default -> "Loại " + type;
        };
    }

    private static String expiry(AdminService.EquipmentDetail row) {
        if (row.durationDays() <= 0 || row.renewalDate() <= 0) {
            return "Không thời hạn";
        }
        long expiresAt = row.renewalDate() + Duration.ofDays(row.durationDays()).toMillis();
        return expiresAt > System.currentTimeMillis() ? "Hết hạn " + date(Instant.ofEpochMilli(expiresAt)) : "Đã hết hạn";
    }

    private static void notice(StringBuilder body, String message) {
        if (message != null && !message.isBlank()) {
            body.append("<div class='alert alert-success'>✓ ").append(h(message)).append("</div>");
        }
    }

    private static String emptyRow(int columns, String message) {
        return "<tr><td colspan='" + columns + "'><div class='empty'>" + h(message) + "</div></td></tr>";
    }

    private static String emptyState(String message) {
        return "<div class='empty wide'>" + h(message) + "</div>";
    }

    private static String initial(String value) {
        if (value == null || value.isBlank()) {
            return "?";
        }
        return h(value.substring(0, 1).toUpperCase(Locale.ROOT));
    }

    static String h(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String n(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    private static String date(Instant value) {
        return value == null ? "—" : DATE_TIME.format(value);
    }

    private static String formatDuration(long millis) {
        Duration duration = Duration.ofMillis(millis);
        return "%d ngày %02d:%02d".formatted(duration.toDays(), duration.toHoursPart(), duration.toMinutesPart());
    }

    private static String filterOptions(String selected) {
        StringBuilder html = new StringBuilder("<select name='filter' aria-label='Trạng thái người chơi'>");
        String[] values = {"ALL", "ONLINE", "OFFLINE", "BANNED", "LOCKED", "DELETED"};
        String[] labels = {"Tất cả", "Online", "Offline", "Đã ban", "Khóa đăng nhập", "Đã xóa mềm"};
        for (int i = 0; i < values.length; i++) html.append("<option value='").append(values[i])
                .append("'").append(values[i].equals(selected) ? " selected" : "").append(">")
                .append(labels[i]).append("</option>");
        return html.append("</select>").toString();
    }

    private static String abilityFields(String json) {
        int[] values = new com.google.gson.Gson().fromJson(json, int[].class);
        if (values == null || values.length != 5) return "<p class='alert alert-error'>Dữ liệu chỉ số không hợp lệ.</p>";
        String[] labels = {"HP", "Sức mạnh", "Phòng thủ", "May mắn", "Đồng đội"};
        StringBuilder html = new StringBuilder("<div class='ability-fields'>");
        for (int i = 0; i < 5; i++) html.append("<label>").append(labels[i])
                .append("<input type='number' name='ability_").append(i)
                .append("' min='0' max='100000' value='").append(values[i]).append("' required></label>");
        return html.append("</div>").toString();
    }

    private static String catalogPicker(List<AdminOperations.Catalog> catalog) {
        StringBuilder html = new StringBuilder("<div class='picker'><label>Tìm vật phẩm<input type='search' class='catalog-search' placeholder='Tên hoặc ID vật phẩm'></label><select name='item_key' aria-label='Vật phẩm' required><option value=''>Chọn vật phẩm</option>");
        for (AdminOperations.Catalog item : catalog) {
            html.append("<option value='").append(item.kind()).append(":").append(item.id())
                    .append("' data-detail='").append(h(item.detail())).append("'>")
                    .append("ITEM".equals(item.kind()) ? "Item" : "Đồ đặc biệt")
                    .append(" · ").append(h(item.name())).append(" #").append(item.id()).append("</option>");
        }
        return html.append("</select><small class='picker-count' aria-live='polite'></small><div class='picker-preview' aria-live='polite'></div></div>").toString();
    }

    private static String visibleForms(String body) {
        // Only trusted, server-generated form markup is parsed here; authorization lives in AdminServer.
        var matcher = java.util.regex.Pattern.compile("<form\\b[^>]*method='post'[^>]*action='([^']+)'[^>]*>.*?</form>", java.util.regex.Pattern.DOTALL).matcher(body);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                ROLE.get().allows(matcher.group(1)) ? matcher.group() : "<p class='muted'>Quyền hiện tại: chỉ xem mục này.</p>"));
        matcher.appendTail(out);
        return out.toString();
    }

    static String broadcast(String csrf, String username, String message) {
        StringBuilder body = new StringBuilder(pageHeader("Thông báo toàn server", "Gửi tới các phiên người chơi đang online", username));
        notice(body, message);
        body.append("<section class='panel'><h2>Soạn thông báo</h2><form method='post' action='/admin/broadcast' class='stack-form'>")
                .append("<input type='hidden' name='csrf' value='").append(h(csrf)).append("'>")
                .append("<label>Nội dung<textarea name='text' maxlength='300' required placeholder='Server sẽ bảo trì lúc 22:00...'></textarea></label>")
                .append("<label>Lý do gửi<input name='reason' maxlength='500' required></label>")
                .append("<p class='muted'>Nội dung xuất hiện trong kênh thông báo của game. Tài khoản offline không nhận thông báo.</p>")
                .append("<button class='btn btn-primary'>Gửi thông báo</button></form></section>");
        return layout("Thông báo", body.toString(), "broadcast", csrf);
    }

    static String accounts(List<AdminAccounts.Account> accounts, String csrf, String username, String message) {
        StringBuilder body = new StringBuilder(pageHeader("Tài khoản quản trị", "Phân quyền và thu hồi phiên đăng nhập", username));
        notice(body, message);
        body.append("<section class='panel'><p>OWNER: toàn quyền · ADMIN: người chơi, bot và thông báo · MODERATOR: kick, ban, khóa/mở khóa · VIEWER: chỉ xem.</p>")
                .append("<p class='muted'>Đổi quyền, trạng thái hoặc mật khẩu sẽ đăng xuất mọi phiên cũ của tài khoản. Luôn giữ ít nhất một OWNER hoạt động.</p></section><div class='account-grid'>");
        body.append(accountForm(null, csrf));
        for (AdminAccounts.Account account : accounts) body.append(accountForm(account, csrf));
        body.append("</div>");
        return layout("Tài khoản admin", body.toString(), "accounts", csrf);
    }

    private static String accountForm(AdminAccounts.Account account, String csrf) {
        StringBuilder out = new StringBuilder("<section class='panel'><h2>")
                .append(account == null ? "Tạo admin" : h(account.username())).append("</h2>")
                .append("<form method='post' action='/admin/accounts'><input type='hidden' name='csrf' value='")
                .append(h(csrf)).append("'><input type='hidden' name='id' value='")
                .append(account == null ? 0 : account.id()).append("'>");
        if (account == null) out.append("<label>Tài khoản<input name='username' minlength='3' maxlength='32' pattern='[A-Za-z0-9_]+' required></label>");
        out.append("<label>").append(account == null ? "Mật khẩu" : "Mật khẩu mới (để trống nếu giữ nguyên)")
                .append("<input type='password' name='password' minlength='8' maxlength='72' autocomplete='new-password'")
                .append(account == null ? " required" : "").append("></label><label>Vai trò<select name='role'>");
        for (AdminAccounts.Role role : AdminAccounts.Role.values()) out.append("<option")
                .append(role == (account == null ? AdminAccounts.Role.VIEWER : account.role()) ? " selected" : "")
                .append(">").append(role).append("</option>");
        out.append("</select></label><label>Trạng thái<select name='enabled'><option value='true'")
                .append(account == null || account.enabled() ? " selected" : "").append(">Hoạt động</option><option value='false'")
                .append(account != null && !account.enabled() ? " selected" : "").append(">Vô hiệu hóa</option></select></label>")
                .append("<label>Lý do<input name='reason' maxlength='500' required></label><button class='btn btn-primary'>Lưu tài khoản</button></form></section>");
        return out.toString();
    }

    static String rooms(AdminOperations.Snapshot snapshot, String state, String csrf, String username) {
        if (!java.util.Set.of("ACTIVE", "ALL", "PLAYING", "WAITING", "EMPTY").contains(state))
            throw new IllegalArgumentException("Bộ lọc phòng không hợp lệ");
        StringBuilder body = new StringBuilder(pageHeader("Phòng và trận đấu", "Dữ liệu chụp từ game loop, cập nhật mỗi giây", username));
        body.append("<div class='filter-bar'>");
        String[] states = {"ACTIVE", "ALL", "PLAYING", "WAITING", "EMPTY"};
        String[] labels = {"Có người chơi", "Tất cả", "Đang đấu", "Chờ đấu", "Phòng trống"};
        for (int i = 0; i < states.length; i++) body.append("<a class='btn ")
                .append(state.equals(states[i]) ? "btn-primary" : "btn-soft").append("' href='/admin/rooms?state=")
                .append(states[i]).append("'>").append(labels[i]).append("</a>");
        body.append("<a class='btn btn-soft' href='/admin/rooms?state=").append(state).append("'>↻ Làm mới</a></div>");
        body.append("<p class='muted'>Cập nhật: ").append(snapshot.capturedAt() == 0 ? "Chưa có dữ liệu" : date(Instant.ofEpochMilli(snapshot.capturedAt())))
                .append(" · Màn hình chỉ đọc, nhấn Làm mới để lấy trạng thái mới nhất.</p><div class='room-grid'>");
        int count = 0;
        for (AdminOperations.Board board : snapshot.boards()) {
            if (state.equals("ACTIVE") ? board.members().isEmpty() : !state.equals("ALL") && !state.equals(board.state())) continue;
            count++;
            body.append("<article class='panel'><div class='panel-head'><div><p class='eyebrow'>PHÒNG ")
                    .append(board.room()).append(" / BÀN ").append(board.board()).append("</p><h2>").append(h(board.name()))
                    .append("</h2></div><span class='badge ").append(board.state().equals("PLAYING") ? "badge-online" : "")
                    .append("'>").append(board.state().equals("PLAYING") ? "Đang đấu" : board.state().equals("EMPTY") ? "Trống" : "Đang chờ")
                    .append("</span></div><p>Map: ").append(h(board.map())).append(" · ").append(board.members().size()).append("/").append(board.limit()).append(" người</p>");
            if (board.state().equals("PLAYING")) body.append("<p>Lượt ").append(board.turn()).append(" · ")
                    .append(h(board.currentPlayer())).append(" · ").append(board.duration()/60000).append(" phút ")
                    .append(board.duration()/1000%60).append(" giây</p>");
            body.append("<ul class='room-members'>");
            for (AdminOperations.Member member : board.members()) {
                body.append("<li><span>");
                body.append(h(member.name()));
                body.append(" · Đội ").append(member.team()).append("</span><span>")
                        .append(member.hp() == null ? "Chờ" : member.hp() + "/" + member.hpMax() + " HP").append("</span></li>");
            }
            body.append("</ul></article>");
        }
        if (count == 0) body.append(emptyState("Không có phòng phù hợp."));
        body.append("</div>");
        return layout("Phòng và trận", body.toString(), "rooms", csrf);
    }

    private static String css() {
        return """
                textarea{font:inherit;width:100%;padding:12px;border:1px solid #dce2ec;border-radius:10px;min-height:110px}
                .pagination{display:flex;gap:12px;align-items:center;justify-content:flex-end;padding-top:18px}.pagination>span{margin-right:auto;color:#64748b}
                .ability-fields{display:grid;grid-template-columns:1fr 1fr;gap:10px}.picker{display:grid;gap:8px;min-width:0}.picker label{display:grid;gap:6px}.picker-preview{display:flex;align-items:center;gap:10px;min-height:38px}.picker-preview img{width:36px;height:36px;object-fit:contain;image-rendering:pixelated}.picker-count{color:#64748b}.room-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,290px),1fr));gap:16px}.room-grid .panel{margin:0}.room-members{list-style:none;padding:0}.room-members li{display:flex;justify-content:space-between;padding:10px 0;border-bottom:1px solid #edf0f5}.filter-bar{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:20px}.account-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,280px),1fr));gap:18px}.account-grid form{display:grid;gap:12px}.account-grid label{display:grid;gap:6px}input:focus-visible,select:focus-visible,button:focus-visible,a:focus-visible,textarea:focus-visible{outline:2px solid #0d9488;outline-offset:3px}
                :root{font-family:Inter,"Segoe UI",system-ui,sans-serif;color:#172033;background:#f5f7fb;font-synthesis:none}
                *{box-sizing:border-box}body{margin:0;background:#f5f7fb}a{color:#3157d5;text-decoration:none}button,input,select{font:inherit}
                h1,h2,h3,p{margin-top:0}h1{font-size:30px;letter-spacing:-.7px;margin-bottom:6px}h2{font-size:19px;margin-bottom:0}h3{font-size:15px}
                .app-shell{min-height:100vh}.sidebar{position:fixed;inset:0 auto 0 0;width:248px;background:#11182a;color:#dbe3fa;padding:26px 18px;display:flex;flex-direction:column;z-index:2}
                .logo{display:flex;align-items:center;gap:12px;color:white;padding:0 8px 28px}.logo>span,.brand-mark{display:grid;place-items:center;width:42px;height:42px;border-radius:13px;background:linear-gradient(135deg,#6d5dfc,#3e7df5);font-weight:800;box-shadow:0 8px 25px #536dfd55}.logo div{display:flex;flex-direction:column}.logo small{font-size:9px;letter-spacing:1.5px;color:#7f8aa9;margin-top:2px}
                .sidebar nav p{font-size:10px;letter-spacing:1.4px;color:#68738e;padding:0 12px;margin:8px 0}.sidebar nav a{display:flex;align-items:center;gap:12px;color:#9ca8c5;padding:12px;border-radius:10px;margin:4px 0;font-weight:600}.sidebar nav a i{font-style:normal;width:20px;text-align:center;font-size:18px}.sidebar nav a.active,.sidebar nav a:hover{color:#fff;background:#202a42}
                .server-state{margin-top:auto;background:#192238;border:1px solid #27334d;border-radius:12px;padding:12px;display:flex;align-items:center;gap:10px}.server-state>span,.presence{width:9px;height:9px;border-radius:50%;background:#36d695;box-shadow:0 0 0 4px #36d6951c}.server-state div{display:flex;flex-direction:column}.server-state small{color:#8290ad;margin-top:2px}.logout{width:100%;border:0;color:#8d99b7;background:transparent;padding:13px;margin-top:8px;text-align:left;cursor:pointer}
                .main-content{margin-left:248px;padding:34px 38px 60px;max-width:1640px}.page-head{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:26px}.page-head>div>p:last-child{color:#727c91;margin:0}.eyebrow{color:#66728b;font-size:10px;letter-spacing:1.55px;font-weight:800;margin-bottom:7px}.admin-chip{display:flex;align-items:center;gap:10px;background:#fff;border:1px solid #e5e9f1;border-radius:14px;padding:8px 12px}.admin-chip>span{display:grid;place-items:center;width:34px;height:34px;border-radius:10px;background:#e9edff;color:#4b57d9;font-weight:800}.admin-chip div{display:flex;flex-direction:column}.admin-chip small{color:#8a93a6}
                .metric-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin-bottom:22px}.metric{position:relative;overflow:hidden;background:#fff;border:1px solid #e4e8f0;border-radius:16px;padding:20px;display:flex;justify-content:space-between;box-shadow:0 5px 20px #25305008}.metric span,.metric small{display:block;color:#778196}.metric strong{display:block;font-size:27px;margin:8px 0 5px}.metric>b{display:grid;place-items:center;width:46px;height:46px;border-radius:14px;background:#eee;color:#777}.metric.violet>b{background:#eeeaff;color:#755ce5}.metric.blue>b{background:#e7f1ff;color:#3c78db}.metric.green>b{background:#ddf8eb;color:#199765}.metric.amber>b{background:#fff0d9;color:#d38313}
                .panel,.profile-hero,.character-card{background:#fff;border:1px solid #e3e8f0;border-radius:16px;box-shadow:0 5px 20px #25305008}.panel{margin:18px 0;padding:22px}.panel-head{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:18px}.panel-head .eyebrow{margin-bottom:5px}.panel-actions{display:flex;align-items:center;gap:9px}.search-form{display:flex;gap:8px;width:min(430px,100%)}input,select{width:100%;border:1px solid #dce2ec;border-radius:9px;background:#fff;padding:10px 12px;outline:0;color:#263047}input:focus,select:focus{border-color:#6673ef;box-shadow:0 0 0 3px #6673ef18}.btn{display:inline-flex;justify-content:center;align-items:center;border:1px solid transparent;border-radius:9px;padding:9px 13px;font-weight:700;cursor:pointer;white-space:nowrap}.btn-primary{background:#5262df;color:#fff}.btn-create{background:#172033;color:#fff}.btn-soft{background:#f0f2ff;color:#4856c4}.btn-warning{background:#fff4df;color:#a45b05;border-color:#f3d7a5}.btn-danger{background:#d74656;color:#fff}.btn-wide{width:100%}
                .table-wrap{overflow:auto}table{border-collapse:collapse;width:100%;min-width:760px}th{color:#8490a5;font-size:11px;text-transform:uppercase;letter-spacing:.7px;font-weight:700}th,td{text-align:left;padding:13px 11px;border-bottom:1px solid #edf0f5;vertical-align:middle}tbody tr:last-child td{border-bottom:0}tbody tr:hover{background:#fafbfe}.identity{display:flex;align-items:center;gap:11px}.identity div{display:flex;flex-direction:column}.identity small,.block{display:block;color:#8a94a8;margin-top:3px}.avatar{display:grid;place-items:center;flex:0 0 auto;width:36px;height:36px;border-radius:11px;background:linear-gradient(135deg,#e9edff,#dce5ff);color:#5061ca;font-weight:800}.avatar-xl{width:60px;height:60px;border-radius:18px;font-size:22px}.money{font-variant-numeric:tabular-nums;font-weight:650}.align-right{text-align:right}.badge{display:inline-flex;align-items:center;gap:6px;padding:5px 9px;border-radius:999px;background:#eef1f5;color:#657085;font-size:12px;font-weight:700;white-space:nowrap}.badge i{width:6px;height:6px;border-radius:50%;background:#a1a9b8}.badge-online{background:#def8eb;color:#168557}.badge-online i{background:#25bb7d}.badge-danger{background:#ffe6e9;color:#bd3546}.badge-danger i{background:#dd5263}.badge-warning{background:#fff2dc;color:#a66509}.badge-warning i{background:#d99525}.count-pill{background:#f0f2f7;color:#6f7a90;border-radius:99px;padding:6px 10px;font-size:12px}
                .alert{padding:13px 15px;border-radius:11px;margin:-4px 0 18px;font-weight:600}.alert-success{background:#e3f8ed;color:#14784e}.alert-error{background:#ffe7e9;color:#ac3041}.empty{text-align:center;color:#8a94a7;padding:28px}.empty.wide{grid-column:1/-1}.back-link{display:inline-block;margin:0 0 13px;color:#6b7690;font-weight:650}.profile-hero{padding:22px 25px;display:flex;justify-content:space-between;align-items:center;gap:25px}.profile-main,.profile-title{display:flex;align-items:center;gap:13px}.profile-title{gap:10px}.profile-title h2{font-size:24px}.profile-main p{color:#7a8498;margin:5px 0 0}.profile-stats{display:flex;gap:32px}.profile-stats div{display:flex;flex-direction:column}.profile-stats span{font-size:12px;color:#8791a5}.profile-stats strong{margin-top:4px;font-size:18px}.tabs{display:flex;gap:6px;overflow:auto;padding:18px 2px 4px}.tabs a{color:#707b91;padding:10px 13px;border-radius:9px;white-space:nowrap;font-weight:650}.tabs a:hover,.tabs a.active{background:#fff;color:#4e5bd3;box-shadow:0 2px 10px #27345a0c}.tabs a span{background:#eef1f6;padding:2px 6px;border-radius:99px;font-size:10px;margin-left:3px}
                .detail-grid{display:grid;grid-template-columns:minmax(0,1.65fr) minmax(280px,.7fr);gap:18px}.content-stack>.panel:first-child{margin-top:14px}.info-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0;margin:0}.info-grid div{padding:13px 0;border-bottom:1px solid #eef1f5}.info-grid div:nth-child(odd){padding-right:18px}.info-grid dt,.compact-info dt{color:#8a94a8;font-size:12px}.info-grid dd,.compact-info dd{font-weight:650;margin:5px 0 0;overflow-wrap:anywhere}.ban-note{margin-top:16px;padding:13px;background:#fff2f3;border-radius:10px;color:#a43d49}.mini-stat-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:10px}.mini-stat-grid div{background:#f7f8fb;border-radius:11px;padding:15px;display:flex;flex-direction:column}.mini-stat-grid strong{font-size:21px}.mini-stat-grid span{color:#7d879b;font-size:12px;margin-top:4px}.action-panel h2{margin-bottom:16px}.action-form{border-top:1px solid #edf0f5;padding:16px 0 0;margin-top:16px;display:grid;gap:9px}.action-form h3{margin-bottom:1px}.form-row{display:grid;grid-template-columns:.65fr 1fr;gap:8px}.manage-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:13px}.manage-form{display:grid;align-content:start;gap:9px;background:#f7f8fc;border:1px solid #e7eaf1;border-radius:12px;padding:16px}.manage-form h3,.manage-form p{margin-bottom:2px}.manage-form p{color:#7c879b;font-size:12px;line-height:1.45}.danger-zone{background:#fff8f8;border-color:#f4dadd}.inline-admin-form{display:grid;grid-template-columns:minmax(240px,1.2fr) minmax(220px,1fr) auto;gap:9px;padding:14px;background:#f7f8fc;border-radius:12px;margin-bottom:18px}.inventory-editor{grid-template-columns:.55fr .55fr .65fr 1fr auto}.remove-form{display:grid;grid-template-columns:minmax(110px,1fr) auto;gap:6px;min-width:210px}.remove-form input{padding:7px 8px}.remove-form .btn{padding:7px 9px}.card-admin-form{display:grid;gap:8px;margin-top:15px;padding-top:15px;border-top:1px solid #edf0f5}.card-admin-form label{display:grid;gap:5px;color:#7d879b;font-size:11px;font-weight:700}.audit-data{margin-top:6px;color:#68738b}.audit-data code{display:block;margin-top:5px;white-space:normal;overflow-wrap:anywhere}.create-layout{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(280px,.55fr);gap:18px}.create-panel,.create-note{margin-top:0}.create-form{display:grid;gap:17px}.create-form label{display:grid;gap:7px;font-weight:700;font-size:13px}.create-form label span{font-weight:400;color:#8993a6;font-size:12px}.create-grid{display:grid;grid-template-columns:1fr 1fr;gap:13px}.form-submit{display:flex;justify-content:flex-end;gap:9px;border-top:1px solid #edf0f5;padding-top:18px}.create-note{align-self:start;background:linear-gradient(145deg,#18213b,#11182a);color:#fff;border:0}.create-note .item-icon{background:#ffffff18;color:#76e6b2}.create-note h3{font-size:18px;margin:16px 0 8px}.create-note p,.create-note li{color:#aeb9d1;line-height:1.55}.create-note ul{padding-left:19px;margin-bottom:0}
                .character-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin-top:14px}.character-card{padding:19px}.character-card.selected{border-color:#8994f0;box-shadow:0 6px 24px #5e6aeb18}.character-head{display:flex;align-items:center;gap:10px}.character-head>div{flex:1}.character-head h3{margin:0 0 3px}.character-head div span{font-size:12px;color:#8993a7}.character-level{display:flex;justify-content:space-between;background:#f7f8fc;padding:10px 12px;border-radius:10px;margin:16px 0}.character-level span{color:#7f899d}.compact-info{margin:0}.compact-info div{border-top:1px solid #eef1f5;padding:10px 0}.compact-info dd code,td code{font-size:11px;color:#526078;white-space:normal}.inventory-grid,.friend-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:11px}.inventory-card,.friend-card{border:1px solid #e7eaf1;border-radius:12px;padding:13px;display:flex;align-items:center;gap:11px;color:#253047}.inventory-card>div,.friend-card>div{flex:1;min-width:0}.inventory-card strong,.inventory-card small,.friend-card strong,.friend-card small{display:block}.inventory-card small,.friend-card small{color:#8a94a7;margin-top:3px}.inventory-card p{font-size:12px;color:#707b8f;margin:5px 0 0}.item-icon{display:grid;place-items:center;width:38px;height:38px;border-radius:10px;background:#f0edff;color:#735bd7}.inventory-card>b{color:#4d59c8}.friend-card:hover{border-color:#9ba4ec;background:#fbfbff}.mission-list{display:grid;gap:11px}.mission-row{border:1px solid #e7eaf1;border-radius:12px;padding:15px}.mission-top,.mission-foot{display:flex;justify-content:space-between;gap:15px}.mission-top small{display:block;color:#8a94a7;margin-top:4px}.progress{height:7px;background:#edf0f5;border-radius:99px;overflow:hidden;margin:13px 0 8px}.progress span{display:block;height:100%;background:linear-gradient(90deg,#5968e5,#7c67df);border-radius:inherit}.mission-foot{font-size:12px;color:#737e92}.positive{color:#158557;font-weight:750}.negative{color:#c33d4c;font-weight:750}
                .login-page{min-height:100vh;display:grid;place-items:center;background:radial-gradient(circle at 10% 10%,#353e7a 0,transparent 34%),#11172a;padding:25px}.login-shell{width:min(980px,96vw);display:grid;grid-template-columns:1.1fr .9fr;background:#fff;border-radius:22px;overflow:hidden;box-shadow:0 30px 80px #080c1a66}.login-brand{background:linear-gradient(145deg,#202b51,#151d35);color:#fff;padding:60px}.brand-mark{margin-bottom:55px}.login-brand .eyebrow{color:#8fa0d0}.login-brand h1{font-size:38px;line-height:1.15}.login-brand>p:not(.eyebrow){color:#aeb9d5;line-height:1.7}.login-points{display:grid;gap:12px;color:#cdd5e9;margin-top:35px}.login-points span::first-letter{color:#5ee0a5}.login-card{padding:60px 50px;align-self:center}.login-card h2{font-size:28px;margin-bottom:8px}.muted{color:#7e8799}.stack-form{display:grid;gap:14px;margin-top:25px}.stack-form label{display:grid;gap:7px;font-weight:650;font-size:13px}.stack-form button{margin-top:5px;padding:12px}.error-card{background:#fff;border-radius:18px;padding:40px;text-align:center;width:min(480px,92vw);margin:10vh auto}.error-code{display:grid;place-items:center;width:55px;height:55px;border-radius:50%;background:#ffe7e9;color:#c53e4e;font-size:28px;font-weight:800;margin:0 auto 17px}.error-card p{color:#747f93;margin-bottom:22px}
                .btn-primary{background:#087f75}.btn-soft{background:#e8f5f2;color:#12685e}.logo>span,.brand-mark{background:linear-gradient(135deg,#0d9488,#155e75)}.search-form{width:auto;flex-wrap:wrap}.search-form input{min-width:190px;flex:2}.search-form select{min-width:140px;flex:1}.inventory-editor{grid-template-columns:minmax(240px,2fr) 1fr 1fr auto}.sidebar{overflow-y:auto}.character-grid{grid-template-columns:repeat(auto-fit,minmax(300px,1fr))}.manage-grid{grid-template-columns:repeat(auto-fit,minmax(250px,1fr))}.panel{box-shadow:0 6px 24px #17203306}
                @media(max-width:1100px){.metric-grid{grid-template-columns:repeat(2,1fr)}.character-grid,.inventory-grid,.friend-grid{grid-template-columns:repeat(2,1fr)}.detail-grid,.create-layout{grid-template-columns:1fr}.manage-grid{grid-template-columns:repeat(2,1fr)}.inline-admin-form,.inventory-editor{grid-template-columns:1fr 1fr}.profile-stats{gap:16px}}
                @media(max-width:760px){.sidebar{position:static;width:auto;padding:14px 17px;flex-direction:row;align-items:center;gap:8px}.logo{padding:0;margin-right:auto}.logo small,.sidebar nav p,.server-state,.logout{display:none}.sidebar nav{display:flex;flex-wrap:wrap}.sidebar{flex-wrap:wrap}.sidebar nav{width:100%}.sidebar nav a{margin:0;padding:9px}.sidebar nav a i{display:none}.main-content{margin-left:0;padding:22px 16px}.page-head{align-items:flex-start}.admin-chip{display:none}.metric-grid{grid-template-columns:1fr 1fr}.panel{padding:17px}.panel-head,.profile-hero{align-items:flex-start;flex-direction:column}.panel-actions{width:100%;align-items:stretch;flex-direction:column}.search-form{width:100%;max-width:none}.profile-stats{width:100%;justify-content:space-between}.character-grid,.inventory-grid,.friend-grid,.manage-grid{grid-template-columns:1fr}.inline-admin-form,.inventory-editor{grid-template-columns:1fr}.mini-stat-grid{grid-template-columns:repeat(2,1fr)}.info-grid,.create-grid{grid-template-columns:1fr}.info-grid div:nth-child(odd){padding-right:0}.login-shell{grid-template-columns:1fr}.login-brand{display:none}.login-card{padding:38px 28px}.form-row{grid-template-columns:1fr}}
                @media(max-width:480px){.metric-grid{grid-template-columns:1fr}.profile-stats{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.profile-stats strong{font-size:14px}.page-head h1{font-size:25px}}
                """;
    }
}
