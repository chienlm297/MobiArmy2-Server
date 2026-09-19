# Kế hoạch nâng cấp bot: di chuyển và dùng item

Trạng thái: đề xuất, chưa triển khai. Phạm vi là bot phía server (`Bot extends User`),
không phải Auto-play của client và không thay AI riêng của boss Robot.

## 1. Hiện trạng đã kiểm tra

- `Bot.update()` đã hồi HP bằng item 0 dưới 70% HP; tuy nhiên ưu tiên POW (100)
  khi đủ nộ và HP thấp. Trong `MapData.useItem()`, POW tăng sức tấn công, không hồi HP.
- Có bước dịch chuyển ngẫu nhiên -1/0/+1 pixel và thử di chuyển khi không tìm được
  đường bắn. Chưa đánh giá chỗ đứng, vực, khoảng cách đồng đội hoặc lợi ích của di chuyển.
- Bot bỏ qua giá trị trả về của `PathSimulator.simulate()` và dùng phần tử cuối của
  `pathFrames`. Simulator có thể trả true sau 60 vòng dù chưa tới đích; mô phỏng va chạm
  khác `Player.move()`, chưa phản ánh đầy đủ thể lực, đóng băng và đi x2.
- Khi bí đường, bot chuyển `isLand=false` trong tìm góc. Đường mô phỏng bỏ đất không
  bảo đảm viên đạn thật đi được.
- Mỗi `BulletTrajectory.start()` tạo thread riêng. Thêm nhiều điểm đứng sẽ nhân chi phí
  nếu không giới hạn việc tìm đường đạn và loại kết quả cũ.
- Bot bắn theo `bulletIdByGlassID()`. Cần thống nhất với hiệu ứng item đổi `bulletId`,
  không được chọn item bay rồi tiếp tục tính/bắn đạn thường.
- `MapData.useItem()` giới hạn một item mỗi lượt qua `isUseItem`. Item đặc biệt có
  kiểm tra/trừ số lượng; cần bảo đảm kiểm tra hợp lệ trước khi đổi cờ hoặc phát hiệu ứng.

## 2. Kết quả mong muốn

Bot biết ở lại khi vị trí tốt, di chuyển tới vị trí tốt hơn khi có lợi, hồi máu khi cần,
dùng item tấn công/hỗ trợ phù hợp và bắn bằng trạng thái sau hành động.
Không bắt buộc bot đi lại mỗi lượt chỉ để tạo cảm giác hoạt động.

Mọi hành động tuân thủ thể lực, địa hình, trạng thái đóng băng, số item và luật lượt.
Mọi thay đổi trận thực hiện trên game loop qua luồng hành động chung của server.
Bot không được tự sửa tọa độ/HP/kho item để bỏ qua luật.

## 3. Các bước triển khai

### P1 — Bộ điều khiển lượt và giới hạn tính toán

- [ ] Tách quyết định khỏi `Bot.update()` thành `BotTurnController`, `BotDecision`,
  `BotMovementPlanner`, `BotItemPolicy`; giữ vòng đời vào/rời phòng hiện có.
- [ ] Luồng: quan sát → lập kế hoạch → dùng item trước di chuyển nếu cần → di chuyển
  → kiểm tra lại → dùng item trước bắn nếu còn quyền → tính góc → bắn/kết thúc lượt.
  Nhánh di chuyển và item có thể được bỏ qua; tối đa một item theo luật hiện tại.
- [ ] Mỗi kế hoạch có khóa trận/lượt/bot. Hủy khi chết, đổi lượt, rời trận, bị khóa/xóa
  hoặc mục tiêu/địa hình thay đổi làm kế hoạch không còn hợp lệ.
- [ ] Chờ thời gian hành động/animation hiện có giữa di chuyển, item và bắn; mỗi bước
  chỉ phát một lần, kiểm tra trạng thái thật sau khi thực thi.
- [ ] Giới hạn số ứng viên, phép mô phỏng và deadline mỗi lượt. Dùng bộ xử lý có số
  worker/hàng đợi hữu hạn nếu tính bất đồng bộ; kết quả immutable, công bố an toàn.
- [ ] Mô phỏng chỉ đọc snapshot vật lý; kiểm tra đường đạn thử không sửa địa hình,
  HP hoặc hiệu ứng thật. Không copy toàn bộ map cho từng góc bắn.
- [ ] Khi hết ngân sách: dùng phương án hợp lệ đã có hoặc kết thúc lượt theo engine;
  không lặp tạo thread và không dùng kết quả của lượt cũ.

Nghiệm thu: không bắn/dùng item hai lần, không áp dụng kết quả muộn, không treo lượt.
Đường tính dùng chung với boss phải có regression test trước khi thay API.

### P2 — Di chuyển có mục tiêu

