# Bot chiến thuật — đợt A

## Bật và chạy

Đã bổ sung bộ điều khiển lượt và chọn đường đi cho bot phía server. Mặc định vẫn dùng
AI cũ; bật ở server kiểm thử trước:

```bash
BOT_TACTICAL=true bash run-manual.sh
```

Hoặc Docker Compose:

```bash
BOT_TACTICAL=true docker compose up -d --build server
```

Biến được đọc khi khởi động JVM. Để quay lại AI cũ, đặt `BOT_TACTICAL=false` và khởi động
lại server sau khi kết thúc trận đang chạy. Không cần ALTER DB hoặc sửa client.
Đợt A giữ chính sách item cũ (POW/hồi máu); item chiến thuật và giao diện cấu hình thuộc đợt B/C.

## Hành vi đã thêm

- `BotTurnController` quản lý item cũ → di chuyển → ngắm → bắn/kết thúc lượt.
  Ràng buộc trận/lượt, bỏ kế hoạch khi chết, bị khóa, bị xóa hoặc đổi lượt.
- `MovementStep` dùng chung cho dự đoán và `Player.move()`: tường, biên map,
  thể lực, đi x2; caller kiểm tra đóng băng. Sửa lỗi cho đi thêm một bước khi đã hết thể lực.
- `BotMovementPlanner` thử tối đa 48 bước mỗi hướng, chấm điểm tại các khoảng 8 bước.
  Ưu tiên khoảng cách bắn và hành lang ít bị chắn, phạt gần đồng đội/mép vực.
  Không chọn đường rơi quá 24 pixel, ra ngoài map hoặc không có lợi hơn đứng yên.
- Đi tối đa 8 bước mỗi đoạn qua `User.moveLocation()`, phát cập nhật vị trí qua engine.
  Kiểm tra đường lại trước từng đoạn và chờ `timeUntilAction2` trước hành động tiếp theo.
- Tìm góc hợp tác trên game loop, không tạo worker mới cho bot chiến thuật và không
  bỏ va chạm đất. Mỗi lần thử tối đa 12 góc/lực, khoảng 1 ms, mỗi đường đạn tối đa
  600 frame; deadline toàn lượt 8 giây, tối đa 4 lần khởi tạo tìm góc/mục tiêu.
- Toàn bộ bot chiến thuật chia ngân sách khoảng 4 ms mỗi lần `updateBot()`;
  quay vòng từ bot kế tiếp khi hết ngân sách. Ngân sách thời gian là giới hạn hợp tác,
  không phải cam kết thời gian thực cứng cho JVM/GC.
- Tính trực tiếp trên game loop nên không có kết quả worker trả về muộn. Kiểm tra lại
  tọa độ bot/mục tiêu và gió trước khi tiếp tục; chỉ công nhận kết quả trên lần mô phỏng
  hiện tại. Đạn thử, kể cả đạn tách nhánh, không tạo hố hoặc gây sát thương thật.
- Không nhắm đối thủ đang tàng hình. Nếu không còn phương án hoặc hết deadline,
  kết thúc lượt theo engine, không bắn xuyên đất hoặc dùng kết quả cũ.

## Giới hạn hiện tại

Điểm vị trí dùng hành lang thẳng làm ước lượng rẻ; chưa so sánh toàn bộ đường đạn
cho từng vị trí ứng viên. Bot có thể không chọn vị trí tối ưu hoặc bỏ lượt khi ngân sách
không đủ. Khoảng cách mục tiêu 160 pixel và ngưỡng rơi 24 pixel cần cân chỉnh bằng trận thật.
Đường `BulletTrajectory.start()` cũ của boss giữ nguyên; bộ điều khiển mới chỉ dùng
`stepSearch()` trên game loop. Chưa thay AI riêng của Robot/RobotSpider.

## Kiểm thử

Chạy `bash tests/bot-behavior.sh`:

- 40 kiểm tra bot/vòng đời/phòng/mục tiêu.
- 15 kiểm tra quản trị bot.
- 31 kiểm tra chiến thuật: đồng nhất bước dự đoán/thật, hết thể lực, đóng băng,
  đi x2, tường, vực, biên map, giữ vị trí tốt, deadline và không lặp hành động.
- Kiểm tra tìm đường đạn hữu hạn cho 10 loại đạn thường; mô phỏng không sửa terrain/HP.
- Kiểm tra đường tìm góc cũ mà boss sử dụng vẫn tìm được mục tiêu trong fixture.
- Fixture điều khiển đi qua engine thật, phát nhiều cập nhật vị trí, ngắm và bắn một lần.

Kết quả trên là test Java bằng fixture, không phải nghiệm thu GUI. **Chưa chạy hai
client thật quan sát bot hoặc benchmark tải 10/50/100 bot chiến đấu và 5.000 bot nhàn rỗi.**
Cần làm các bước này trước khi bật mặc định. Runner Auto-play hai người hiện từ chối
người thứ ba nên không dùng kết quả Auto-play trước đó làm bằng chứng cho bot mới.

## Bản tiếp theo

Đợt B/C đã bổ sung item, cấu hình admin và nghiệm thu client thật. Xem
[bot-phase-b-c.md](bot-phase-b-c.md) để biết hành vi hiện tại; mục item cũ ở trên mô tả riêng bản đợt A.
