# Bằng chứng kiểm thử mở rộng B/C

**Kết luận: chưa đạt toàn bộ nghiệm thu.** Xem [báo cáo phân tích](../bot-bc-soak.md).
Tất cả dưới đây là lượt chạy mới ngày 19/09/2026; không gộp bằng chứng của đợt trước.

| Thư mục | Trận kết thúc / kế hoạch | Runner | So khớp gói A/B |
|---|---:|---|---|
| [basic](basic/summary.md) | 8/10 | FAIL: trận 9 timeout | Đạt |
| [special](special/summary.md) | 6/6 | PASS + đăng nhập lại | Đạt |
| [terrain-retry](terrain-retry/summary.md) | 0/3 | FAIL: trận đầu timeout | Đạt |
| [terrain](terrain/summary.md) | 0/3 | Fixture chưa sẵn sàng, đăng nhập lỗi | Không có trận, không tính vào thống kê gameplay |

Mỗi thư mục có JSONL A/B, summary, console, ảnh trận cuối hoặc FAILED/DONE;
`bot-report.json` ghi số liệu gói tin và kết quả kiểm tra nghiêm ngặt. Báo cáo FAIL
không bị ghi đè thành PASS chỉ vì hai client nhận cùng gói tin.

- [java-tests.txt](java-tests.txt): 141 regression server đạt.
- [simulation.txt](simulation.txt): 1.000 lượt mô phỏng và 4 tình huống riêng đạt.
- [wind-regression-before.txt](wind-regression-before.txt): lỗi tái hiện trước sửa.
- [client-tests.txt](client-tests.txt): 31 kiểm tra controller client đạt.
- [inventory-check.json](inventory-check.json): 12 đối chiếu kho theo bot/item đạt.
- [timing.json](timing.json): khoảng thời gian chạy và lỗi timeout, timestamp UTC.
- [benchmark-final.txt](benchmark-final.txt): fixture tải chạy sau khi dọn stack GUI.
- `admin-snapshot.txt`: thu sau khi runner kết thúc, lọc bỏ form/CSRF/cookie. Với hai
  trận bế tắc, server tiếp tục chạy sau khi client thoát và qua khoảng gián đoạn phiên
  làm việc; counters quyết định/tìm góc vì vậy lớn hơn khoảng quan sát của client.
- `server-errors.txt`: trích dòng exception/error và số restart. Lỗi duy nhất ở bộ
  terrain là validation lúc fixture thử mang 2 item 6 trong khi giới hạn là 1;
  đã sửa fixture, không nới luật game.

## Phiên bản chạy

- basic: JAR SHA-256 `28ec8c1d8cea326928b79a90b377558bd5396614f9a1fc4cda41b553ddd75da8`,
  trước sửa ngưng gió sau di chuyển; loadout không có item 5.
- special / terrain-retry: JAR SHA-256
  `ca61bbd2e3a21bdf1dedef2d8d262466b5310ad81c1ec5c9415fe97c4bb7cd4f`,
  đã có sửa ngưng gió. Cả ba bản đều có sửa chọn mục tiêu/va chạm xác chết.
- Chế độ `BOT_TACTICAL=true`, `BOT_COUNT=0`, tạo đúng 2 bot qua admin mỗi stack.
  Bot Gunner cấp 1, target RANDOM, think 300 ms, steps 48, ngân sách lượt 10.000 ms.
- Container game ports 18125/18126/18127 và admin 18085/18086/18087, chỉ bind loopback.
  Ba stack/volume kiểm thử đã được dọn. Không khởi động lại server của người dùng.

## Phạm vi bằng chứng

Gói SHOT không chứa ID mục tiêu, nên không chứng minh bot ngắm ai; regression Java
kiểm tra việc hủy ngắm khi mục tiêu chết và bỏ va chạm xác chết. Gói item/đạn xác nhận
item 5/6/10 đã được dùng trong GUI, không phải so sánh tự động mọi pixel địa hình/hiệu ứng.
Không lưu profile RMS, cookie, mật khẩu hay toàn bộ DB vào repo.
