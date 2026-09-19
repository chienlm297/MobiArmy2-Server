# Kiểm thử mở rộng bot B/C — 19/09/2026

> Đây là kết quả **trước sửa**. Bản sửa sau đó đã hoàn tất 16/16 trận, gồm một hòa
> chống bế tắc; xem [báo cáo mới](../bot-stalemate-fix.md).

## Kết luận trước sửa: chưa đạt toàn bộ nghiệm thu B/C

Đã chạy **16 trận thực tế được bắt đầu**: 14 kết thúc, 2 timeout sau 600 giây.
Kế hoạch ba bộ là 19 trận; 3 trận còn lại không được chạy vì runner dừng khi timeout.
Không cộng các trận của lần nghiệm thu trước vào số liệu này.

| Bộ thử | Kết thúc / kế hoạch | Kết quả toàn bộ runner | Ghi nhận |
|---|---:|---|---|
| Cơ bản, map mặc định | 8/10 | FAIL | Trận 9 bế tắc; trận 10 chưa chạy, chưa đăng nhập lại |
| Hỗ trợ, map 2 | 6/6 | PASS | Cả A/B đăng nhập lại; đúng map ở cả 6 trận |
| Phá đất, map 20 | 0/3 | FAIL | Trận 1 bế tắc; trận 2/3 chưa chạy, chưa đăng nhập lại |

Hai bộ thất bại là lỗi **không tiến triển trận đấu**, không phải crash hoặc sai gói tin.
Bộ cơ bản kéo dài 23 phút 33 giây, hỗ trợ 8 phút 22 giây, phá đất 10 phút 7 giây
(tính từ sự kiện đầu/cuối của client A, gồm cả đăng nhập/phòng chờ).

### Lỗi bế tắc cần xử lý trước khi bật rộng

- **BC-SOAK-02 — cao:** hai bot còn sống nhưng liên tục hết phương án bắn. Ở trận 9
  bộ cơ bản, không có phát bắn nào trong **555,876 giây cuối**; Mê cung là **423,224 giây**.
  Log HP xác nhận cả hai người chơi đã chết trước thời gian này. Ảnh FAILED cho thấy
  vị trí bot trên các tầng địa hình khác nhau; snapshot có `Giữ vị trí` /
  `Không còn mục tiêu nhìn thấy`. Đây là quan sát; chưa kết luận toàn bộ nguyên nhân
  hình học từ ảnh. Trong code, thông báo hết mục tiêu cũng được dùng khi mọi mục tiêu
  đã thử nhưng không tìm được đường bắn, nên nó không chứng minh địch đã chết.
- Bộ Mê cung cố ý chỉ cho dùng item 6 để kiểm tra phá đất, không có phương án bay.
  Vì vậy không khái quát kết quả này cho mọi loadout trên map 20. Tuy nhiên bộ cơ bản
  cũng bế tắc với loadout bình thường có bay, nên rủi ro không chỉ nằm ở fixture item 6.
- Bộ đếm `Timeout=0` trong snapshot là ngân sách **một lượt**, không phải giới hạn toàn
  trận. Sau khi hai client đóng, hai server này vẫn giữ trận bot và tiếp tục tính lượt;
  snapshot được thu muộn sau khoảng gián đoạn phiên làm việc, nên counters quyết định/
  tìm góc không chỉ tính khoảng client còn kết nối. Đã dừng các stack này khi dọn test.
- Chưa thêm luật tự xử hòa, xử thua hoặc cấp item miễn phí để làm test xanh.

Việc tiếp theo: phát hiện nhiều lượt không tiến triển, đổi vị trí/đường ngắm hoặc
chọn escape phù hợp; có chính sách kết thúc/dọn trận bế tắc; metrics riêng cho lượt
không bắn và trận không tiến triển. Sau sửa, chạy lại cả hai bộ thất bại cùng bộ hỗ trợ.
**Giữ `BOT_TACTICAL` mặc định false, chỉ bật thử nhóm nhỏ.**

Các lượt bot quan sát được đã chuyển sang lượt kế tiếp/kết thúc trận:

| Bộ thử | Lượt bot có đủ mốc quan sát | Lượt không nhận gói bắn |
|---|---:|---:|
| Cơ bản | 313 | 226 |
| Hỗ trợ | 64 | 0 |
| Phá đất | 186 | 178 |

Không tính lượt cuối đang mở lúc timeout; một lượt không bắn riêng lẻ chưa phải lỗi,
nhưng chuỗi kéo dài không có phát bắn hay cập nhật HP là dấu hiệu bế tắc ở hai ca này.

### Những phần đã xác nhận đạt

Cả A/B nhận **cùng thứ tự và nội dung** 326 POSITION, 97 ITEM, 159 SHOT, 122 HP của bot
trên các bộ chạy, kể cả hai trận timeout. Không có phát bắn từ bot sau khi client đã
nhận HP=0, không có Java exception ở sáu client, không có container restart.
Đây không phải bằng chứng về ID mục tiêu ngắm; kiểm tra xác chết vẫn dựa vào regression Java.

