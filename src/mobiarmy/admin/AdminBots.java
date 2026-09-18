package mobiarmy.admin;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import com.google.gson.Gson;
import mobiarmy.server.Bot;
import mobiarmy.server.BotSettings;
import mobiarmy.server.Server;

/** HTTP reads immutable snapshots and submits commands. Only the game loop touches bots. */
public final class AdminBots {
    public record Row(int id, String name, int glass, int level, String state, String room,
                      String targetMode, boolean locked) {}
    public record Snapshot(long time, List<Row> rows) {}
    record Job(String id, String actor, String action, String status, String result) {}
    record Request(String id, AdminAccounts.Account actor, String action, int botId,
                   String name, int glass, int exp, String mode, String reason, long expires) {}
    private static final ArrayBlockingQueue<Request> QUEUE = new ArrayBlockingQueue<>(32);
    private static final LinkedHashMap<String, Job> JOBS = new LinkedHashMap<>();
    private static volatile Snapshot snapshot = new Snapshot(0, List.of());
    private static final Gson JSON = new Gson();

    static Snapshot snapshot() { return snapshot; }
    static synchronized List<Job> jobs() {
        ArrayList<Job> result = new ArrayList<>(JOBS.values());
        Collections.reverse(result);
        return result;
    }
    private static synchronized void status(Request r, String status, String result) {
        JOBS.put(r.id(), new Job(r.id(), r.actor().username(), r.action(), status, result));
        while (JOBS.size() > 100) {
            String done = JOBS.entrySet().stream().filter(e -> !Set.of("QUEUED", "RUNNING").contains(e.getValue().status()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (done == null) break;
            JOBS.remove(done);
        }
    }
    static synchronized String submit(AdminAccounts.Account actor, Map<String,String> form) {
        if (!actor.role().allows("/admin/bots")) throw new SecurityException("Không có quyền quản lý bot");
        String action = form.getOrDefault("action", "");
        if (!Set.of("create", "leave", "remove", "target").contains(action))
            throw new IllegalArgumentException("Thao tác bot không hợp lệ");
        String reason = form.getOrDefault("reason", "").trim();
        if (reason.isEmpty() || reason.length() > 500) throw new IllegalArgumentException("Nhập lý do, tối đa 500 ký tự");
        int id = action.equals("create") ? 0 : Integer.parseInt(form.getOrDefault("bot_id", ""));
        String name = form.getOrDefault("name", "").trim();
        int glass = 0, exp = 0;
        if (action.equals("create")) {
            if (!name.matches("[\\p{L}\\p{N}_ -]{3,32}")) throw new IllegalArgumentException("Tên bot cần 3–32 chữ, số, dấu cách, _ hoặc -");
            glass = Integer.parseInt(form.getOrDefault("glass", "0"));
            exp = Integer.parseInt(form.getOrDefault("exp", "0"));
            if (glass < 0 || glass > 9 || exp < 0 || exp > 49999999) throw new IllegalArgumentException("Nhân vật 0–9, EXP 0–49.999.999");
        }
        String mode = form.getOrDefault("mode", "DEFAULT");
        if (action.equals("target") && !Set.of("DEFAULT", "RANDOM", "LOW_HP").contains(mode))
            throw new IllegalArgumentException("Chế độ mục tiêu không hợp lệ");
        Request r = new Request(UUID.randomUUID().toString(), actor, action, id, name, glass, exp, mode, reason,
                System.currentTimeMillis() + 10000);
        status(r, "QUEUED", "Đang chờ game loop");
        if (!QUEUE.offer(r)) {
            JOBS.remove(r.id());
            throw new IllegalArgumentException("Hàng đợi đầy, vui lòng thử lại sau");
        }
        return r.id();
    }

    public static void tick() {
        Request r = QUEUE.poll();
        if (r != null) execute(r);
        if (r != null || System.currentTimeMillis() - snapshot.time() >= 1000) capture();
    }
    private static void execute(Request r) {
        status(r, "RUNNING", "Đang xử lý");
        try {
            if (System.currentTimeMillis() > r.expires()) throw new IllegalArgumentException("Lệnh hết hạn, chưa thực hiện");
            AdminAccounts.Account actor = AdminAccounts.get(r.actor().id());
            if (actor == null || !actor.enabled() || actor.version() != r.actor().version()
                    || !actor.role().allows("/admin/bots")) throw new SecurityException("Quyền admin đã thay đổi, chưa thực hiện");
            validateCurrent(r);
            // Durable intent must be written before touching in-memory state.
            audit(r, "BOT_REQUEST", "Yêu cầu " + r.action(), before(r));
            String result = apply(r);
            try {
                audit(r, "BOT_RESULT", result, null);
                status(r, "DONE", result);
            } catch (Exception auditError) {
                auditError.printStackTrace();
                status(r, "DONE_AUDIT_ERROR", result + " — ghi audit kết quả thất bại; không gửi lại thao tác");
            }
        } catch (Exception e) {
            e.printStackTrace();
            status(r, "FAILED", e instanceof IllegalArgumentException || e instanceof SecurityException
                    ? e.getMessage() : "Lỗi xử lý, kiểm tra log server và trạng thái bot trước khi thử lại");
        }
    }
    private static String before(Request r) {
        Bot b = Bot.findById(r.botId());
        return b == null ? null : JSON.toJson(row(b));
    }
    private static void audit(Request r, String action, String detail, String before) throws Exception {
        try (Connection c = Server.dbManager.getConnection()) {
            AdminService.insertAudit(c, r.actor().username(), action, null,
                    "Lệnh " + r.id() + " · bot " + r.botId() + " · " + detail,
                    r.reason(), before, null);
        }
    }
    static void validateCurrent(Request r) {
        if (r.action().equals("create")) {
            if (Bot.bots.size() >= 10000) throw new IllegalArgumentException("Đã đạt giới hạn 10.000 bot");
            if (Bot.bots.stream().anyMatch(b -> b.name.equalsIgnoreCase(r.name())))
                throw new IllegalArgumentException("Tên bot đã tồn tại");
        } else {
            Bot b = Bot.findById(r.botId());
            if (b == null || b.remove) throw new IllegalArgumentException("Bot không còn tồn tại");
            if (!r.action().equals("target") && (b.lock || b.roomWait != null && b.roomWait.started))
                throw new IllegalArgumentException("Bot đang trong trận hoặc xử lý hành động; hãy thử sau khi kết thúc");
        }
    }
    static String apply(Request r) {
        Bot b = Bot.findById(r.botId());
        switch (r.action()) {
            case "create" -> {
                Bot created = Bot.addBot(r.name(), r.glass(), r.exp(), null);
                return "Đã tạo bot " + created.name + " (#" + created.id + ")";
            }
            case "leave" -> { b.invited = null; b.leaveRoomWait(); return "Bot " + b.id + " đã rời phòng chờ"; }
            case "remove" -> { b.remove(); return "Đã xóa bot " + b.id; }
            case "target" -> {
                b.targetModeOverride = r.mode().equals("DEFAULT") ? null : BotSettings.TargetMode.valueOf(r.mode());
                return "Bot " + b.id + " chọn mục tiêu: " + b.effectiveTargetMode();
            }
            default -> throw new IllegalArgumentException("Thao tác không hợp lệ");
        }
    }
    private static Row row(Bot b) {
        return new Row(b.id, b.name, b.selectGlass, b.glass().level,
                b.roomWait == null ? "IDLE" : b.roomWait.started ? "PLAYING" : "WAITING",
                b.roomWait == null ? "—" : (b.roomWait.roomID & 255) + " / " + (b.roomWait.boardID & 255),
                b.effectiveTargetMode().name() + (b.targetModeOverride == null ? " (mặc định)" : ""), b.lock);
    }
    private static void capture() {
        List<Row> rows = new ArrayList<>();
        for (Bot b : Bot.bots) rows.add(row(b));
        snapshot = new Snapshot(System.currentTimeMillis(), List.copyOf(rows));
    }
}
