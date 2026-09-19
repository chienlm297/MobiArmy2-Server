# Tổng hợp sửa lỗi và nâng cấp bot

Đây là tài liệu tổng hợp chính về bot của server. **Mọi thay đổi liên quan đến bot
về sau phải cập nhật file này**, theo [quy định dự án](../AGENTS.md).

Mốc nghiệm thu hiện tại: 19/09/2026. Nội dung dưới đây mô tả trạng thái đã triển khai;
các báo cáo theo đợt được giữ riêng để tra cứu lịch sử và bằng chứng.

## 1. Vòng đời và quản lý bot

- Sửa `remove()` từng thêm lại bot vào danh sách: rời phòng, xóa khỏi danh sách và
  chỉ mục ID/tên; gọi lặp an toàn.
- Xử lý xóa cả khi bot bị khóa; chống đăng ký trùng ID hoặc đăng ký lại bot đã xóa.
- Reset timer khi vào/đổi phòng, bắt đầu và kết thúc trận. Không rời giữa trận vì
  timeout của lần vào phòng trước.
- Bỏ lời mời xung đột hoặc không còn phù hợp khi bot đã ở phòng khác.
- `generateBot()` chỉ tạo đủ số lượng cấu hình, không tạo thêm cả lô khi gọi lại.
- Sửa bước dịch chuyển nhỏ để có cả hướng trái, đứng yên và phải.
- Giới hạn số lần thử tạo trang bị/ép ngọc, dừng khi không tiến triển để tránh lặp
  vô hạn lúc tạo bot qua web admin.

## 2. Cấu hình chung và tham gia phòng

| Cấu hình | Chức năng |
|---|---|
| `BOT_COUNT` | Số bot tạo khi khởi động; hỗ trợ 0 |
| `BOT_AUTO_JOIN` | Bật/tắt tự tìm phòng |
| `BOT_REQUIRE_HUMAN` | Mặc định yêu cầu người thật còn kết nối khi tự vào/start phòng |
| `BOT_READY_DELAY_MS` | Thời gian chờ sẵn sàng |
| `BOT_LEAVE_DELAY_MS` | Thời gian chờ trước khi rời phòng chờ |
| `BOT_TARGET_MODE` | `RANDOM` hoặc `LOW_HP` |
| `BOT_TACTICAL` | Bật AI chiến thuật mới; mặc định false |

Tự tìm phòng có kiểm tra phòng công khai, chưa bắt đầu, còn chỗ và đủ tiền cược.
Bot vẫn tuân thủ yêu cầu sẵn sàng và luật start của phòng. `LOW_HP` chọn đối thủ hợp
lệ có HP tuyệt đối thấp nhất; đây không phải mức độ khó.

Biến môi trường được đọc khi JVM khởi động. Cấu hình riêng từng bot trên admin có
thể bật AI chiến thuật cho bot đó dù cờ chung đang tắt.

## 3. Di chuyển và điều khiển lượt — đợt A

- `BotTurnController` quản lý dùng item → di chuyển → ngắm → bắn/kết thúc lượt.
  Hủy kế hoạch không còn phù hợp khi đổi trận/lượt, chết, bị khóa hoặc xóa.
- `MovementStep` dùng chung cho dự đoán và di chuyển thật; sửa lỗi đi thêm một bước
  khi đã hết thể lực.
- Kiểm tra tường, biên map, thể lực, đóng băng, đi x2 và nền. Không chọn đường rơi
  quá 24 px hoặc ra ngoài map.
- Chấm điểm vị trí dựa trên khoảng cách, vật cản, đồng đội và mép vực. Đi từng đoạn
  tối đa 8 bước qua engine, phát cập nhật vị trí và chờ thời gian hành động.
- Tìm góc trên game loop cho AI chiến thuật, không tạo worker mới mỗi lần ngắm.
  Ngân sách chung khoảng 4 ms/tick, khoảng 1 ms/bot/lần gọi, tối đa 600 frame cho
  một phương án; có giới hạn số lần tìm góc và thời gian mỗi lượt.
- Kiểm tra lại vị trí, mục tiêu và gió. Mô phỏng đạn, kể cả đạn tách nhánh, không
  gây sát thương hoặc sửa địa hình thật.

Đây là ngân sách hợp tác, không phải cam kết thời gian thực cứng của JVM/GC.

## 4. Item chiến thuật — đợt B/C

| Item | Hành vi hiện tại |
|---|---|
| HP `0` | Hồi máu theo ngưỡng, ưu tiên khi nguy hiểm |
| Bay `1` | Tìm điểm đáp có nền, mô phỏng đường bay trước khi dùng |
| Bắn x2 `2` | Dùng sau khi có đường bắn hợp lệ |
| Đi x2 `3` | Dùng khi giúp đến vị trí tốt hơn |
| Ngưng gió `5` | Dùng trước khi ngắm nếu gió mạnh |
| Phá đất `6` | Tìm vật cản để phá khi bí đường bắn |
| HP đội `10` | Chữa đồng đội còn sống khi có lợi |
| POW `100` | Kiểm tra nộ, tính lại đường đạn sau khi kích hoạt |