- [ ] Tách phép tính bước đi thuần để mô phỏng và di chuyển thật dùng cùng quy tắc;
  xác định rõ trường hợp hết thể lực, đi x2, vướng tường, rơi và đóng băng.
- [ ] Kết quả đường đi phân biệt tới đích, tới một phần, bị chặn, nguy hiểm và hết ngân sách.
  Chỉ dùng đường đi một phần nếu điểm cuối thực tế được kiểm tra an toàn và có lợi.
- [ ] Sinh một tập nhỏ vị trí ứng viên trong thể lực còn lại: đứng yên, trái/phải gần,
  trung bình và gần giới hạn. Không thử mọi pixel cùng mọi góc/lực.
- [ ] Lọc vực/ngoài map/vị trí không đứng được. Phạt gần mép vực, quá sát đồng đội,
  đường bắn có nguy cơ trúng đồng đội; chỉ dùng thông tin đối thủ mà luật cho phép thấy.
- [ ] Chấm điểm lợi ích đường bắn, an toàn, khoảng cách và chi phí bước đi.
  Chỉ di chuyển khi tốt hơn đứng yên đủ một ngưỡng; chống đi qua lại vô ích.
- [ ] Di chuyển theo đoạn ngắn qua cơ chế hiện có, kiểm tra tọa độ thực tế và địa hình
  sau mỗi đoạn. Tính lại góc từ điểm đứng thực, không từ điểm dự kiến.
- [ ] Bỏ fallback tìm đạn thường xuyên đất (`isLand=false`) trong AI bot mới.

Nghiệm thu: bot thoát vị trí bị chắn khi có đường, không vượt thể lực/đi xuyên tường,
không chủ động đi xuống vực trong fixture, đứng yên khi đó là lựa chọn tốt hơn.

### P3 — Item giai đoạn đầu

- [ ] Thiết lập bộ item mang vào trận, số lượng và danh sách cho phép. Không bổ sung
  item miễn phí giữa trận; quản trị phải nhìn được nguồn và giới hạn item của bot.
- [ ] Xây bảng quyết định có ưu tiên; kiểm tra lại điều kiện ngay trước khi dùng.
- [ ] Thực thi qua `User.useItem()` / `MapData.useItem()`, xác nhận hiệu ứng thật;
  item không hợp lệ không được trừ kho, chiếm lượt item hoặc phát hiệu ứng giả.
- [ ] Lập lại kế hoạch nếu item thay gió, sức di chuyển, số phát hoặc loại đạn.

| Item | Chính sách đề xuất |
|---|---|
| Hồi HP 0 | Ưu tiên khi nguy cấp; xét lượng HP thiếu và nguy cơ chết, tránh hồi lãng phí |
| POW 100 | Dùng khi đủ nộ và có cơ hội tấn công tốt; bỏ điều kiện HP thấp làm tiêu chí chính |
| Bắn x2 2 | Dùng khi có đường bắn hợp lệ và lợi ích sát thương đủ lớn |
| Đi x2 3 | Chỉ dùng nếu phần tầm di chuyển tăng thêm giúp tới vị trí có lợi |
| HP đồng đội 10 | Xét HP thiếu của đồng đội còn sống, đúng điều kiện phe/team của engine |

Ngưỡng HP ban đầu có thể thử 40% cho mức nguy cấp; đây là tham số cần điều chỉnh sau
kiểm thử, không phải thay luật hồi máu. Nếu đã dùng item hồi máu thì không dùng x2
hoặc POW trong cùng lượt khi luật chỉ cho một item.

### P4 — Item bay và địa hình

- [ ] Item bay 1: chọn điểm đáp an toàn, tìm đường đạn bay tới điểm đó, xác nhận vị trí
  sau đáp và tuân thủ việc kết thúc lượt của engine; không coi là đổi tọa độ tức thời.
- [ ] Chỉ dùng bay khi đường đi bộ không đạt mục tiêu và lợi ích đáng tiêu item.
- [ ] Sau khi bay ổn định, thêm ngưng gió/phá đất theo từng loại, có mô phỏng đúng hiệu ứng.
- [ ] Tạm loại tự sát, UFO và các item nhiều hiệu ứng khỏi bản đầu; mở từng loại sau test riêng.

Nghiệm thu: đạn bay đúng loại, không đáp xuống vực/ngoài map, không bắn thêm trái luật
sau hành động tiêu hao lượt. Có thể phát hành P1–P3 trước, không cần đợi toàn bộ P4.

### P5 — Quản trị và quan sát

- [ ] Thêm bật/tắt AI chiến thuật, di chuyển và dùng item trong `/admin/bots`.
- [ ] Preset `PASSIVE` (không di chuyển/item), `BALANCED`, `AGGRESSIVE`, `SUPPORT`;
  chế độ chọn mục tiêu RANDOM/LOW_HP giữ là cấu hình riêng.
