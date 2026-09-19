# Bot chiến thuật — đợt B/C và sửa bắn xác chết

> Đã sửa bế tắc: chạy lại 16/16 trận kết thúc, gồm một hòa do guard.
> Xem [bản sửa và nghiệm thu](bot-stalemate-fix.md). Báo cáo soak trước sửa vẫn được giữ.

## Sử dụng

```bash
BOT_TACTICAL=true bash run-manual.sh
# hoặc
BOT_TACTICAL=true docker compose up -d --build server
```

Vào **Quản lý bot → Chiến thuật và item** ở từng bot. Có thể bật AI chiến thuật cho
riêng bot từ trang này ngay cả khi `BOT_TACTICAL` mặc định false. Cấu hình lưu RAM,
áp dụng từ lượt kế tiếp; khởi động lại sẽ theo biến môi trường. Không cần ALTER DB.
Các thay đổi trong UI đi qua hàng đợi game loop, kiểm tra quyền OWNER/ADMIN, CSRF và audit.
VIEWER chỉ xem thông tin; lệnh chờ quá hạn hoặc quyền bị thu hồi không được thực thi.

## Cấu hình

- Bật/tắt AI chiến thuật, di chuyển và dùng item độc lập.
- Preset: `PASSIVE` đứng bắn, không di chuyển/item; `BALANCED` theo ngưỡng HP đã chọn;
  `AGGRESSIVE` chỉ ưu tiên hồi máu khi HP ≤ min(30%, ngưỡng); `SUPPORT` nâng ngưỡng
  hồi máu lên ít nhất 60% và ưu tiên chữa đồng đội khi có lợi.
- Ngưỡng HP 1–90%, bước tối đa 0–60, chờ suy nghĩ 0–2.000 ms,
  ngân sách lượt 3.000–10.000 ms; tính góc vẫn chia ngân sách chung giữa các bot.
- Danh sách item cho phép độc lập với bộ item mang vào trận. Có trong allowlist
  không có nghĩa được cấp miễn phí.
- RANDOM/LOW_HP tiếp tục là cách chọn mục tiêu riêng, không bị preset ghi đè.
- Trang admin hiển thị quyết định gần nhất, số đoạn đi, số item dùng, lượt tìm góc,
  thời gian tìm góc tích lũy, timeout và kho/slot đang mang.

## Cấp item

Dùng form **4 slot / cấp kho** khi bot ngoài trận và chưa sẵn sàng; có thể cho bot
rời phòng chờ trước. Nhập bốn ID, `-1` là trống. Ví dụ `0,1,2,3`.
Chỉ dùng bốn slot cơ bản, tránh giả định bot đã có túi mở rộng.

“Số lượng cấp thêm” cộng một lần vào mỗi loại item đặc biệt được chọn, kiểm tra
`carryable`, tồn kho và giới hạn 99 trước khi thay đổi. Số 0 chỉ đổi loadout theo kho
sẵn có. Mọi cấp phát là thao tác admin có audit, không có tự nạp giữa trận.
Item HP/bay cơ bản 0/1 theo luật hiện tại: tiêu slot mang theo trong trận, không trừ
kho cơ bản; item 2/3/5/6/10 trừ số lượng trong kho thật. POW tiêu nộ, không chiếm slot kho.

## Chính sách item

| ID | Hành vi |
|---|---|
| 0 | Hồi HP khi dưới ngưỡng, ưu tiên hơn POW |
| 1 | Khi bí đường bắn, tìm điểm đáp có nền, không sát vực/đè người khác; mô phỏng đạn bay rồi mới dùng |
| 2 | Bắn x2 sau khi có đường bắn hợp lệ |
| 3 | Đi x2 chỉ khi điểm đứng đạt được có lợi hơn đường đi thường |
| 5 | Ngưng gió trước ngắm khi tổng độ lớn gió X/Y ≥ 35 |
| 6 | Khi bí đường, tìm chướng ngại phía trước để phá; tránh điểm nổ sát bản thân/đồng đội |
| 10 | Chữa đồng đội còn sống, đúng điều kiện phe/team của engine; xét lượng HP thiếu |
| 100 | POW sau khi có đường bắn; tính lại nếu POW thay đặc tính đạn |