| Item | Số lần nhận trên một client, cộng ba bộ | Xác minh thêm |
|---|---:|---|
| HP 0 | 15 | Kho cơ bản theo luật engine |
| Bay 1 | 3 | Xuất hiện trong bộ cơ bản |
| Bắn x2 2 | 17 | Kho hai bot giảm 9 và 8 |
| Đi x2 3 | 11 | Kho hai bot giảm 6 và 5 |
| Ngưng gió 5 | 12 | Map 2, kho mỗi bot 30 → 24 |
| Phá đất 6 | 2 | Map 20, có SHOT bullet 6 từ cả hai bot, kho mỗi bot 30 → 29 |
| HP đội 10 | 12 | Map 2, nhận HP tăng sau item; kho mỗi bot 30 → 24 |
| POW 100 | 25 | Có gói item và phát bắn tiếp theo |

Ví dụ HP đội trận đầu: seat 3 nhận HP 738, dùng item 10 rồi nhận HP 1010; đồng đội seat 1
cũng nhận cập nhật HP. Hiệu ứng gió/địa hình chưa được so sánh tự động từng pixel/gói WIND:
client chứng minh sử dụng/phát đạn đúng loại, còn thay đổi gió và lựa chọn phá vật cản
được kiểm tra bổ sung ở engine/controller. Không đánh đồng việc dùng phá đất với đảm bảo thoát kẹt.

12 đối chiếu kho theo bot/item đều đạt; không âm kho hoặc tự cấp lại item. Server bộ
cơ bản/hỗ trợ không có exception. Server phá đất có đúng một lỗi validation được chủ
động gây ra lúc cấu hình mang hai item 6: dữ liệu game chỉ cho mang một. Lượt client
khởi động nhầm trước khi fixture đó xong đã thất bại đăng nhập, 0 trận; giữ riêng ở
`terrain/`, không gộp thành trận game thất bại hay giấu khỏi bằng chứng.

**Bằng chứng:** [thư mục log, ảnh và báo cáo máy đọc](bot-bc-soak-evidence/README.md).

## Phạm vi và cách chạy

Kiểm thử trên Compose/MySQL tách riêng; không dùng DB hay tài khoản đang chơi.
Mỗi bộ trận sử dụng **hai client LibGDX thật và hai bot server**, Gunner cấp 1,
nhân vật/tài khoản fixture. Kết quả trận phải do server xác nhận; runner chỉ đạt sau
khi cả A/B đăng nhập lại thành công. Không coi timeout là trận hoàn thành.

- Bộ cơ bản: dự kiến 10 trận, loadout `0,1,2,3`, BALANCED, kho 2/3 ban đầu 10 mỗi bot.
  Bản server có sửa xác chết, trước sửa lỗi ngưng gió phát hiện trong đợt này.
- Bộ hỗ trợ: dự kiến 6 trận trên **Căn cứ thép (2)**, loadout `5,6,10,1`, SUPPORT,
  kho 5/6/10 ban đầu 30 mỗi bot. Dùng bản đã sửa ngưng gió.
- Bộ phá đất: dự kiến 3 trận trên **Mê cung (20)**, loadout `6,-1,-1,-1`, chỉ cho
  phép item 6, kho ban đầu 30 mỗi bot. Dùng bản đã sửa ngưng gió.

Hai bộ sau ghi `MATCH_MAP` và báo cáo kiểm tra đúng ID cho từng trận. Bộ cơ bản bắt đầu
trước khi thêm telemetry map nên chỉ ghi nhận map mặc định, không suy ra ID từ góc bắn.
Các map có địa hình thật; không sửa HP, sát thương hay vị trí giữa trận để ép kết quả.

## Lỗi phát hiện và sửa

`BotTurnController`: khi di chuyển vừa đến điểm đích, state chuyển sang AIM và bỏ qua
kiểm tra ngưng gió vốn nằm trong nhánh MOVE. Đưa kiểm tra gió vào điểm chung trước
khi bắt đầu tìm góc, có điều kiện chưa dùng item và chưa tìm đường đạn đặc biệt.

Test riêng thực sự đi rồi gặp gió 50 tái hiện lỗi trước sửa; sau sửa gió về 0 và kho
item 5 giảm đúng một. Log trước/sau nằm trong thư mục bằng chứng. Không thay đổi luật
một item/lượt hay cấp item miễn phí.

## Mô phỏng và hồi quy

```bash
bash tests/bot-behavior.sh
bash tests/bot-soak.sh
bash tests/bot-benchmark.sh
```

- 141 kiểm tra hồi quy server: bot/admin/di chuyển/item/preview bay/hủy ngắm xác chết.
- `BotSoakTest`: **1.000 lượt quyết định mô phỏng**, seed `20260919`, không phải 1.000
  trận network. Thay đổi HP, stamina, gió, vị trí địch, bốn preset, kho 0/1/2, mặt đất
  phẳng/vực/tường, đóng băng và mục tiêu chết giữa lượt. Kiểm tra giới hạn bước, tọa độ,
  kết thúc lượt, tối đa một phát bắn và một item, kho không âm/không tự nạp.
