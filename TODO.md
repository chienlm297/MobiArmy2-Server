# Web Admin Roadmap

## MVP quản trị

- [x] Chạy web admin cùng JVM với game server.
- [x] Cấu hình admin qua biến môi trường.
- [x] Đăng nhập bằng session cookie và CSRF token.
- [x] Dashboard trạng thái server, session và người chơi online.
- [x] Tìm user theo ID, username hoặc tên nhân vật.
- [x] Tạo user mới với dữ liệu mặc định, mật khẩu BCrypt và audit log.
- [x] Cộng/trừ xu và lượng, đồng bộ user online và database.
- [x] Ghi lịch sử thay đổi số dư.
- [x] Kick user đang online.
- [x] Ban vĩnh viễn hoặc theo số phút.
- [x] Unban và chặn user bị ban tại bước đăng nhập game.
- [x] Audit log cho wallet, kick, ban và unban.
- [x] Broadcast thông báo toàn server từ web admin.
- [x] Trang chi tiết người chơi với tổng quan, nhân vật, trang bị, item, nhiệm vụ và bạn bè.
- [x] Lịch sử thay đổi số dư và audit log theo từng người chơi.
- [x] Đổi/reset mật khẩu game user bằng BCrypt.
- [x] Khóa/mở khóa đăng nhập nhanh và chặn ngay tại luồng đăng nhập game.
- [x] Đổi tên nhân vật, kiểm tra trùng tên và đồng bộ chỉ mục trong RAM.
- [x] Xóa mềm/khôi phục tài khoản, giữ nguyên toàn bộ dữ liệu game.
- [x] Cộng/trừ cup; chỉnh EXP, cấp độ suy ra, điểm và ability nhân vật.
- [x] Thêm/trừ item, đồ đặc biệt; thêm/xóa trang bị.
- [x] Audit có lý do bắt buộc, request ID và JSON dữ liệu trước/sau.

## An toàn và phân quyền

- [x] Lưu tài khoản admin trong bảng `admin_account` bằng BCrypt.
- [x] Role `OWNER`, `ADMIN`, `MODERATOR`, `VIEWER`.
- [ ] Rate limit đăng nhập admin và khóa tạm khi sai nhiều lần.
- [ ] Bắt buộc HTTPS khi bind admin ra ngoài localhost.
- [ ] Idempotency key cho thao tác wallet.
- [ ] Giới hạn số tiền theo role và xác nhận thao tác giá trị lớn.
- [ ] Ban IP và giới hạn số session theo IP.

## Nền tảng server

- [ ] Thay collection mutable trong `SessionManager` bằng API snapshot thread-safe hoàn chỉnh.
- [ ] Tạo command queue riêng cho thao tác admin trên game state.
- [ ] Refactor `saveUsers()` thành `saveUser()` có transaction.
- [ ] Không xóa user khỏi `SessionManager` sau khi lưu.
- [ ] Thay các `catch` rỗng bằng structured logging.
- [ ] Thêm trạng thái `STARTING`, `RUNNING`, `STOPPING`, `FAILED`.
- [ ] Maintenance mode và graceful shutdown từ admin.
- [ ] Metrics game tick, JVM, HikariCP, phòng và trận đấu.

## Build và database

- [ ] Chuyển build từ Ant/JAR thủ công sang Gradle.
- [ ] Dùng Flyway thay cho tự tạo bảng lúc khởi động.
- [ ] Tách tài khoản database riêng, bỏ `root` mật khẩu rỗng.
- [ ] Backup/restore database từ quy trình vận hành bên ngoài admin.
- [x] Test tích hợp các route admin và migration schema với MySQL thật.
- [ ] Test tự động game session giả lập cho đồng bộ user đang online.

## Giao diện nâng cao