Mỗi lượt tối đa một item theo engine. Không đủ kho/slot/nộ thì không phát hiệu ứng,
không tiêu quyền dùng item. Kế hoạch dùng loại đạn sau item, không ép về đạn thường.
Bay là một phát bắn kết thúc lượt, không dịch chuyển tức thời để bắn thêm.

Tự sát, UFO, tàng hình, item nhiều hiệu ứng khác chưa được đưa vào allowlist của AI.
Đây là phạm vi đã loại khỏi bản đầu trong kế hoạch; không tự mở tất cả item.

## Sửa lỗi xác chết

Có hai lỗi được sửa cho cả trường hợp không bật `BOT_TACTICAL`:

1. AI cũ có thể giữ góc bắn sau khi mục tiêu chết/rời trận/di chuyển. Nay lưu mục tiêu
   cùng tọa độ người bắn, kiểm tra lại và loại kết quả cũ trước khi bắn; đổi lượt hủy góc cũ.
2. `MapData.isCollisionPlayer()` vẫn tính xác chết là vật cản. Nay chỉ nhân vật còn sống,
   HP > 0 và có va chạm mới cản đạn. Nhờ đó đạn nhắm người sống không nổ trên xác nằm trước.

Cờ hoàn tất đường đạn bất đồng bộ cũ có công bố bộ nhớ (`volatile`). AI chiến thuật
kiểm tra mục tiêu mỗi tick và bỏ tìm góc nếu mục tiêu chết trong lúc đang ngắm.
Đạn đã bắn ra trước khi mục tiêu chết không được sửa hướng giữa đường.

## Kiểm thử

```bash
bash tests/bot-behavior.sh
bash tests/bot-benchmark.sh
```

141 kiểm tra Java đạt: 40 bot, 15 admin cũ, 31 đợt A, 34 item/bay, 10 cấu hình admin,
11 xác chết. Bao gồm kho rỗng/null, POW giả không đủ nộ, một item/lượt, preview bay
không đổi vị trí/địa hình/may mắn, điểm đáp thật khớp mô phỏng, cấu hình chờ lượt sau,
và góc bắn đã hoàn tất nhưng mục tiêu chết trước khi dùng.

Bằng chứng: [bot-bc-evidence](testing/bot-bc-evidence/README.md).
Benchmark là fixture Java (không DB/network), không phải cam kết tải production.
Giữ mặc định flag false để bật từng nhóm bot và theo dõi trước khi mở toàn bộ.

## Lặp nghiệm thu hai client

Trong repo client, runner có chế độ fixture rõ ràng:

```bash
export AUTO_BOT_IDS='-2147483648,-2147483647' # dùng ID thật từ admin của server kiểm thử
export AUTO_STEP_TIMEOUT_SECONDS=90         # cho profile mới tải tài nguyên
# Thiết lập hai tài khoản, mật khẩu, host/port theo docs/testing/client-autoplay.md
bash scripts/run-two-clients.sh
```

A mời đúng các bot được chỉ định. Runner chờ đủ người và bot sẵn sàng; không chấp nhận
người thứ ba ngoài allowlist. Phòng chia đội cần số lượng hai bên bằng nhau nên sử dụng
hai client + hai bot. Mặc định không có `AUTO_BOT_IDS` vẫn là bài kiểm thử hai người.
Log có ACTOR/POSITION/ITEM_RECEIVED/HP_RECEIVED/SHOT_RECEIVED. Đối chiếu:

```bash
python3 scripts/testing/bot-client-report.py /duong/dan/ket-qua
```

Báo cáo gói tin không suy ra mục tiêu bot đang nhắm. Kiểm tra bắn xác chết dựa vào
regression Java chọn mục tiêu/va chạm, bổ sung đối chiếu HP và đạn client khi có telemetry.
