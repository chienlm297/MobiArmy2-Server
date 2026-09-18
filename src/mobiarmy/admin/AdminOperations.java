package mobiarmy.admin;

import java.sql.*;
import java.util.*;
import mobiarmy.server.*;
import mobiarmy.war.*;

public final class AdminOperations {
    public record Member(int id, String name, int team, Integer hp, Integer hpMax) {}
    public record Board(int room, int board, String name, String map, String state, int limit,
                        int turn, String currentPlayer, long duration, List<Member> members) {}
    public record Snapshot(long capturedAt, List<Board> boards) {}
    private static volatile Snapshot snapshot = new Snapshot(0, List.of());

    // Called only by the game loop. HTTP handlers read immutable DTOs, never live room arrays.
    public static void captureRooms() {
        long now = System.currentTimeMillis();
        if (now - snapshot.capturedAt() < 1000 || RoomInfo.entrys == null) return;
        List<Board> boards = new ArrayList<>();
        for (RoomInfo room : RoomInfo.entrys) {
            for (RoomWait wait : room.roomWaits) {
                List<Member> members = new ArrayList<>();
                MapData game = wait.mapData;
                for (User user : wait.players) {
                    if (user == null) continue;
                    Player fighter = null;
                    if (wait.started && game != null) {
                        for (Player p : game.players) if (p != null && p.userID == user.id) { fighter = p; break; }
                    }
                    members.add(new Member(user.id, user.name, fighter == null ? user.team : fighter.team,
                            fighter == null ? null : fighter.hp, fighter == null ? null : fighter.hpMax));
                }
                boolean playing = wait.started && game != null && game.isWar;
                int turnIndex = playing ? game.getTurn() : -1;
                String current = turnIndex >= 0 && turnIndex < game.players.length && game.players[turnIndex] != null
                        ? game.players[turnIndex].name : "—";
                mobiarmy.server.Map map = playing ? game.map : mobiarmy.server.Map.get(wait.mapID);
                boards.add(new Board(room.id & 255, wait.boardID & 255,
                        room.name + " · " + ((wait.name == null || wait.name.isBlank()) ? "Bàn " + (wait.boardID & 255) : wait.name),
                        map == null ? "#" + wait.mapID : map.name,
                        playing ? "PLAYING" : members.isEmpty() ? "EMPTY" : "WAITING",
                        wait.playerLimit, playing ? game.nTurn : 0, current,
                        playing && game.startedAt > 0 ? now - game.startedAt : 0, List.copyOf(members)));
            }
        }
        snapshot = new Snapshot(now, List.copyOf(boards));
    }
    static Snapshot rooms() { return snapshot; }

    static int broadcast(String message, String reason, String actor) throws SQLException {
        if (message == null || message.isBlank() || message.length() > 300
                || message.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Thông báo cần 1–300 ký tự, không chứa ký tự điều khiển");
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("Nhập lý do, tối đa 500 ký tự");
        List<Session> targets = new ArrayList<>();
        for (User u : SessionManager.generateUsers()) {
            Session s = u.session;
            if (s != null && s.connected) targets.add(s);
        }
        try (Connection c = Server.dbManager.getConnection()) {
            AdminService.insertAudit(c, actor, "BROADCAST", null,
                    "Gửi tới " + targets.size() + " phiên: " + message.trim(), reason, null, null);
        }
        for (Session s : targets) s.sessionHandler.messageWorld("[Thông báo] " + message.trim());
        return targets.size();
    }

    record Catalog(String kind, int id, String name, String detail) {}
    static List<Catalog> catalog() throws SQLException {
        List<Catalog> rows = new ArrayList<>();
        try (Connection c = Server.dbManager.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT 'ITEM' AS kind,id,name,'' AS detail FROM item UNION ALL SELECT 'SPECIAL',id,name,detail FROM linhtinh ORDER BY kind,id")) {
            while (r.next()) rows.add(new Catalog(r.getString("kind"), r.getInt("id"), r.getString("name"), r.getString("detail")));
        }
        return rows;
    }
}
