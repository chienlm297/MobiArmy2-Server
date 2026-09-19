# Chạy Auto-play bằng hai client thật

Mã Auto-play nằm trong repo **MobiArmy2-Client**, thư mục `core/src/autoplay/`.
Server không cần bật bot hoặc sửa luật chơi. `run-manual.sh` của server vẫn dùng như trước.

## Điều kiện

- Linux desktop có display/OpenGL, Java và các dependency Gradle của client đã tải.
- Hai tài khoản **kiểm thử riêng**, nhân vật Gunner (ID 0), đủ xu vào phòng.
- Chọn phòng PvP trống; tắt bot tự vào phòng này. A phải là chủ phòng, B là đối tác duy nhất.
- Server đã chạy, DB có dữ liệu game. Không dùng tài khoản đang chơi ở client khác.
- Bản hiện tại hỗ trợ bắn Gunner, chọn địch còn sống ít HP nhất, tìm góc/lực theo địa hình và gió.
  Chưa mô phỏng vòi rồng, đạn đặc biệt, di chuyển chiến thuật hoặc boss. Có thể bắn trượt và hết timeout.

## Chạy tự động

Tại repo client, nhập mật khẩu qua `read` để không lưu vào lịch sử shell:

```bash
cd ../MobiArmy2-Client
export AUTO_USER_A=test_a AUTO_USER_B=test_b
read -rsp 'Mật khẩu A: ' AUTO_PASSWORD_A; echo
read -rsp 'Mật khẩu B: ' AUTO_PASSWORD_B; echo
export AUTO_PASSWORD_A AUTO_PASSWORD_B
export AUTO_HOST=127.0.0.1 AUTO_PORT=8122
export AUTO_ROOM=0 AUTO_BOARD=0 AUTO_MATCHES=3
bash scripts/run-two-clients.sh
unset AUTO_PASSWORD_A AUTO_PASSWORD_B
```

Nếu tên nhân vật khác tên đăng nhập, thêm `AUTO_NAME_A`, `AUTO_NAME_B` đúng tên nhân vật.
Script build **cả core JAR và desktop**, mở hai cửa sổ, tự đăng nhập, vào phòng, ready/start,
bắn theo lượt, chờ kết quả ba trận rồi đăng nhập lại. Script thoát mã 0 chỉ khi cả hai đạt yêu cầu.
Sau mỗi trận chờ 12 giây để qua thời gian cấm ready của server.

`F8`: bật/dừng Auto-play trong cửa sổ; `F9`: dừng gửi hành động tự động.
Trong bài kiểm thử hai client, dừng một client làm bài kiểm thử không đạt và script đóng cả hai.
Muốn chạy lại bài kiểm thử, gọi lại script để có thư mục kết quả mới.
Auto-play mặc định tắt khi mở client bình thường; hai profile RMS được tách riêng.

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `AUTO_MATCHES` | 3 | Số trận cần kết quả xác nhận từ server |
| `AUTO_MATCH_TIMEOUT_SECONDS` | 600 | Hết thời gian một trận là thất bại |
| `AUTO_TOTAL_TIMEOUT_SECONDS` | 1800 | Giới hạn toàn bộ lượt chạy |
| `AUTO_HEAL` | false | Dùng item hồi máu khi HP dưới 70%, đợi xác nhận trước khi bắn |
| `AUTO_AIM` | SEARCH | `FIXED` để dùng góc/lực cố định |
| `AUTO_ANGLE`, `AUTO_FORCE` | 45, 20 | Góc/lực khi chọn FIXED |
| `AUTO_RUN_DIR` | thư mục mới trong test-results | Phải dùng thư mục chưa có kết quả lượt trước |
| `AUTO_LOBBY_DELAY_MS` | 0 | A chờ thêm trước start, hữu ích khi kiểm thử broadcast |
| `AUTO_SHOT_DELAY_MS` | 1000 | Chờ đầu trận trước phát bắn đầu tiên |

