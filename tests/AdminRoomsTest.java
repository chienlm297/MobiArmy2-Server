package mobiarmy.admin;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import mobiarmy.server.User;
import mobiarmy.war.*;

/** Minimal room fixtures; no live server, database or game sessions are modified. */
public class AdminRoomsTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        System.out.println("PASS: " + message);
    }
    public static void main(String[] args) throws Exception {
        Gson gson = new com.google.gson.GsonBuilder().setExclusionStrategies(
            new com.google.gson.ExclusionStrategy() {
                public boolean shouldSkipField(com.google.gson.FieldAttributes field) {
                    return !field.getDeclaredClass().isPrimitive() && field.getDeclaredClass() != String.class;
                }
                public boolean shouldSkipClass(Class<?> type) { return false; }
            }).create();
        mobiarmy.server.Map map = new mobiarmy.server.Map();
        map.id = 0;
        map.name = "Test map";
        mobiarmy.server.Map.entrys = new mobiarmy.server.Map[]{map};
        RoomInfo room = new RoomInfo();
        room.id = 2;
        room.name = "Test room";
        RoomWait empty = new RoomWait((byte)2, (byte)0, (byte)0, new byte[]{0}, 0, 100, (byte)2);
        RoomWait waiting = new RoomWait((byte)2, (byte)0, (byte)1, new byte[]{0}, 0, 100, (byte)2);
        RoomWait playing = new RoomWait((byte)2, (byte)0, (byte)2, new byte[]{0}, 0, 100, (byte)2);
        User user = gson.fromJson("{\"id\":42,\"name\":\"Player <script>\",\"team\":1}", User.class);
        waiting.players[0] = user;
        playing.players[0] = user;
        playing.started = true;
        MapData game = gson.fromJson("{\"isWar\":true,\"nTurn\":7,\"isTurnPlayer\":true,\"indexTurnPlayer\":0}", MapData.class);
        game.map = map;
        game.startedAt = System.currentTimeMillis() - 65000;
        Player fighter = gson.fromJson("{\"userID\":42,\"name\":\"Fighter\",\"hp\":350,\"hpMax\":1000}", Player.class);
        fighter.team = 3;
        game.players = new Player[]{fighter};
        playing.mapData = game;
        room.roomWaits = new ArrayList<>(List.of(empty, waiting, playing));
        RoomInfo.entrys = new RoomInfo[]{room};
        AdminOperations.captureRooms();
        var snapshot = AdminOperations.rooms();
        check(snapshot.boards().size() == 3, "all boards captured");
        check(snapshot.boards().get(0).state().equals("EMPTY"), "empty state");
        check(snapshot.boards().get(1).state().equals("WAITING"), "waiting state");
        var board = snapshot.boards().get(2);
        check(board.state().equals("PLAYING") && board.turn() == 7 && board.currentPlayer().equals("Fighter"), "match state and turn");
        check(board.duration() >= 65000 && board.duration() < 70000, "match duration");
        check(board.members().get(0).hp() == 350 && board.members().get(0).hpMax() == 1000, "member HP");
        check(board.members().get(0).team() == 3 && board.map().equals("Test map"), "team and map");
        fighter.hp = 1;
        user.name = "changed";
        check(board.members().get(0).hp() == 350 && board.members().get(0).name().equals("Player <script>"), "snapshot isolated from live mutations");
        try { snapshot.boards().clear(); throw new AssertionError("mutable boards"); }
        catch (UnsupportedOperationException expected) { System.out.println("PASS: immutable boards"); }
        AdminView.setRole(AdminAccounts.Role.VIEWER);
        String html = AdminView.rooms(snapshot, "PLAYING", "csrf", "viewer");
        check(html.contains("Player &lt;script&gt;") && !html.contains("Player <script>"), "room names escaped");
        check(!html.contains("Bàn 1") && html.contains("Bàn 2"), "playing filter");
        AdminView.clearRole();
        System.out.println("11 room checks passed");
    }
}
