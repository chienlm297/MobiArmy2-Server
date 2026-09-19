# Sửa bế tắc bot B/C

## Thay đổi

1. Bộ tìm đường đạn giữ lại góc/lực và frame đang tính khi hết ngân sách CPU, tiếp tục
   ở tick sau. Trước đây mỗi lần yield làm bỏ luôn phần còn lại của phương án đó.
   Ngân sách tổng 4 ms/tick, 1 ms/bot/lần gọi và giới hạn 600 frame/phương án vẫn giữ.
2. Sau lượt không tìm được đường bắn, bot nhớ tối đa bốn vị trí thất bại và thử điểm
   đứng khác dù điểm đó không tối ưu theo khoảng cách 160 px. Vẫn kiểm tra thể lực,
   đóng băng, biên map, nền và giới hạn rơi 24 px; không dịch chuyển tức thời.
3. Sau khi không tìm được đường bắn trực tiếp, ưu tiên phương án bay/phá đất đang có.
   Gunner dùng đạn thường để phá vật cản nếu không có item 6 và không POW; không cấp
   thêm item. Điểm phá phải cách bản thân và đồng đội sống ít nhất 96 px; kiểm tra lại
   điểm dự kiến trước bắn. Đây chưa phải bộ tránh sát thương đồng đội cho mọi vũ khí.
4. Phân biệt thông báo không còn mục tiêu nhìn thấy với đã thử hết mục tiêu sống nhưng
   không có đường bắn. Admin thêm bộ đếm `Bỏ lượt`, độc lập `Timeout` của từng lượt.

## Chống trận không kết thúc

`BotMatchGuard` chạy trong game loop, chỉ áp dụng **PvP mà tất cả người còn sống đều
là bot server, có ít nhất hai bot sống**. Engine kiểm tra thắng/thua bình thường trước.

- Còn người kết nối theo dõi: không có thay đổi HP trong 120 giây thì kết thúc hòa.
- Không còn người kết nối: kết thúc hòa sau 30 giây để dọn trận không người theo dõi.
- Di chuyển, đổi lượt hoặc bắn trượt không gia hạn giới hạn HP. HP thay đổi thì bắt đầu
  lại 120 giây; người xem kết nối lại hủy bộ đếm 30 giây.
- Không áp dụng khi còn người chơi thật sống, hoặc ở trận boss.
- Dùng kết quả hòa `typeComplete=3` và đường kết thúc hiện có, không tự chọn bên thắng.
  Server ghi `Bot match drawn: ... reason=NO_PROGRESS/UNATTENDED`.
- Các giới hạn là hằng số trong `BotMatchGuard`, không cần ALTER DB. Có hiệu lực cho
  cả AI cũ lẫn mới; `BOT_TACTICAL` vẫn chỉ điều khiển AI chiến thuật.

Giới hạn này đảm bảo không còn vòng lặp trận chỉ có bot kéo dài vô hạn trong trường
hợp không đổi HP. Nó không đảm bảo mọi map đều có đường bắn hoặc mọi trận kết thúc
bằng chiến thắng. Trận hòa do bảo vệ cần được tách riêng trong thống kê chất lượng AI.

## Kiểm thử

```bash
bash tests/bot-behavior.sh
bash tests/bot-soak.sh
bash tests/bot-stall.sh
```

162 kiểm tra trong `bot-behavior.sh` (gồm 21 ca chống bế tắc mới), cùng 1.000 lượt
mô phỏng + bốn ca item. Các ca mới bao gồm: cửa sổ thời gian,
HP reset, kết nối lại, loại trừ người thật/boss, ưu tiên chiến thắng, không hòa lặp,
đổi vị trí khi ngắm thất bại, giữ freeze và tiếp tục phương án đạn bị ngắt ngân sách.

## Kết quả client thật sau sửa

| Bộ thử | Trước sửa | Sau sửa | Hòa do guard |
|---|---|---|---:|
| Cơ bản, map 0, loadout 0/1/2/3 | 8/10 rồi timeout | **10/10 PASS** | 0 |
| Mê cung, map 20, chỉ item 6 | 0/3, trận đầu timeout | **3/3 PASS** | 1 |
| Hỗ trợ, map 2, loadout 5/6/10/1 | 6/6 ở lần thử trước | **3/3 PASS hồi quy** | 0 |

Mỗi bộ dùng hai client LibGDX thật + hai bot Gunner trên DB/server riêng. Cả A/B đều
đăng nhập lại thành công. Tổng **16 trận kết thúc**, không timeout; một trận Mê cung
kết thúc bằng hòa chống bế tắc, không coi là bot đã tìm được cách thắng. Các lần chạy
không cố định seed của trận, nên không phải so sánh thống kê trên cùng chuỗi trạng thái.

Hai client nhận cùng thứ tự/nội dung 768 POSITION, 97 ITEM, 198 SHOT và 136 HP của bot.
Không ghi nhận bot bắn sau HP=0; Java regression kiểm tra riêng việc hủy mục tiêu chết.
12 đối chiếu kho theo bot/item đạt; không cấp thêm giữa trận. Không có Java exception
trong các lượt nghiệm thu hoặc container restart.

Ca bổ sung chủ động ngắt đúng hai client giữa trận đã trở về phòng chờ. Không quan sát
được log `UNATTENDED` ở ca đó (trận có thể kết thúc tự nhiên trước deadline), nên không
coi nó là chứng minh trigger 30 giây. Đường guard 30 giây, kết nối lại và kết quả hòa
được kiểm tra bằng các ca Java. Guard `NO_PROGRESS` đã được xác minh trên client thật:
ảnh trận cuối Mê cung hiển thị **HÒA**, log server ghi lý do tương ứng.

Bằng chứng: [log, ảnh, snapshot và báo cáo](testing/bot-stalemate-fix-evidence/README.md).
Đã dọn ba stack kiểm thử; chưa khởi động lại server đang sử dụng của người dùng.

Giới hạn: nghiệm thu này dùng Gunner trên ba map, không chứng nhận mọi vũ khí/map boss.
Giữ kiểm thử các trường hợp mới và theo dõi số trận hòa khi mở rộng số bot.

## Chạy bản sửa

Dừng tiến trình cũ rồi build/chạy:

```bash
BOT_TACTICAL=true bash run-manual.sh
# hoặc
BOT_TACTICAL=true docker compose up -d --build server
```
