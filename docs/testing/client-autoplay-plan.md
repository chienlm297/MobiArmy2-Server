# Auto-play: hai client thật

Mục tiêu: A/B đăng nhập riêng, vào cùng phòng, chơi ba trận có kết quả từ server,
đăng nhập lại, ghi nhận broadcast và đối chiếu dữ liệu. Không tính timeout là thành công.

## Các mốc

- [x] M1: bộ điều khiển trạng thái, cấu hình, RMS/log riêng, F8 bật/tắt và F9 dừng.
- [x] M2: xác nhận phòng/chủ phòng/đối tác, ready/start không gửi trùng.
- [x] M3: Gunner chọn mục tiêu/tính góc có giới hạn, bắn mỗi lượt một lần; hồi máu tùy chọn.
- [x] M4: runner hai client, ba trận, đăng nhập lại, JSONL/ảnh/báo cáo.
- [x] M5: broadcast ở sảnh/trong trận; thay dữ liệu qua admin; đối chiếu SQL/audit sau đăng nhập.

## Kiểm thử

Logic: timeout, dừng khi đang chờ, sự kiện lặp, sai phòng/chủ phòng, mất kết nối,
không gửi lại bắn khi chưa biết kết quả, không tính trận chưa được server xác nhận.

Tích hợp: dùng database và tài khoản kiểm thử riêng. Đọc/ghi DB chỉ qua bộ chạy phía
server hoặc admin; không đưa mật khẩu DB vào client. Không sửa luật thắng hoặc gửi
kết quả trận giả để đạt ba trận.

Chỉ đánh dấu các mốc sau khi có bằng chứng. Client GUI phải chạy trên desktop có
OpenGL/display; nếu môi trường không hỗ trợ thì ghi rõ ca chưa chạy và nguyên nhân.

## Kết quả triển khai

Hướng dẫn: [client-autoplay.md](client-autoplay.md). M1–M4 đã chạy bằng hai client thật;
M5 xác nhận broadcast và điều chỉnh lượng online, SQL/audit sau đăng nhập lại.
Hồi máu mới có unit test; chưa nghiệm thu E2E. Chưa hỗ trợ các nhân vật khác Gunner.