- Ca riêng chạy controller: ngưng gió sau di chuyển; bị tường chặn thì chọn phá đất;
  mục tiêu chết giữa lúc chuẩn bị phá đất thì không bắn/không trừ kho; hỗ trợ chữa đồng đội.
- 31 kiểm tra controller client, bao gồm cấu hình map PvP tùy chọn.

Fixture mô phỏng dùng `CombatBot` ghi nhận lệnh bắn, không tính toàn bộ sát thương
trận thật. Bullet/engine có các kiểm tra riêng và được bổ sung bằng các trận client.
Thời gian giả lập tăng theo bước để kiểm tra timeout; kết quả không thay thế đo tải.

## Tái chạy fixture client

Thiết lập stack riêng theo `docker-compose.yml`, override cổng game/admin local,
`BOT_COUNT=0`, `BOT_TACTICAL=true`, username/password admin thử nghiệm. Không trỏ script
fixture vào server đang phục vụ người chơi.

Đặt `AUTO_ADMIN_USERNAME`, `AUTO_ADMIN_PASSWORD`, `AUTO_USER_A/B`, `AUTO_PASSWORD_A/B`
qua môi trường. Tại repo server:

```bash
python3 scripts/testing/bot-bc-fixture.py --url http://127.0.0.1:18086 \
  --create-users --slots=5,6,10,1 --preset=SUPPORT --quantity=30
```

Script chờ kết quả DONE từng lệnh game-loop, không coi HTTP nhận lệnh là đã thực thi.
Chỉ `--create-users` khi tạo tài khoản mới; số lượng cấp thêm là cộng vào kho hiện có.
Dùng `--snapshot-only` để xuất metrics/kho, không xuất CSRF/cookie.

Tại repo client, giữ cùng thông tin tài khoản, dùng ID hai bot script in ra:

```bash
AUTO_HOST=127.0.0.1 AUTO_PORT=18126 AUTO_ROOM=0 AUTO_BOARD=0 \
AUTO_BOT_IDS='-2147483648,-2147483647' AUTO_MAP_ID=2 AUTO_MATCHES=6 \
AUTO_STEP_TIMEOUT_SECONDS=90 AUTO_TOTAL_TIMEOUT_SECONDS=5400 \
AUTO_RUN_DIR=/tmp/bc-new-run bash scripts/run-two-clients.sh
```

Tại repo server:

```bash
python3 scripts/testing/bot-client-report.py /tmp/bc-new-run --matches 6 --map 2 --strict
```

`--strict` yêu cầu có telemetry HP/đạn, không có lỗi Java trong console, không có bot
đã nhận HP=0 tiếp tục bắn; so sánh nguyên thứ tự gói POSITION/ITEM/SHOT/HP giữa A/B.
Script cũng kiểm tra đúng số trận yêu cầu và ID map nếu chỉ định. Hết trận phải có
`status=DONE`; gói tin không chứa ID mục tiêu nên không dùng nó để chứng minh mục tiêu
ngắm còn sống. Điều đó do regression Java kiểm tra riêng.

## Benchmark sau khi dọn các stack client

JVM `-Xmx512m`, không network/DB. Combat: 100 mẫu, mỗi mẫu **13 lần xử lý AI**;
idle: 300 mẫu, mỗi mẫu một game tick. Không so trực tiếp hai loại mẫu với nhau.

| Workload | p95 | p99 | Heap tại lúc đo |
|---|---:|---:|---:|
| 10 bot combat | 1,811 ms | 8,877 ms | 11,8 MB |
| 50 bot combat | 2,914 ms | 2,942 ms | 32,1 MB |
| 100 bot combat | 3,997 ms | 4,308 ms | 71,3 MB |
| 5.000 bot idle | 0,617 ms | 0,831 ms | 92,3 MB |

Số thread ở cả bốn mẫu workload là 6. Không có SLA production hoặc benchmark đối chứng;
không dùng số liệu fixture này để kết luận server đầy đủ chịu được 5.000 người chơi.

## Giới hạn kết luận

- Kết quả chỉ đại diện nhân vật Gunner và những map/loadout được chạy; không phải
  chứng nhận mọi nhân vật, map boss, item ngoài allowlist hay mọi cấu hình production.
- Đóng băng, kho rỗng và mục tiêu chết đúng lúc tìm góc có mô phỏng/hồi quy; không
  khẳng định mọi tình huống đó đều xảy ra trong GUI.
- Lượt không bắn có thể là không còn đường bắn hoặc hết mục tiêu, không tự kết luận lỗi.
- Bot hiện chưa có kiểm tra tránh sát thương đồng đội hoàn chỉnh; đó là phạm vi đợt D.
- Benchmark không DB/network; mẫu combat gồm 13 lần xử lý AI, không phải một tick server.
