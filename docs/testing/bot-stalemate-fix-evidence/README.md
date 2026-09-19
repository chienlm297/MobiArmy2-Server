# Nghiệm thu bản sửa bế tắc B/C

[Báo cáo thay đổi, chính sách hòa và phạm vi](../../bot-stalemate-fix.md).

| Bộ client thật | Kết quả | Hòa do guard |
|---|---|---:|
| [Cơ bản](basic/summary.md) | 10/10 + relogin A/B PASS | 0 |
| [Mê cung](maze/summary.md) | 3/3 + relogin A/B PASS | 1 |
| [Hỗ trợ](support/summary.md) | 3/3 + relogin A/B PASS | 0 |

`bot-report.json` mỗi thư mục kiểm tra đúng số trận, map, telemetry HP/đạn, không lỗi
Java, không bot đã nhận HP=0 tiếp tục bắn và so khớp toàn bộ chuỗi gói A/B.
Ảnh `maze/client-A-match-3.png` xác nhận **HÒA**, tương ứng log `NO_PROGRESS` trong
`maze/server-events.txt`. Không ghi đè hay xóa các kết quả FAIL của lần chạy trước.

- [java-tests.txt](java-tests.txt): 162 kiểm tra chính, gồm 21 ca chống bế tắc.
- [stall-tests.txt](stall-tests.txt): log 21 ca chống bế tắc chạy riêng, **không cộng trùng**.
- [soak-tests.txt](soak-tests.txt): 1.000 lượt mô phỏng + bốn tình huống item.
- [inventory-check.json](inventory-check.json): 12 đối chiếu kho theo bot/item đạt.
- [result-totals.json](result-totals.json): tổng packet, guard draws và SHA-256 JAR chạy thật.
- [benchmark.txt](benchmark.txt): fixture sau khi dọn Docker, không DB/network.
  Combat là 13 lần xử lý AI/mẫu, idle là một tick/mẫu; không phải SLA production.
- [disconnect/disconnect-check.json](disconnect/disconnect-check.json): chủ động ngắt
  đúng hai JVM fixture, quan sát phòng quay về WAITING. Không có log UNATTENDED nên
  **chưa xác minh trigger 30 giây trên client thật**, chỉ có unit/integration Java.
  `disconnect/summary.md` FAIL là kết quả dự kiến do chủ động đóng client, không phải
  một trận nằm trong 16 trận nghiệm thu hoàn tất.

Cả ba bộ dùng server/DB riêng, hai bot Gunner, target RANDOM, think 300 ms, steps 48,
ngân sách lượt 10.000 ms. Loadout/stock ban đầu nằm trong `*-initial.txt`; snapshot
cuối ở mỗi thư mục. Bộ support chụp snapshot trước ca ngắt kết nối bổ sung.
Không lưu cookie, mật khẩu, RMS hoặc dump DB. Đã dọn toàn bộ ba stack/volume thử nghiệm.

JAR thực nghiệm: `eff4c9f785e41a32461cc2f87470faafb158b21ac72a56cc43e103340b91c462`.
Các trận không cố định seed; số liệu không phải thí nghiệm đối chứng thống kê.
