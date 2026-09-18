# MobiArmy 2 Server

Server MobiArmy 2 viết bằng Java, dùng MySQL để lưu dữ liệu và mặc định lắng nghe tại cổng TCP `8122`.

## Yêu cầu

- Linux hoặc môi trường shell tương đương.
- JDK 21. Dự án được biên dịch với target Java 20.
- Docker nếu muốn chạy MySQL bằng container hoặc chạy toàn bộ bằng Compose.
- Luôn chạy lệnh tại thư mục gốc của repository. Server đọc `res/` và `cache/` bằng đường dẫn tương đối.

Kiểm tra Java:

```bash
java -version
javac -version
```

Hai lệnh phải trả về phiên bản 21 hoặc mới hơn.

## Chạy thủ công từ đầu

Phần này chạy Java trực tiếp trên máy và chỉ dùng Docker cho MySQL. Không chạy đồng thời server thủ công và service `server` của Docker Compose vì cả hai cùng sử dụng cổng `8122`.

### Cách nhanh: dùng một script

Script sau tự build JAR, tạo hoặc khởi động MySQL, đợi import dữ liệu xong, rồi chạy game server và web admin:

```bash
./run-manual.sh
```

Chọn tài khoản OWNER cho lần khởi tạo database đầu tiên:

```bash
ADMIN_USERNAME=myadmin \
ADMIN_PASSWORD='mat-khau-manh' \
./run-manual.sh
```

Các chế độ khác:

```bash
./run-manual.sh build
./run-manual.sh run-no-build
./run-manual.sh --help
```

Khi chạy thành công:

