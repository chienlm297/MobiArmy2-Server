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