- [x] Phân trang và bộ lọc user.
- [ ] Log realtime bằng SSE/WebSocket.
- [x] Xem phòng/trận: map, thành viên, HP, lượt, thời lượng qua snapshot game loop.
- [ ] Điều khiển phòng/trận từ web admin.
- [ ] Lịch sử đăng nhập, IP và phiên bản client.
- [x] Làm mới giao diện responsive cho dashboard, đăng nhập và trang người chơi.
- [ ] Thông báo kết quả không cần tải lại trang.

## Bổ sung ưu tiên cao

- [x] Form chỉ số có tên, kiểm tra giới hạn phía server.
- [x] Catalog vật phẩm/trang bị tìm theo tên/ID; ảnh xem trước cho item.
- [x] Thu hồi session khi đổi quyền, mật khẩu hoặc vô hiệu hóa admin.
- [x] Bảo vệ OWNER cuối cùng và chặn moderator khôi phục tài khoản đã xóa mềm.
- [x] Bổ sung schema admin_account vào army.sql và migration lúc khởi động.
- [x] 118 kiểm tra HTTP/SQL trên MySQL thật, gồm 211 user để kiểm tra phân trang.
- [x] 11 kiểm tra snapshot phòng/trận và 7 kiểm tra bootstrap/broadcast với session mô phỏng.
- [x] Chrome: tìm vật phẩm không dấu, ảnh preview và 15 tổ hợp trang/kích thước 1440, 390, 320 px.
- [ ] Kiểm thử nghiệm thu bằng hai client game thật: chơi trận và nhận broadcast.

## Bot: vòng đời và hành vi

- [x] Sửa remove, dọn chỉ mục, chống đăng ký trùng và xử lý xóa bot bị lock.
- [x] Reset timer theo lần vào phòng/kết thúc trận; không rời giữa trận do timeout.
- [x] Cấu hình BOT_COUNT, AUTO_JOIN, REQUIRE_HUMAN, thời gian chờ và TARGET_MODE.
- [x] Thêm LOW_HP; lọc mục tiêu chết/rời trận, xóa danh sách cũ mỗi lượt.
- [x] Sửa hướng di chuyển nhỏ; bỏ lời mời xung đột khi đã ở phòng.
- [x] Build và 40 kiểm tra bot bằng fixture, không tác động DB.
- [ ] Nghiệm thu bot với client thật, đo CPU theo số bot.
- [ ] Executor/timeout/cancellation cho tính đường đạn và công bố kết quả thread-safe.
- [ ] Độ khó, tìm vị trí bắn có địa hình hợp lệ, giới hạn thử ép ngọc.
- [x] Trang quản trị bot: tìm/lọc/phân trang, tạo, rời phòng chờ, xóa và chọn mục tiêu riêng.
- [x] Hàng đợi bot có giới hạn/hạn chờ, kiểm tra quyền lại, audit yêu cầu/kết quả, snapshot chỉ đọc.
- [x] Chặn xóa/rời phòng khi bot đã vào trận; giới hạn vòng ép ngọc khi tạo bot.
- [x] 15 kiểm tra quản trị bot; 156 kiểm tra HTTP/SQL toàn admin; 18 tổ hợp viewport/trang trên Chrome.
- [ ] Metrics bot, lưu bền cấu hình riêng và benchmark độ trễ lệnh. Xem docs/bot-improvements.md.

## Auto-play client — 18/09/2026

- [x] Controller A/B: đăng nhập, xác nhận phòng/chủ phòng/đối tác, ready/start, bắn Gunner theo lượt.
- [x] RMS riêng, F8/F9, timeout, không gửi trùng hành động; runner tự đóng client.
- [x] Hai client thật hoàn thành ba trận và đăng nhập lại.
- [x] Broadcast ở phòng chờ/trong trận; chỉnh lượng online; đối chiếu client/SQL/transaction/audit.
- [x] Unit test controller và tài liệu chạy: `docs/testing/client-autoplay.md`.
- [ ] Nghiệm thu hồi máu bằng client thật (hiện chỉ có unit test chờ item ACK).
- [ ] Mở rộng nhân vật/đạn, di chuyển, boss và địa hình có vòi rồng.