Các sửa lỗi đi kèm:

- Kiểm tra kho trước khi tiêu slot, quyền dùng item hoặc phát hiệu ứng.
- Không cho giả slot POW để bỏ qua yêu cầu nộ.
- Tối đa một item/lượt; không tự nạp kho giữa trận. Cấp item là thao tác admin riêng.
- Item cơ bản 0/1 theo luật kho hiện có; item đặc biệt trừ kho thật; POW tiêu nộ.
- Preview bay không dịch chuyển người chơi thật hoặc làm thay đổi trạng thái may mắn.
- Bay là phát bắn kết thúc lượt, không dịch chuyển rồi bắn thêm.
- Sửa lỗi bỏ qua bước cân nhắc ngưng gió khi bot vừa đi đến đích.

Các item ngoài danh sách trên, như tự sát/UFO/tàng hình, chưa nằm trong allowlist
của AI chiến thuật hiện tại.

## 5. Web admin quản lý bot

- Tìm kiếm, lọc, phân trang; xem phòng, trạng thái và nhân vật bot.
- Tạo, xóa, rời phòng chờ và đổi chế độ chọn mục tiêu từng bot.
- Bật/tắt AI, di chuyển và item riêng; preset `PASSIVE`, `BALANCED`, `AGGRESSIVE`,
  `SUPPORT`. Preset là phong cách hành động, chưa phải EASY/NORMAL/HARD.
- Chỉnh ngưỡng HP, số bước, thời gian suy nghĩ, ngân sách lượt và allowlist item.
- Đặt bốn slot mang theo, cấp kho với kiểm tra giới hạn; chặn cấp giữa trận.
- Hiển thị quyết định gần nhất, số đoạn đi, item đã dùng, số lần/thời gian tìm góc,
  `Timeout` và `Bỏ lượt`.
- Thao tác qua hàng đợi game loop có giới hạn và hạn chờ; kiểm tra lại quyền,
  bảo vệ CSRF, ghi audit yêu cầu/kết quả. HTTP đọc snapshot.
- Chặn xóa/rời phòng khi đang chiến đấu hoặc xử lý hành động.

**Cấu hình riêng từng bot hiện lưu RAM**, áp dụng từ lượt tiếp theo và mất sau
restart. Audit lưu DB, nhưng chưa có lưu bền policy/loadout riêng qua restart.
Các nâng cấp AI hiện tại không yêu cầu ALTER DB riêng.

## 6. Sửa bắn xác chết

- AI cũ không dùng lại góc ngắm nếu mục tiêu đã chết, rời trận hoặc đổi vị trí;
  kiểm tra cả tọa độ người bắn.
- AI mới kiểm tra mục tiêu trong lúc tìm góc và hủy phương án không còn hợp lệ.
- Va chạm đạn bỏ qua người đã chết hoặc HP bằng 0.
- Không chọn mục tiêu đang tàng hình.
- Cờ hoàn tất tìm góc bất đồng bộ cũ có công bố bộ nhớ bằng `volatile`.

Đạn đã bắn ra trước khi mục tiêu chết không được đổi hướng giữa đường. Gói tin
client không chứa ID mục tiêu ngắm, nên hồi quy Java vẫn là bằng chứng riêng cho
việc hủy ngắm xác chết.

## 7. Sửa trận bế tắc

- Giữ góc/lực và frame đang mô phỏng khi hết ngân sách CPU để tiếp tục ở tick sau;
  không bỏ phương án chỉ vì bị ngắt giữa chừng.
- Nhớ tối đa bốn vị trí ngắm thất bại và thử vị trí khác.
- Gunner có thể bắn phá vật cản bằng đạn thường khi phù hợp và không POW; không
  cấp item miễn phí. Kiểm tra điểm phá cách bản thân/đồng đội sống ít nhất 96 px.
- Phân biệt “không còn mục tiêu nhìn thấy” với “đã thử mục tiêu sống nhưng không
  có đường bắn”; thêm bộ đếm bỏ lượt độc lập timeout một lượt.

`BotMatchGuard` chỉ áp dụng PvP có ít nhất hai bot sống và **tất cả người còn sống
đều là bot server**. Engine kiểm tra thắng/thua bình thường trước:

| Điều kiện | Xử lý |
|---|---|
| Không đổi HP trong 120 giây | Kết thúc hòa |
| Không còn người kết nối theo dõi trong 30 giây | Kết thúc hòa/dọn trận |
| HP thay đổi | Reset thời gian không tiến triển |
| Người xem kết nối lại | Hủy bộ đếm không người theo dõi |
| Còn người thật sống hoặc trận boss | Không áp dụng guard này |