- [ ] Cấu hình ngưỡng hồi máu, item cho phép, giới hạn bước, độ trễ suy nghĩ và ngân sách tìm góc.
- [ ] Hiển thị hành động gần nhất, lý do chọn/bỏ di chuyển/item, số lần bị chặn,
  thời gian tính và số kết quả tìm góc hết hạn. Log có giới hạn để tránh ghi mỗi tick.
- [ ] Đổi cấu hình qua hàng đợi game loop và audit như admin bot hiện tại; áp dụng từ
  lượt tiếp theo, kiểm tra quyền và đầu vào ở server.
- [ ] Feature flag cho phép quay lại AI cũ khi cần. Cấu hình đợt đầu theo cơ chế RAM/env
  hiện có; chưa cần ALTER DB. Nếu bổ sung lưu preset bền vững, làm migration riêng và cập nhật army.sql.

### P6 — Kiểm thử và phát hành

- [ ] Unit test có seed cố định cho chấm điểm vị trí/chọn item; không phụ thuộc sleep thực.
- [ ] Integration test gọi engine thật: thể lực, đóng băng, x2, va chạm, trừ item,
  giới hạn một item/lượt, loại đạn sau item, đổi lượt và trận kết thúc giữa lúc tính.
- [ ] Mở rộng `tests/BotBehaviorTest.java`, `tests/AdminBotsTest.java` và script hiện có.
- [ ] Dùng hai client thật quan sát bot: một trận địa hình phẳng, một map có vực,
  một tình huống bot thấp HP, một trận phe với đồng đội thấp HP; lưu log và ảnh.
- [ ] Đối chiếu vị trí, HP, item và thứ tự hành động ở cả hai client với trạng thái server.
  Runner Auto-play hai người hiện từ chối người thứ ba nên cần kịch bản nghiệm thu bot riêng.
- [ ] Benchmark 10/50/100 bot đang chiến đấu và 5.000 bot nhàn rỗi: game tick p95/p99,
  CPU, heap, số worker, độ dài hàng đợi, timeout. Đặt ngưỡng theo baseline trước khi bật mặc định.
- [ ] Bật thử ít bot ở phòng kiểm thử, theo dõi rồi mới mở rộng.

## 4. Thứ tự bàn giao

1. **Đợt A:** P1 + P2, bot di chuyển hợp lệ; giữ item cũ tới khi P3 sẵn sàng.
2. **Đợt B:** P3 + cấu hình cơ bản P5, nghiệm thu hồi máu/POW/x2/đi x2/hỗ trợ.
3. **Đợt C:** P4 + quan sát nâng cao P5, hoàn tất nghiệm thu và tải P6.

Mỗi đợt đều phải chạy regression bot, trận thường và boss; không đợi đến đợt C mới test.
Chưa sửa source hoặc bật hành vi mới trong bước lập kế hoạch này.

## Cập nhật 19/09/2026

Đã triển khai mã đợt A và 86 kiểm tra fixture đạt; hướng dẫn và khác biệt thiết kế tại
[bot-phase-a.md](bot-phase-a.md). Tìm góc dùng ngân sách trên game loop, không cần
worker/snapshot bất đồng bộ cho nhánh mới. Nghiệm thu client thật và tải còn chờ;
feature flag mặc định tắt. Đợt B/C chưa triển khai.

## Cập nhật đợt B/C — 19/09/2026

Đã triển khai chính sách item, flight/phá đất, cấu hình preset/allowlist/ngưỡng/ngân sách,
loadout có cấp kho qua admin và metrics. Đã sửa thêm va chạm xác chết và góc bắn cũ.
141 test Java đạt; nghiệm thu ba trận bằng hai client + hai bot, thêm một trận trên
bản sửa xác chết. Benchmark fixture đã chạy. Xem [bot-phase-b-c.md](bot-phase-b-c.md)
và [bằng chứng](testing/bot-bc-evidence/README.md). Các mục checklist ở trên là kế hoạch
ban đầu; nghiệm thu GUI cho item 5/6/10 và mở rộng production vẫn được ghi riêng trong TODO.


## Kết quả kiểm thử mở rộng B/C

Xem [báo cáo 19/09/2026](testing/bot-bc-soak.md): 14 trận hoàn thành, 2 timeout,
3 trận theo kế hoạch chưa chạy do runner dừng. Item 5/6/10 đã có bằng chứng client/kho,
nhưng còn bế tắc nhiều lượt cần xử lý trước khi coi nghiệm thu B/C hoàn tất.


Cập nhật sau sửa: [bot-stalemate-fix.md](bot-stalemate-fix.md) — bộ cơ bản 10/10,
Mê cung 3/3 (một hòa do guard), hỗ trợ 3/3 đạt. Đã có guard dọn trận chỉ còn bot;
không đánh đồng trận hòa với cải thiện tỷ lệ bắn trúng.
