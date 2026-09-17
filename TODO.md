# Web Admin Roadmap

## MVP quản trị

- [x] Chạy web admin cùng JVM với game server.
- [x] Cấu hình admin qua biến môi trường.
- [x] Đăng nhập bằng session cookie và CSRF token.
- [x] Dashboard trạng thái server, session và người chơi online.
- [x] Tìm user theo ID, username hoặc tên nhân vật.
- [x] Cộng/trừ xu và lượng, đồng bộ user online và database.
- [x] Ghi lịch sử thay đổi số dư.
- [x] Kick user đang online.
- [x] Ban vĩnh viễn hoặc theo số phút.
- [x] Unban và chặn user bị ban tại bước đăng nhập game.
- [x] Audit log cho wallet, kick, ban và unban.
- [ ] Broadcast thông báo toàn server từ web admin.
- [ ] Trang chi tiết trang bị, item, nhiệm vụ và bạn bè.
- [ ] Đổi mật khẩu game user.

## An toàn và phân quyền

- [ ] Lưu tài khoản admin trong bảng `admin_account` bằng BCrypt.
- [ ] Role `OWNER`, `ADMIN`, `MODERATOR`, `VIEWER`.
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
- [ ] Test tích hợp admin với MySQL và game session giả lập.

## Giao diện nâng cao

- [ ] Phân trang và bộ lọc user.
- [ ] Log realtime bằng SSE/WebSocket.
- [ ] Quản lý phòng, trận và người chơi trong phòng.
- [ ] Lịch sử đăng nhập, IP và phiên bản client.
- [ ] Responsive UI và thông báo kết quả không cần tải lại trang.