Di chuyển, đổi lượt hoặc bắn trượt không gia hạn giới hạn HP. Guard dùng đường hòa
hiện có, không tự chọn bên thắng; có log lý do `NO_PROGRESS`/`UNATTENDED`.
Guard áp dụng cả AI cũ và mới. Trận hòa không chứng minh bot đã tìm được cách thắng.

## 8. Kiểm thử và bằng chứng hiện tại

- Runner hai client LibGDX thật có mời bot fixture, chọn map và ghi log vị trí,
  item, HP, đạn; đối chiếu hai phía, số trận và đăng nhập lại.
- **162 kiểm tra Java chính**, gồm 21 ca chống bế tắc; không cộng trùng khi chạy
  riêng `bot-stall.sh`.
- **1.000 lượt mô phỏng**, cùng các ca item riêng. Đây không phải 1.000 trận network.
- Nghiệm thu mới nhất: **10/10 cơ bản, 3/3 Mê cung, 3/3 hỗ trợ**. Cả A/B đăng nhập
  lại thành công; một trận Mê cung hòa do guard `NO_PROGRESS`.
- Gói tin A/B khớp; 12 đối chiếu kho theo bot/item đạt. Không có exception Java hoặc
  container restart trong các lượt nghiệm thu này.
- Ca chủ động ngắt hai client đã trở về phòng chờ, nhưng không quan sát được trigger
  `UNATTENDED`. Giới hạn 30 giây mới được xác minh bằng kiểm thử Java.
- Có benchmark fixture 10/50/100 bot chiến đấu và 5.000 bot nhàn rỗi; chưa phải SLA
  hay đo tải server production đầy đủ.

Các lần thử trước từng có timeout và vẫn được giữ trong báo cáo lịch sử. Số trận
trên chỉ là lượt nghiệm thu mới nhất, không cộng dồn các đợt hoặc đếm một trận hai lần
vì có hai client. Nghiệm thu thật hiện tập trung Gunner trên ba map, không chứng nhận
mọi nhân vật/map boss.

## 9. Những phần chưa triển khai đầy đủ

- Lưu bền cấu hình bot vào DB và khôi phục sau restart.
- Độ khó EASY/NORMAL/HARD và sai số ngắm có kiểm soát.
- Tránh sát thương đồng đội cho mọi đường đạn/vũ khí; hiện mới có các kiểm tra cục bộ.
- Tối ưu vị trí, đường đi và chiến thuật cho mọi map/nhân vật.
- Nâng cấp AI riêng của boss như `Robot`/`RobotSpider`; không nhầm bot kế thừa `User`
  với AI boss. Đường tìm góc bất đồng bộ cũ của boss vẫn tồn tại.
- Đo tải production, độ trễ admin khi DB chậm, cân bằng sức mạnh/phần thưởng và tối ưu
  lịch đánh thức bot nhàn rỗi.

## 10. Build, chạy và kiểm thử

Dừng tiến trình cũ trước khi chạy bản build mới:

```bash
BOT_TACTICAL=true bash run-manual.sh
# hoặc
BOT_TACTICAL=true docker compose up -d --build server
```

```bash
bash tests/bot-behavior.sh
bash tests/bot-soak.sh
bash tests/bot-stall.sh
bash tests/bot-benchmark.sh
```

Thay đổi biến môi trường cần restart. Không tự xem việc sửa source là đã áp dụng
cho server đang chạy. Các stack nghiệm thu riêng đã được dọn; không khởi động lại
server của người dùng trong các đợt nghiệm thu đó.

## Tài liệu chi tiết

- [Vòng đời và nâng cấp ban đầu](bot-improvements.md) — có các mục mô tả trạng thái cũ.
- [Đợt A](bot-phase-a.md).
- [Đợt B/C](bot-phase-b-c.md).
- [Autoplay client](testing/client-autoplay.md).
- [Soak trước sửa bế tắc](testing/bot-bc-soak.md).
- [Bản sửa bế tắc và nghiệm thu mới nhất](bot-stalemate-fix.md).
- [Bằng chứng nghiệm thu mới nhất](testing/bot-stalemate-fix-evidence/README.md).
- [Kế hoạch chiến thuật](bot-tactical-plan.md).

## Lịch sử cập nhật tài liệu

- **19/09/2026 — Tổng hợp ban đầu:** hợp nhất các thay đổi vòng đời, cấu hình, admin,
  di chuyển, item, xác chết, bế tắc và kiểm thử. Mốc kiểm chứng: 162 kiểm tra Java,
  1.000 lượt mô phỏng, 16/16 trận mới nhất kết thúc (một hòa do guard). Những giới hạn
  chưa xác minh và phần chưa triển khai được ghi rõ ở trên. Thêm quy định bắt buộc
  duy trì tài liệu này trong `AGENTS.md` theo yêu cầu người dùng.
