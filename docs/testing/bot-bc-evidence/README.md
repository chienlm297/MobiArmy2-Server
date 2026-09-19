# Bằng chứng nghiệm thu bot B/C — 19/09/2026

## Java và HTTP

- [141 kiểm tra Java đạt](java-tests.txt): vòng đời bot, admin, di chuyển, item/flight,
  cấu hình và 11 ca hồi quy xác chết. Chạy lại bằng `bash tests/bot-behavior.sh`.
- HTTP thật với server/MySQL Compose `army-bot-bc`, cổng local 18125/18085:
  tạo hai bot, cấu hình qua hàng đợi, cấp kho và slot; request cấu hình sai và CSRF sai
  trả lỗi 400/403. SQL có 12 BOT_REQUEST và 12 BOT_RESULT theo reason tiền tố BC.
- Sau trận trên bản sửa xác chết, hai bot có kho x2 9 (từ 10), kho đi x2 lần lượt
  10 và 9; không tự nạp lại trong trận. Metrics ghi 3/4 item đã dùng và 0 timeout.
- DB/tài khoản kiểm thử tách riêng. Không lưu cookie, CSRF, mật khẩu hoặc RMS trong bằng chứng.

## Hai client LibGDX thật + hai bot

[Ba trận trước bản sửa xác chết](three-matches/summary.md),
[đối chiếu gói tin](three-matches/bot-report.json):

- A/B đều 3/3 trận, đăng nhập lại thành công.
- Hai client nhận cùng chuỗi 71 cập nhật vị trí và 22 lần dùng item của bot.
- Item thực tế nhận được: HP (0), bay (1), bắn x2 (2), đi x2 (3), POW (100).
- Lượt này chưa bật log HP/đạn nên không kết luận gì về bắn xác chết từ log đó.

[Một trận sau bản sửa xác chết](after-corpse-fix/summary.md),
[đối chiếu gói tin](after-corpse-fix/bot-report.json):

- A/B đều hoàn thành, đăng nhập lại thành công.
- Chuỗi 19 vị trí, 7 item, 9 phát bắn, 8 cập nhật HP của bot khớp giữa hai phía.
- Không ghi nhận bot đã có HP=0 tiếp tục bắn. Gói đạn không chứa ID mục tiêu được ngắm:
  việc hủy ngắm xác chết được xác minh bằng test Java riêng, không suy đoán từ góc bắn.
- Ngưng gió, phá đất, hồi HP đồng đội đã qua fixture engine; chưa có bằng chứng GUI
  cho ba loại item này. Bản GUI có item bay ở lượt ba trận.

Một lượt khởi động profile mới đã hết timeout đăng nhập 15 giây trong lúc tải tài
nguyên. Runner được bổ sung timeout bước có cấu hình; lượt chạy lại dùng 90 giây và đạt.
Lượt thử ba người bị engine từ chối vì hai bên không bằng nhau; bài nghiệm thu dùng
hai client + hai bot, không sửa luật trận.

## Tải mô phỏng

[Log benchmark](benchmark.txt), `bash tests/bot-benchmark.sh`, JVM `-Xmx512m`.
Đây là fixture không network/DB, không phải server full-load hoặc benchmark cân bằng.
Các mẫu combat gồm **13 lượt xử lý AI**/mẫu, không phải một game tick:

| Workload | p95 | p99 |
|---|---:|---:|
| 10 bot combat, 13 lượt/mẫu | 2.030 ms | 9.117 ms |
| 50 bot combat, 13 lượt/mẫu | 3.088 ms | 4.464 ms |
| 100 bot combat, 13 lượt/mẫu | 5.008 ms | 5.352 ms |
| 5.000 bot idle, 1 game tick/mẫu | 0.733 ms | 0.913 ms |

Số thread được ghi là 6 ở các workload. Chưa có baseline server production hoặc
ngưỡng SLA; không dùng số liệu này để bật mặc định cho toàn bộ bot đang phục vụ người chơi.