- Game server mở tại cổng `8122`.
- Web admin mở tại [http://127.0.0.1:8080](http://127.0.0.1:8080).
- Nhấn `Ctrl+C` tại terminal chạy script để dừng game server và web admin.
- MySQL container vẫn chạy để giữ database; dừng bằng `docker stop mobiarmy-mysql` nếu cần.

Các bước bên dưới là quy trình tương đương nếu muốn chạy từng lệnh riêng.

### Bước 1: dừng stack Compose nếu đang chạy

Lệnh này giữ nguyên dữ liệu Compose:

```bash
docker compose down
```

Nếu chưa từng chạy Compose, có thể bỏ qua lỗi `no configuration file` hoặc `no resource found`.

### Bước 2: khởi tạo MySQL và import dữ liệu

Chỉ chạy lệnh dưới đây một lần để tạo container `mobiarmy-mysql`. File `army.sql` sẽ được import tự động trong lần khởi tạo đầu tiên:

```bash
docker run -d \
  --name mobiarmy-mysql \
  --restart unless-stopped \
  -e MYSQL_ALLOW_EMPTY_PASSWORD=yes \
  -e MYSQL_DATABASE=army \
  -p 127.0.0.1:3306:3306 \
  -v "$PWD/army.sql:/docker-entrypoint-initdb.d/001-army.sql:ro" \
  mysql:8 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci \
  --restrict-fk-on-non-standard-key=OFF
```

Tùy chọn `--restrict-fk-on-non-standard-key=OFF` là bắt buộc với dump MariaDB của dự án khi import vào MySQL 8.4.

Nếu container đã tồn tại, không chạy lại `docker run`; chỉ cần khởi động nó:

```bash
docker start mobiarmy-mysql
```

Đợi MySQL import xong rồi kiểm tra:

```bash
docker exec mobiarmy-mysql mysqladmin ping -h 127.0.0.1 -uroot
docker exec mobiarmy-mysql mysql -uroot -Darmy -N \
  -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='army';"
```

Kết quả đúng:

```text
mysqld is alive
26
```

Nếu MySQL chưa sẵn sàng, xem quá trình import bằng:

```bash
docker logs -f mobiarmy-mysql
```

Đợi log có dòng `ready for connections`, sau đó nhấn `Ctrl+C` để thoát phần theo dõi log. Container vẫn tiếp tục chạy.

### Bước 3: build lại JAR

Dùng script của dự án để tránh tạo JAR thiếu classpath:

```bash
bash build.sh
```

Script sẽ xóa class cũ, biên dịch toàn bộ mã nguồn, copy các thư viện sang `dist/lib/` và đóng gói `dist/MobiArmy.jar` bằng `manifest.mf`.

Thông báo sau không phải lỗi và có thể bỏ qua:

```text
Note: Some input files use unchecked or unsafe operations.
```

Kiểm tra JAR đã chứa khai báo thư viện:

```bash
unzip -p dist/MobiArmy.jar META-INF/MANIFEST.MF
```

Kết quả phải có cả `Main-Class: mobiarmy.MobiArmy` và `Class-Path:`.

### Bước 4: chạy server có giao diện

```bash
java -jar dist/MobiArmy.jar
```

Khi cửa sổ **Server Manager** xuất hiện, bấm **Start Server**. Terminal phải xuất hiện:

```text
HikariPool-1 - Start completed.
Start server port:8122
```

### Bước 5: hoặc chạy headless

Trên VPS hoặc máy không có giao diện, server tự khởi động mà không cần bấm nút:

```bash
MOBIARMY_HEADLESS=true java -jar dist/MobiArmy.jar
```

Giữ terminal này mở trong khi server chạy. Dừng server bằng `Ctrl+C`.

### Bước 6: kiểm tra server

Mở terminal khác và chạy:

```bash
nc -vz 127.0.0.1 8122
```

Kết quả đúng:

```text
Connection to 127.0.0.1 8122 port [tcp/*] succeeded!
```

## Cấu hình chạy thủ công

Server nhận các biến môi trường sau:

| Biến | Giá trị mặc định | Ý nghĩa |
| --- | --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/army` | JDBC URL của database |
| `DB_USER` | `root` | Tài khoản database |
| `DB_PASSWORD` | rỗng | Mật khẩu database |
| `MOBIARMY_PORT` | `8122` | Cổng TCP của game server |
| `MOBIARMY_HEADLESS` | `false` | Chạy không cần giao diện Swing |

Ví dụ chạy headless với database có mật khẩu:

```bash
MOBIARMY_HEADLESS=true \
MOBIARMY_PORT=8122 \
DB_URL='jdbc:mysql://127.0.0.1:3306/army?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
DB_USER=root \
DB_PASSWORD='mat-khau' \
java -jar dist/MobiArmy.jar
```

## Dùng MySQL hoặc MariaDB cài trực tiếp

Tạo database và import dữ liệu:

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS army CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -uroot -p army < army.sql
```

Nếu tài khoản `root` không có mật khẩu, bỏ `-p`. Với MySQL 8.4, dịch vụ MySQL phải được khởi động với `--restrict-fk-on-non-standard-key=OFF` trước khi import.

## Chạy toàn bộ bằng Docker Compose

Đây là cách ngắn nhất. Compose tự build Java, tạo MySQL, import `army.sql`, chờ database sẵn sàng và chạy server headless:

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f server
```

Compose bật web admin tại [http://127.0.0.1:8080](http://127.0.0.1:8080). Tài khoản development mặc định:

```text
Username: admin
Password: admin123
```

Phải đổi mật khẩu khi chạy ngoài máy development:

```bash
ADMIN_USERNAME=myadmin ADMIN_PASSWORD='mat-khau-manh' docker compose up -d --build
```

Web admin hiện hỗ trợ:

- Dashboard uptime, TCP session, user online và user đã load.
- Tìm theo ID, username hoặc tên nhân vật.
- Tạo tài khoản người chơi mới với mật khẩu BCrypt, số dư ban đầu và dữ liệu game mặc định.
- Xem hồ sơ chi tiết theo tab: tổng quan, nhân vật, trang bị, vật phẩm, nhiệm vụ, bạn bè và lịch sử.
- Cộng/trừ xu và lượng, đồng bộ với user đang online.
- Reset mật khẩu bằng BCrypt; đổi tên nhân vật với kiểm tra trùng tên.
- Khóa/mở khóa đăng nhập nhanh; xóa mềm và khôi phục tài khoản.
- Cộng/trừ cup; chỉnh EXP, điểm và bộ chỉ số của từng nhân vật.
- Thêm/trừ item, đồ đặc biệt; cấp hoặc xóa trang bị.
- Kick user.
- Ban vĩnh viễn hoặc theo số phút và unban.
- Wallet transaction và audit log có lý do bắt buộc, request ID, dữ liệu trước/sau.

Các chức năng quản trị bổ sung:

- **Bộ lọc người chơi:** online, offline, bị ban, khóa đăng nhập, xóa mềm; mỗi trang 25 người. Có thể kết hợp tìm tên/ID với bộ lọc.
- **Chỉ số nhân vật:** nhập riêng HP, sức mạnh, phòng thủ, may mắn, đồng đội trong tab Nhân vật; không cần nhập mảng JSON. Chỉ số được áp dụng cho trận tiếp theo.
- **Kho đồ:** tìm vật phẩm và trang bị theo tên/ID, hỗ trợ tìm tiếng Việt không dấu. Item có ảnh xem trước nếu có ảnh trong `res/icon/item`; đồ đặc biệt hiển thị mô tả.
- **Phòng và trận** (`/admin/rooms`): xem map, thành viên, đội, HP, lượt hiện tại và thời lượng. Game loop xuất snapshot khoảng mỗi giây; bấm Làm mới để lấy dữ liệu mới. Trang này chỉ xem.
- **Thông báo** (`/admin/broadcast`): gửi một dòng tối đa 300 ký tự tới các phiên người chơi đang online; bắt buộc lý do và ghi audit. Số phiên báo thành công là số phiên được đưa vào hàng đợi gửi, không phải xác nhận từ client; người offline không nhận lại.
- **Tài khoản admin** (`/admin/accounts`): OWNER tạo tài khoản, đổi quyền, bật/tắt và reset mật khẩu. Mật khẩu được băm BCrypt; tối thiểu 8 ký tự, tối đa 72 byte UTF-8. Mọi lần cập nhật tài khoản thu hồi các phiên đăng nhập cũ. Không cho vô hiệu hóa hoặc hạ quyền OWNER cuối cùng.

| Quyền | Phạm vi |
| --- | --- |
| OWNER | Tất cả chức năng và quản lý tài khoản admin |
| ADMIN | Quản lý người chơi, bot, chỉ số, kho đồ, số dư và gửi thông báo |
| MODERATOR | Xem, kick, ban/unban, khóa/mở khóa; không khôi phục người chơi đã xóa mềm |
| VIEWER | Chỉ xem, không thay đổi dữ liệu |

**Nâng cấp database đang dùng:** dừng tiến trình Java cũ, build lại rồi chạy bằng
`bash run-manual.sh` như trước. Server tự tạo bảng `admin_account` nếu chưa có;
không cần import lại `army.sql` vào database đang có dữ liệu.
`ADMIN_USERNAME` và `ADMIN_PASSWORD` chỉ tạo OWNER khi bảng admin còn trống.
Sau lần đầu, đổi mật khẩu/quyền trong trang Tài khoản admin; thay biến môi trường
không ghi đè tài khoản đã lưu. Vẫn cấu hình `ADMIN_PASSWORD` khi bật web admin.

Kiểm thử trên stack Docker và database tạm riêng:

```bash
bash tests/admin-priority.sh
# Nếu cổng kiểm thử đang bận:
TEST_ADMIN_PORT=18081 TEST_GAME_PORT=18123 bash tests/admin-priority.sh
```

Bộ test hiện gồm 156 kiểm tra HTTP/SQL, 11 kiểm tra snapshot phòng/trận và 7 kiểm tra
bootstrap/broadcast. Kiểm thử Chrome thêm tìm kiếm catalog, ảnh preview và bố cục
1440/390/320 px. Phòng/trận và gói broadcast có fixture/session mô phỏng;
vẫn cần nghiệm thu hiển thị trong trận bằng hai client game thật.

Script cần JDK 21, Docker Compose hỗ trợ `!override`, Python 3 và Docker daemon đang chạy.
Thêm `TEST_BROWSER=1` để kiểm tra trên Chrome/Chromium headless nếu đã cài trình duyệt.
Nó tự build, kiểm tra HTTP/CSRF/phân quyền/dữ liệu, rồi xóa **chỉ volume của stack kiểm thử**.

Database tự tạo thêm các bảng `user_ban`, `wallet_transaction`, `admin_audit_log`,
`user_account_state` và `admin_account` khi admin khởi động. Khi nâng cấp từ bản cũ, server tự thêm các cột
`reason`, `before_data`, `after_data` và `request_id` còn thiếu trong `admin_audit_log`.
File `army.sql` cũng đã chứa đầy đủ schema này cho database khởi tạo mới.

Mọi form làm thay đổi dữ liệu đều bắt buộc nhập lý do. Xóa tài khoản trên web admin là
xóa mềm: user bị chặn đăng nhập nhưng các bảng dữ liệu game vẫn được giữ để có thể khôi phục.

Trạng thái đúng là service `db` hiển thị `healthy`, service `server` hiển thị `Up`, và log server có dòng `Start server port:8122`.

Dừng stack nhưng giữ dữ liệu:

```bash
docker compose down
```

### Bật web admin khi chạy Java thủ công

```bash
ADMIN_ENABLED=true \
ADMIN_HOST=127.0.0.1 \
ADMIN_PORT=8080 \
ADMIN_USERNAME=admin \
ADMIN_PASSWORD='mat-khau-manh' \
MOBIARMY_HEADLESS=true \
java -jar dist/MobiArmy.jar
```

Không đặt `ADMIN_HOST=0.0.0.0` trên server public nếu chưa có reverse proxy HTTPS và firewall.

## Các lỗi thường gặp

### `NoClassDefFoundError: com/zaxxer/hikari/HikariConfig`

JAR đã bị build bằng lệnh `jar --main-class` nhưng không dùng `manifest.mf`, nên Java không biết các thư viện trong `dist/lib/`. Không tự chạy lại lệnh `jar`; build lại bằng script:

```bash
bash build.sh
```

Sau đó chạy bằng `java -jar dist/MobiArmy.jar`.

### `Communications link failure` hoặc `Connection refused` tại cổng 3306

MySQL chưa chạy hoặc chưa import xong. Kiểm tra:

```bash
docker start mobiarmy-mysql
docker exec mobiarmy-mysql mysqladmin ping -h 127.0.0.1 -uroot
```

### `NullPointerException` tại `SessionHandler.updateRuong` / `DataOutputStream.writeUTF`

Dữ liệu cũ có thể chứa `equip.name = NULL`. Bản server hiện tại tự dùng tên dự phòng
`Trang bị #<glassID>:<id>`, nhưng phải build và khởi động lại Java để nạp mã mới:

```bash
bash build.sh
MOBIARMY_HEADLESS=true java -jar dist/MobiArmy.jar
```

Với database Compose đã tồn tại, chuẩn hóa dữ liệu một lần bằng:

```bash
docker compose exec db mysql -uroot -Darmy -e "
UPDATE equip
SET name = CONCAT('Trang bị #', glassID, ':', id)
WHERE name IS NULL OR TRIM(name) = '';
ALTER TABLE equip MODIFY name varchar(255) NOT NULL;
"
```

`army.sql` đã tự thực hiện bước chuẩn hóa này cho database được tạo mới.

### `BindException: Address already in use` tại cổng 8122

Đã có một server khác hoặc service Compose giữ cổng. Kiểm tra và dừng Compose nếu đang chạy:

```bash
docker compose ps
docker compose down
ss -ltnp | grep 8122
```

## Kết nối client

Client kết nối tới IP của máy chạy server, port mặc định `8122`.

[MobiArmy2 Client](https://github.com/vantu03/MobiArmy2-Client)

## Hình ảnh

### Giao diện quản lý server

![Giao diện quản lý server](src/anh1.png)

### Cấu hình kết nối MySQL

![Cấu hình MySQL](src/anh2.png)

### Xử lý bot trong game

![Xử lý bot](src/anh3.png)

### Server đang hoạt động

![Server đang hoạt động](src/anh4.png)

### Cấu hình bot

Bot đọc cấu hình lúc khởi động. Không cần ALTER hoặc import lại database.

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `BOT_COUNT` | `5000` | Số bot được tạo; 0–10000, `0` không tạo bot |
| `BOT_AUTO_JOIN` | `false` | Tự thử vào phòng sơ cấp công khai, còn chỗ và đủ tiền cược |
| `BOT_REQUIRE_HUMAN` | `true` | Bot chủ phòng chỉ thử start khi có người thật còn kết nối; tự tìm phòng cũng áp dụng điều kiện này |
| `BOT_READY_DELAY_MS` | `2000` | Thời gian trước lần sẵn sàng đầu tiên và giữa các lần kiểm tra; 100–60000 ms |
| `BOT_LEAVE_DELAY_MS` | `120000` | Thời gian chờ tối đa sau khi vào phòng hoặc kết thúc trận; 1000–3600000 ms; không rời giữa trận |
| `BOT_TARGET_MODE` | `RANDOM` | `RANDOM`: chọn ngẫu nhiên; `LOW_HP`: ưu tiên đối thủ có HP tuyệt đối thấp nhất |

Ví dụ chạy 100 bot và ưu tiên đối thủ ít HP:

```bash
BOT_COUNT=100 BOT_TARGET_MODE=LOW_HP bash run-manual.sh
```

Với Docker Compose:

```bash
BOT_COUNT=100 BOT_TARGET_MODE=LOW_HP docker compose up -d --build
```

Muốn bot tự tìm phòng, thêm `BOT_AUTO_JOIN=true`. Chu kỳ tìm phòng ngẫu nhiên 3 giây
đến dưới 10 phút, nên không phải bật là bot vào ngay. Muốn cho phép bot chủ phòng
mở trận chỉ có bot, đặt `BOT_REQUIRE_HUMAN=false`; vẫn phải thỏa điều kiện của phòng.
Cấu hình sai sẽ báo lỗi khi khởi tạo bot. Những biến môi trường này chưa chỉnh trực tiếp trên web admin; trang Bot cho phép quản lý từng bot đang chạy.

Phân tích chi tiết và lộ trình: [docs/bot-improvements.md](docs/bot-improvements.md).
Kiểm tra logic bot: `bash tests/bot-behavior.sh` (40 kiểm tra hành vi và 15 kiểm tra quản trị bot, cần JDK 21).

### Quản lý bot trên web

Mở **Quản lý bot** ở thanh bên hoặc `/admin/bots`.

- Xem số bot nhàn rỗi, chờ phòng và đang chơi; tìm theo tên/ID âm, lọc trạng thái, phân trang 25 bot.
- Xem nhân vật, cấp độ, phòng/bàn, trạng thái xử lý và chế độ chọn mục tiêu.
- OWNER/ADMIN tạo từng bot bằng tên, nhân vật ID 0–9 và EXP; trang bị được chọn theo cấp như bot khởi tạo tự động. Tổng tối đa 10.000 bot.
- Chọn `Đổi cách chọn mục tiêu` để áp dụng RANDOM, LOW_HP hoặc quay về cấu hình mặc định. Có hiệu lực ở lần chọn mục tiêu tiếp theo, không tính lại đường đạn đã bắt đầu mô phỏng.
- Chọn `Rời phòng chờ` hoặc `Xóa bot`. Server từ chối nếu bot đã vào trận hoặc đang khóa xử lý, kể cả trạng thái thay đổi sau khi gửi lệnh. Bot rời phòng vẫn có thể nhận lời mời/tự tìm phòng lại.
- MODERATOR/VIEWER chỉ xem. Thao tác yêu cầu CSRF và lý do, kiểm tra lại quyền admin khi xử lý.

Sau khi gửi, xem **Lệnh gần đây** và bấm **Làm mới**. `QUEUED` là chờ, `RUNNING`
là đang xử lý, `DONE` là hoàn tất, `FAILED` là bị từ chối hoặc lỗi. Không xem thông báo
“Đã nhận lệnh” là đã hoàn thành. Nếu `DONE_AUDIT_ERROR`, thay đổi đã thực hiện nhưng ghi
audit kết quả lỗi: kiểm tra log, không gửi lại thao tác.

Lệnh được xử lý tuần tự trên game loop (tối đa một lệnh mỗi tick), hàng đợi tối đa 32.
Lệnh còn chờ quá 10 giây bị từ chối khi được lấy ra. Ghi audit `BOT_REQUEST` trước khi
sửa dữ liệu RAM và `BOT_RESULT` sau thành công; lỗi ghi audit yêu cầu thì không thực hiện.
Tra mã lệnh trong lịch sử quản trị. 100 kết quả lệnh gần nhất nằm trong bộ nhớ.

**Không cần sửa schema DB.** Bot và chế độ riêng không lưu bền: restart tạo lại bot theo
BOT_COUNT; bot đã xóa không làm giảm BOT_COUNT. Audit vẫn nằm trong database. Cấu hình
tổng số lượng, tự tìm phòng và thời gian chờ vẫn dùng biến môi trường.
