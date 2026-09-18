# Phân tích và nâng cấp bot

Bot kế thừa User, chạy theo game loop và dùng logic trận chung. Các thay đổi bên dưới
đã được triển khai; phần lộ trình chưa được thực hiện.

## Đã sửa

| Điểm cũ | Thay đổi |
| --- | --- |
| remove() thêm lại bot vào danh sách | Rời phòng, xóa bot khỏi danh sách và chỉ mục ID/tên; gọi lặp an toàn |
| Cờ remove bị bỏ qua khi bot bị lock | Xử lý xóa trước các hành động và kể cả bot bị lock |
| Có thể đăng ký cùng bot nhiều lần | Từ chối ID trùng và bot đã xóa |
| Timer rời phòng chạy độc lập với lần vào phòng | Đặt lại timer khi đổi phòng, bắt đầu/kết thúc trận; không rời giữa trận vì timeout |
| Bot chủ phòng thử start dù không có người thật | Mặc định yêu cầu có người thật với session còn connected |
| Danh sách mục tiêu có thể tồn tại qua lượt/trận | Xóa khi chuyển trạng thái/lượt; loại mục tiêu chết hoặc đã rời danh sách trận |
| Bước dịch chuyển nhỏ chỉ có -1 và 0 | Cho phép -1, 0 và +1 |
| Lời mời đến khi bot đã ở phòng | Bỏ lời mời xung đột, tránh tham gia sau bằng lời mời cũ |
| Gọi generateBot() lại tạo thêm 5.000 bot | Chỉ tạo đủ đến số lượng cấu hình |

## Tính năng mới

- Cấu hình số lượng bot lúc khởi động, bao gồm 0 để không tạo bot.
- Bật tự tìm phòng sơ cấp công khai: còn chỗ, đủ tiền cược, chưa bắt đầu.
  Mặc định yêu cầu phòng có người thật còn kết nối để tránh bot tự lấp đầy phòng trống.
  Chu kỳ thử tìm phòng vẫn ngẫu nhiên từ 3 giây đến dưới 10 phút.
- Chế độ mục tiêu `RANDOM` hoặc `LOW_HP`. LOW_HP ưu tiên HP tuyệt đối thấp nhất
  trong danh sách đối thủ hợp lệ; nếu không tìm được đường bắn, thử mục tiêu khác.
- Cấu hình thời gian sẵn sàng và rời phòng chờ. Các điều kiện start của RoomWait
  vẫn được kiểm tra, không bypass yêu cầu sẵn sàng của người chơi khác.

Cấu hình chung qua biến môi trường, áp dụng sau khởi động lại. Trang `/admin/bots`
đã hỗ trợ xem/tạo/xóa/rời phòng chờ và đổi chế độ mục tiêu từng bot. Các thay đổi từng
bot lưu trong RAM, lệnh qua game loop và có audit. Xem hướng dẫn tại README.

## Điểm cần cải thiện tiếp

1. **Ngân sách tính đường đạn:** BulletTrajectory vẫn tạo thread cho mỗi lần tìm góc.
   Cần executor có số worker/hàng đợi giới hạn, timeout và hủy công việc khi hết lượt.
   Cờ hoàn tất và kết quả cần cơ chế công bố giữa các thread; snapshot vật lý cần tránh
   đọc bản đồ đang thay đổi. Đây là đường dùng chung với boss nên cần kiểm thử riêng.
2. **Độ chính xác hợp lý:** thêm EASY/NORMAL/HARD với giới hạn số lần thử góc/lực,
   thời gian suy nghĩ và sai số có kiểm soát. LOW_HP hiện chỉ đổi chọn mục tiêu,
   không phải một mức độ khó và không sửa may mắn/sát thương.
3. **Địa hình:** nhánh isLand=false vẫn bỏ va chạm đất trong mô phỏng khi bot bí đường.
   Cần thay bằng tìm vị trí bắn có đường đi hợp lệ hoặc bỏ lượt; không nên coi đường
   mô phỏng xuyên đất là bảo đảm bắn thật trúng.
4. **Di chuyển và vật phẩm:** kiểm tra kết quả PathSimulator.simulate() trước khi
   chọn điểm đến; tránh vực, tránh đồng đội, bổ sung dùng teleport và vật phẩm chiến thuật.
5. **Hiệu năng bot nhàn rỗi:** hiện vẫn duyệt toàn bộ danh sách mỗi tick. Có thể tách
   bot hoạt động và hàng đợi đánh thức theo thời gian; đo CPU trước/sau thay đổi.
6. **Tạo trang bị:** đã giới hạn tối đa 32 lần thử mỗi trang bị và dừng nếu số slot
   không giảm, để tạo bot trên web không lặp vô hạn. Cần tiếp tục đo thời gian tạo bot cấp cao.
7. **Quản trị:** đã có danh sách bot, phòng/trận và thao tác qua game loop + audit.
   Còn thiếu metrics số lần tính góc, thời gian tính, tỷ lệ trúng và cấu hình lưu bền.
   Audit hiện truy cập DB trong lúc xử lý lệnh; cần đo độ trễ game tick khi DB chậm
   trước khi dùng cho nhiều thao tác liên tục.
8. **Cân bằng:** tạo bot theo khoảng cấp độ người chơi, giới hạn phần thưởng khi
   đánh bot; đánh giá sức mạnh trước khi thay phân bố điểm hoặc trang bị hiện tại.

## Kiểm chứng

`bash tests/bot-behavior.sh` build server và chạy 40 kiểm tra bằng fixture, không cần
DB hay kết nối mạng. Có thêm 15 kiểm tra quản trị bot. Bao phủ cấu hình hợp lệ/sai, vòng đời phòng, removal, quyền
start theo người thật, lời mời, lọc/chọn mục tiêu và tự tìm phòng.

Chưa chạy nghiệm thu bằng client game thật hoặc benchmark tải. Cần thử thêm trận
PvP/boss có bot, người thật thoát khi bot làm chủ phòng, quay lại phòng sau trận,
và so sánh CPU với BOT_COUNT=100/5000 trước khi điều chỉnh mặc định.
