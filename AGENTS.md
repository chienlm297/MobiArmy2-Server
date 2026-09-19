# Project instructions

## Codebase Knowledge Graph (codebase-memory-mcp)

Prefer MCP graph tools for code discovery, in this order:
`search_graph`, `trace_path`, `get_code_snippet`, `query_graph`, `get_architecture`.
Fall back to `rg`/file search for string literals, configuration and non-code files,
or when graph tools are unavailable or return insufficient results.

## Bắt buộc cập nhật tài liệu bot

`docs/bot-summary.md` là tài liệu tổng hợp chính về trạng thái hiện tại của bot.

- Mọi thay đổi liên quan đến bot phải cập nhật file này trong cùng đợt thay đổi,
  trước khi báo hoàn tất. Áp dụng cho hành vi AI, vòng đời, di chuyển, chọn mục tiêu,
  đường đạn, item, quản trị bot, cấu hình, DB, xử lý bế tắc, tích hợp client và kiểm thử.
- Cập nhật nội dung hiện hành bị ảnh hưởng, không chỉ thêm một dòng lịch sử. Nếu thay
  đổi thay thế hành vi cũ, sửa mô tả cũ để tài liệu không tự mâu thuẫn.
- Thêm mục lịch sử ghi ngày, vấn đề/thay đổi, kết quả kiểm thử thực tế và giới hạn còn
  lại. Dẫn liên kết tới tài liệu hoặc bằng chứng chi tiết khi có.
- Khi thay đổi cấu hình hoặc cách chạy, ghi rõ mặc định, phạm vi áp dụng, yêu cầu
  build/restart, tính lưu bền và migration DB nếu có.
- Không ghi kiểm thử là đạt khi chưa chạy. Phân biệt mô phỏng, client thật, benchmark
  và các ca chưa xác minh; không cộng trùng số kiểm tra hoặc số trận của các đợt.
- Giữ các báo cáo nghiệm thu cũ làm bằng chứng lịch sử; liên kết bản mới thay vì xóa
  kết quả thất bại trước đó. Các tài liệu đợt A/B/C bổ sung chi tiết, không thay thế
  nghĩa vụ cập nhật `docs/bot-summary.md`.

Quy định này được thêm theo yêu cầu của người dùng để các lần làm việc sau tiếp tục
duy trì bản tổng hợp bot.
