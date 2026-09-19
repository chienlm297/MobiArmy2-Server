package mobiarmy.admin;

import java.util.*;
import mobiarmy.server.BotPolicy;

final class BotView {
    static String render(AdminBots.Snapshot snapshot, List<AdminBots.Job> jobs,
                         Map<String,String> query, String csrf, String username) {
        String search = query.getOrDefault("q", "").trim();
        String state = query.getOrDefault("state", "ALL");
        if (!Set.of("ALL", "IDLE", "WAITING", "PLAYING").contains(state)) throw new IllegalArgumentException("Trạng thái bot không hợp lệ");
        String term = search.toLowerCase(Locale.ROOT);
        List<AdminBots.Row> rows = snapshot.rows().stream().filter(r ->
                (state.equals("ALL") || state.equals(r.state()))
                && (term.isEmpty() || String.valueOf(r.id()).equals(term) || r.name().toLowerCase(Locale.ROOT).contains(term))).toList();
        int pages = Math.max(1, (rows.size() + 24) / 25);
        int page = Math.max(1, Math.min(pages, Integer.parseInt(query.getOrDefault("page", "1"))));
        StringBuilder body = new StringBuilder("<header class='page-head'><div><p class='eyebrow'>MOBIARMY ADMIN</p><h1>Quản lý bot</h1><p>Người vận hành: ")
                .append(h(username)).append("</p></div><a class='btn btn-soft' href='/admin/bots?q=").append(AdminView.url(search))
                .append("&amp;state=").append(state).append("&amp;page=").append(page).append("'>Làm mới</a></header>");
        if (query.containsKey("message")) body.append("<div class='alert'>").append(h(query.get("message"))).append("</div>");
        body.append("<section class='panel'><h2>Trạng thái đội bot</h2><p>Tổng: <strong>").append(snapshot.rows().size()).append("</strong>");
        for (String s : List.of("IDLE", "WAITING", "PLAYING")) body.append(" · ").append(label(s)).append(": ")
                .append(snapshot.rows().stream().filter(r -> r.state().equals(s)).count());
        body.append("</p><p class='muted'>Cập nhật: ").append(snapshot.time() == 0 ? "Chờ game loop" : h(new java.sql.Timestamp(snapshot.time()).toString()))
                .append(". Bấm Làm mới để lấy dữ liệu và kết quả lệnh mới nhất.</p><p>Bot và chế độ riêng chỉ lưu trong RAM. Khi khởi động lại, server tạo bot theo BOT_COUNT và cấu hình môi trường.</p>")
                .append("<p>OWNER/ADMIN được thay đổi. Các quyền còn lại chỉ xem. Rời phòng và xóa chỉ áp dụng khi bot không trong trận và không bị khóa xử lý.</p></section>");
        body.append("<section class='panel'><h2>Tạo bot</h2><form method='post' action='/admin/bots' class='stack-form'>")
                .append(hidden(csrf)).append("<input type='hidden' name='action' value='create'>")
                .append("<div class='form-row'><label>Tên bot<input name='name' minlength='3' maxlength='32' required></label>")
                .append("<label>Nhân vật (ID 0–9)<input type='number' name='glass' min='0' max='9' value='0' required></label>")
                .append("<label>EXP<input type='number' name='exp' min='0' max='49999999' value='0' required></label></div>")
                .append("<p class='muted'>Tạo từng bot; trang bị ngẫu nhiên theo cấp. Giới hạn tổng 10.000 bot.</p>")
                .append(reason()).append("<button class='btn btn-primary'>Tạo bot</button></form></section>");
        body.append("<section class='panel'><h2>Danh sách bot</h2><form method='get' action='/admin/bots' class='search-form'>")
                .append("<input name='q' aria-label='Tìm bot' placeholder='Tên hoặc ID âm của bot' value='").append(h(search)).append("'><select name='state' aria-label='Trạng thái'>");
        for (String s : List.of("ALL", "IDLE", "WAITING", "PLAYING")) body.append("<option value='").append(s).append("'")
                .append(s.equals(state) ? " selected" : "").append(">").append(label(s)).append("</option>");
        body.append("</select><button class='btn btn-primary'>Tìm kiếm</button></form><div class='table-wrap'><table><thead><tr>")
                .append("<th>Bot</th><th>Nhân vật</th><th>Trạng thái</th><th>Phòng / bàn</th><th>Mục tiêu</th><th>Thao tác</th></tr></thead><tbody>");
        for (AdminBots.Row r : rows.subList((page-1)*25, Math.min(page*25, rows.size()))) {
            body.append("<tr><td><strong>").append(h(r.name())).append("</strong><small class='block'>#").append(r.id())
                    .append("</small></td><td>#").append(r.glass()).append(" · Lv.").append(r.level())
                    .append("</td><td>").append(label(r.state())).append(r.locked() ? " · Đang xử lý" : "")
                    .append("</td><td>").append(h(r.room())).append("</td><td>").append(h(r.targetMode()))
                    .append("<small class='block'>").append(h(r.decision())).append("</small><small class='block'>").append(h(r.metrics())).append("</small><small class='block'>").append(h(r.inventory())).append("</small>")
                    .append("</td><td><form method='post' action='/admin/bots' class='stack-form'>").append(hidden(csrf))
                    .append("<input type='hidden' name='bot_id' value='").append(r.id()).append("'>")
                    .append("<select name='action' aria-label='Thao tác bot'><option value='target'>Đổi cách chọn mục tiêu</option>");
            String disabled = r.locked() || r.state().equals("PLAYING") ? " disabled" : "";
            body.append("<option value='leave'").append(disabled).append(">Rời phòng chờ</option><option value='remove'").append(disabled)
                    .append(">Xóa bot</option></select><label>Mục tiêu (khi đổi chế độ)<select name='mode'><option value='DEFAULT'>Theo cấu hình server</option>")
                    .append("<option value='RANDOM'>Ngẫu nhiên</option><option value='LOW_HP'>Ít HP nhất</option></select></label>")
                    .append(reason()).append("<button class='btn btn-soft'>Gửi lệnh</button></form>")
                    .append(policyForm(r,csrf)).append("</td></tr>");
        }
        if (rows.isEmpty()) body.append("<tr><td colspan='6'>Không tìm thấy bot phù hợp.</td></tr>");
        body.append("</tbody></table></div><div class='pagination'><span>").append(rows.size()).append(" bot · Trang ")
                .append(page).append(" / ").append(pages).append("</span>");
        for (int next : new int[]{page-1, page+1}) if (next >= 1 && next <= pages) body.append("<a class='btn btn-soft' href='/admin/bots?q=")
                .append(AdminView.url(search)).append("&amp;state=").append(state).append("&amp;page=").append(next)
                .append("'>").append(next < page ? "← Trước" : "Tiếp →").append("</a>");
        body.append("</div></section><section class='panel'><h2>Lệnh gần đây</h2><p>Đã nhận lệnh chưa có nghĩa là đã thực hiện. Hàng đợi tối đa 32 lệnh; lệnh chờ quá 10 giây bị từ chối khi được xử lý.</p>")
                .append("<div class='table-wrap'><table><thead><tr><th>Mã lệnh / admin</th><th>Thao tác</th><th>Trạng thái</th><th>Kết quả</th></tr></thead><tbody>");
        for (AdminBots.Job job : jobs) body.append("<tr><td>").append(h(job.id())).append("<small class='block'>").append(h(job.actor()))
                .append("</small></td><td>").append(h(job.action())).append("</td><td>").append(h(job.status())).append("</td><td>")
                .append(h(job.result())).append("</td></tr>");
        return body.append("</tbody></table></div><p class='muted'>Giữ 100 lệnh gần đây trong bộ nhớ. Audit yêu cầu và kết quả lưu ở lịch sử quản trị.</p></section>").toString();
    }
    private static String policyForm(AdminBots.Row r,String csrf) {
        BotPolicy p=r.policy();
        StringBuilder s=new StringBuilder("<details><summary>Chiến thuật và item</summary><form method='post' action='/admin/bots' class='stack-form'>")
                .append(hidden(csrf)).append("<input type='hidden' name='action' value='policy'><input type='hidden' name='bot_id' value='").append(r.id()).append("'>");
        s.append(flag("enabled","AI chiến thuật",p.enabled())).append(flag("movement","Di chuyển",p.movement())).append(flag("items","Dùng item",p.items()));
        s.append("<label>Phong cách<select name='preset'>");
        for(var v:BotPolicy.Preset.values()) s.append("<option").append(v==p.preset()?" selected":"").append(">").append(v).append("</option>");
        s.append("</select></label>").append(number("heal","Ngưỡng HP (%)",p.healPercent(),1,90))
                .append(number("steps","Bước tối đa",p.maxSteps(),0,60)).append(number("think","Suy nghĩ (ms)",p.thinkMs(),0,2000))
                .append(number("budget","Ngân sách lượt (ms)",p.turnMs(),3000,10000))
                .append("<label>Item cho phép<input name='allowed' value='")
                .append(h(p.allowed().stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))))
                .append("'></label><p>0: HP · 1: bay · 2: bắn x2 · 3: đi x2 · 5: ngưng gió · 6: phá đất · 10: HP đội · 100: POW. Cấu hình áp dụng từ lượt sau, lưu RAM.</p>")
                .append(reason()).append("<button class='btn btn-primary'>Lưu chiến thuật</button></form>");
        s.append("<form method='post' action='/admin/bots' class='stack-form'>").append(hidden(csrf))
                .append("<input type='hidden' name='action' value='loadout'><input type='hidden' name='bot_id' value='").append(r.id())
                .append("'><label>4 slot, -1 là trống<input name='slots' value='0,1,2,3' maxlength='64' required></label>")
                .append(number("quantity","Cấp thêm mỗi loại đặc biệt",0,0,99))
                .append("<p>Chỉ khi ngoài trận và chưa sẵn sàng. Không tự nạp lại giữa trận. 0/1 theo kho cơ bản; slot đặc biệt tiêu hao kho.</p>")
                .append(reason()).append("<button class='btn btn-soft'>Đặt bộ item / cấp kho</button></form></details>");
        return s.toString();
    }
    private static String flag(String key,String label,boolean value) {
        return "<label>"+label+"<select name='"+key+"'><option value='true'"+(value?" selected":"")+">Bật</option><option value='false'"+(!value?" selected":"")+">Tắt</option></select></label>";
    }
    private static String number(String key,String label,int value,int min,int max) {
        return "<label>"+label+"<input type='number' name='"+key+"' value='"+value+"' min='"+min+"' max='"+max+"' required></label>";
    }
    private static String hidden(String csrf) { return "<input type='hidden' name='csrf' value='" + h(csrf) + "'>"; }
    private static String reason() { return "<label>Lý do<input name='reason' maxlength='500' required></label>"; }
    private static String label(String state) { return switch(state) {
        case "IDLE" -> "Chưa vào phòng"; case "WAITING" -> "Phòng chờ"; case "PLAYING" -> "Đang chơi"; default -> "Tất cả";
    }; }
    private static String h(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
