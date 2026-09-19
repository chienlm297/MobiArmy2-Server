# Bằng chứng Auto-play — 18/09/2026

Bản cuối: [run-11/summary.md](run-11/summary.md), [admin-check.json](run-11/admin-check.json).

- Build client thành công, 25 kiểm tra controller đạt.
- Hai client LibGDX thật: 3/3 trận mỗi client, đăng nhập lại thành công.
- Mỗi client nhận hai broadcast (phòng chờ và trong trận).
- Lượng mỗi tài khoản: 1011 → 1022; client sau relogin và SQL khớp.
- Mỗi thao tác có đúng một wallet transaction và một audit theo mã lượt chạy.
- Kiểm tra report: từ chối thiếu file, thiếu kết quả và JSON hỏng.
- Ảnh run-11 chụp sau render, đã kiểm tra chiều ảnh.

Fixture: MySQL/server Compose riêng, hai user 6/7, Gunner với chỉ số tấn công
1500 do admin thiết lập trước khi chạy. Luật trận giữ nguyên; thời gian và chiến thắng
phụ thuộc vị trí/gió/địa hình. Không dùng kết quả này đánh giá cân bằng hoặc tải server.

run-9 là lượt nghiệm thu trước khi sửa thời điểm chụp ảnh. Không lưu profile RMS,
mật khẩu hay database dump trong bằng chứng. Hồi máu chỉ có unit test, chưa chạy E2E.
