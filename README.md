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
21
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

Trạng thái đúng là service `db` hiển thị `healthy`, service `server` hiển thị `Up`, và log server có dòng `Start server port:8122`.

Dừng stack nhưng giữ dữ liệu:

```bash
docker compose down
```

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