Runner nghiệm thu yêu cầu đăng nhập lại (`AUTO_RELOGIN=true`). Bật hồi máu chưa được kiểm thử
end-to-end; logic chờ xác nhận item có unit test.

## Đọc kết quả

Script in đường dẫn bằng chứng. Mỗi lượt có:

- `client-A/B.jsonl`: chuyển trạng thái, kết quả trận, broadcast, số dư và lệnh đã gửi.
- `client-A/B.console.log`: lỗi Java và log client.
- `client-A/B.summary`, `summary.md`: kết quả; timeout hoặc mất kết nối không tính là thắng.
- `client-A/B-match-*.png`, `client-A/B-DONE.png`: ảnh sau khi vẽ khung hình.
- `profile-A/B/`: dữ liệu RMS riêng. Không đưa toàn bộ profile lên Git.

Unit test: `bash scripts/test-autoplay.sh`. Nếu Gradle báo thiếu dependency khi offline,
chạy `bash gradlew :desktop:autoplayClasspath` một lần có mạng rồi chạy lại script.
Nếu sai phòng/chủ phòng, chọn phòng trống khác; nếu sai nhân vật, chuyển cả hai về Gunner.
Nếu không khởi tạo được OpenGL/display, xem console log; đây không phải kết quả kiểm thử game đạt.

## Kiểm thử admin và SQL

Script server `scripts/testing/autoplay-admin-check.py` chạy **đồng thời**, theo dõi JSONL của client:

1. Đợi cả hai vào phòng chờ, gửi broadcast có mã duy nhất và kiểm tra cả hai nhận được.
2. Đợi cả hai vào trận, gửi broadcast thứ hai và cộng **11 lượng vào mỗi tài khoản** qua admin.
3. Đợi đăng nhập lại; so sánh lượng ban đầu + 11 với client và SQL.
4. Kiểm tra đúng một giao dịch và một audit của mỗi tài khoản theo mã lượt chạy.

Script này thực sự thay đổi số dư tài khoản; chỉ trỏ vào DB/tài khoản kiểm thử riêng.
Không tự hoàn lại 11 lượng. SQL chỉ đọc; thay đổi số dư đi qua admin có CSRF.
Adapter SQL hiện dành cho MySQL trong Docker dùng root không mật khẩu, database `army`
như Compose kiểm thử của repo; cấu hình DB khác cần adapter tương ứng.

Ở terminal server, đặt biến rồi chạy (giá trị bên dưới là ví dụ):

```bash
export AUTO_RUN_DIR=/tmp/army-autoplay-check-01
export AUTO_ADMIN_URL=http://127.0.0.1:18084
export AUTO_ADMIN_USERNAME=admin
read -rsp 'Mật khẩu admin: ' AUTO_ADMIN_PASSWORD; echo
export AUTO_ADMIN_PASSWORD
export AUTO_DB_CONTAINER=army-autoplay-acceptance-db-1
export AUTO_ID_A=6 AUTO_ID_B=7
python3 scripts/testing/autoplay-admin-check.py
```

Ở terminal client, dùng cùng `AUTO_RUN_DIR`, đúng tài khoản ứng với hai ID trên,
đặt `AUTO_LOBBY_DELAY_MS=7000 AUTO_SHOT_DELAY_MS=5000`, rồi chạy runner.
Nên khởi động script admin trước runner. Chỉ kết luận bài kiểm thử đầy đủ đạt khi
**cả `summary.md` lẫn `admin-check.json` đều PASS / `passed: true`**.
Script admin không thay thế kiểm tra ba trận của runner.

## Bằng chứng ngày 18/09/2026

Đã kiểm tra bằng hai JVM LibGDX thật, server/DB Compose riêng, không dùng bot server.
Tài khoản fixture Gunner có điểm tấn công cao để rút ngắn trận; chưa đại diện cho cân bằng game.
Báo cáo đã lưu tại [autoplay-evidence](autoplay-evidence/).