## Bot chiến thuật — đợt A (19/09/2026)

- [x] Controller theo trận/lượt, deadline, chờ animation, hành động không lặp.
- [x] Bước đi dùng chung engine/dự đoán; chọn vị trí an toàn, đi từng đoạn theo thể lực.
- [x] Tìm góc theo ngân sách trên game loop, giới hạn tổng và quay vòng; giữ va chạm đất.
- [x] Giữ item cũ và AI boss; flag `BOT_TACTICAL` cho phép bật thử/quay lại AI cũ.
- [x] 86 kiểm tra Java đạt, bao gồm 31 kiểm tra mới. Xem `docs/bot-phase-a.md`.
- [x] Nghiệm thu hai client thật quan sát bot di chuyển/bắn (bằng chứng đợt B/C).
- [x] Benchmark fixture; tiếp tục cân chỉnh/tải production trước khi bật `BOT_TACTICAL` mặc định.

## Bot chiến thuật — đợt B/C và xác chết (19/09/2026)

- [x] Chính sách HP/POW/x2/đi x2/ngưng gió/HP đội, kho thật và một item/lượt.
- [x] Bay tới điểm đáp an toàn, preview không đổi tọa độ thật; fallback phá đất.
- [x] Preset, allowlist, ngưỡng, ngân sách, metrics và cấp loadout qua admin/game loop/audit.
- [x] Sửa AI cũ giữ góc bắn vào mục tiêu chết; đạn không còn va chạm xác chết.
- [x] 141 kiểm tra Java đạt; HTTP thật kiểm tra cấu hình/CSRF/audit.
- [x] Hai client + hai bot hoàn thành ba trận; kiểm tra thêm một trận sau sửa xác chết.
- [x] Benchmark fixture 10/50/100 bot combat và 5.000 bot idle.
- [x] Ghi nhận client thật dùng ngưng gió/phá đất/HP đội, đối chiếu gói và kho; xem báo cáo soak (không đồng nghĩa toàn bộ trận đạt).
- [ ] Theo dõi tải production trước khi mở `BOT_TACTICAL` cho toàn bộ bot.

Hướng dẫn: `docs/bot-phase-b-c.md`; bằng chứng: `docs/testing/bot-bc-evidence/README.md`.

## Kiểm thử mở rộng B/C — 19/09/2026

- [x] 141 regression server, 31 kiểm tra client và 1.000 lượt mô phỏng thêm.
- [x] BC-SOAK-01: sửa nhánh bỏ qua ngưng gió sau khi đi tới đích, có test trước/sau.
- [x] Chạy 16 trận được bắt đầu bằng hai client + hai bot mỗi bộ: 14 kết thúc, 2 timeout.
- [x] Đối chiếu A/B và kho thật cho cả 8 item hỗ trợ; lưu ảnh/log cả thành công và thất bại.
- [x] BC-SOAK-02: đổi vị trí thất bại, giữ phương án đạn qua tick, phá vật cản và guard hòa khi chỉ còn bot không tiến triển.
- [x] Guard PvP chỉ còn bot: 120 giây không đổi HP / 30 giây không người theo dõi; giữ đường hòa của engine.
- [x] Admin thêm Bỏ lượt độc lập Timeout; log lý do guard và phân biệt hết đường bắn với hết mục tiêu.
- [x] Chạy lại cơ bản 10/10, Mê cung 3/3 (1 hòa do guard), hỗ trợ 3/3; cả hai client đăng nhập lại và đối chiếu gói/kho đạt.

Báo cáo: `docs/testing/bot-bc-soak.md`.


Bản sửa bế tắc: `docs/bot-stalemate-fix.md`; 162 kiểm tra Java chính (21 ca mới),
1.000 lượt mô phỏng. Kết quả cũ FAIL được giữ làm bằng chứng lịch sử; bản mới kết thúc
16/16 trận, trong đó có một hòa chống bế tắc.
