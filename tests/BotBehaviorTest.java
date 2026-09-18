package mobiarmy.server;

import com.google.gson.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mobiarmy.war.*;

/** No DB or network. Minimal fixtures plus real Bot.update()/RoomWait lifecycle. */
public class BotBehaviorTest {
    static int checks;
    static final Gson GSON = new GsonBuilder().setExclusionStrategies(new ExclusionStrategy() {
        public boolean shouldSkipField(FieldAttributes f) {
            return !f.getDeclaredClass().isPrimitive() && f.getDeclaredClass() != String.class;
        }
        public boolean shouldSkipClass(Class<?> type) { return false; }
    }).create();
    static class TestBot extends Bot {
        int starts, readies, joins;
        TestBot(int id) { super(id, "test"); }
        @Override public void startGame() { starts++; }
        @Override public void ready() { readies++; ready = !ready; }
        @Override public void joinRoomWait(byte room, byte board, String password) { joins++; }
    }
    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
        System.out.println("PASS: " + name);
    }
    static TestBot bot(int id) throws Exception {
        TestBot bot = GSON.fromJson("{\"id\":" + id + ",\"name\":\"bot" + id + "\"}", TestBot.class);
        Field selected = Bot.class.getDeclaredField("selecteds");
        selected.setAccessible(true);
        selected.set(bot, new ArrayList<Player>());
        return bot;
    }
    static RoomWait room(TestBot bot) {
        RoomWait r = new RoomWait((byte)0, (byte)0, (byte)0, new byte[]{0}, 0, 100, (byte)8);
        r.players[0] = bot;
        r.userID = bot.id;
        r.numPlayer = 1;
        bot.roomWait = r;
        return r;
    }
    static void readyNow(TestBot bot) {
        bot.observeRoom(System.currentTimeMillis());
        bot.waitReady = 0;
        bot.update();
    }
    static void invalid(Map<String,String> settings, String key) {
        try {
            BotSettings.fromEnvironment(settings);
            throw new AssertionError("Accepted invalid " + key);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(key), "reject invalid " + key);
        }
    }
    public static void main(String[] args) throws Exception {
        check(Bot.SETTINGS.count() == 0, "test process disables bot generation");
        Bot.generateBot();
        check(Bot.bots.isEmpty(), "BOT_COUNT=0 creates no bots");
        BotSettings defaults = BotSettings.fromEnvironment(Map.of());
        check(defaults.count() == 5000 && defaults.requireHuman() && !defaults.autoJoin(), "compatible defaults and human start policy");
        BotSettings custom = BotSettings.fromEnvironment(Map.of("BOT_COUNT", "100", "BOT_AUTO_JOIN", "true", "BOT_TARGET_MODE", "low_hp"));
        check(custom.count() == 100 && custom.autoJoin() && custom.targetMode() == BotSettings.TargetMode.LOW_HP, "custom configuration");
        invalid(Map.of("BOT_COUNT", "-1"), "BOT_COUNT");
        invalid(Map.of("BOT_COUNT", "10001"), "BOT_COUNT");
        invalid(Map.of("BOT_COUNT", "abc"), "BOT_COUNT");
        invalid(Map.of("BOT_READY_DELAY_MS", "0"), "BOT_READY_DELAY_MS");
        invalid(Map.of("BOT_LEAVE_DELAY_MS", "0"), "BOT_LEAVE_DELAY_MS");
        invalid(Map.of("BOT_TARGET_MODE", "WRONG"), "BOT_TARGET_MODE");
        invalid(Map.of("BOT_AUTO_JOIN", "yes"), "BOT_AUTO_JOIN");
        TestBot bot = bot(-1);
        RoomWait r = room(bot);
        bot.observeRoom(1000);
        check(bot.waitLeave == 1000 + Bot.SETTINGS.leaveDelayMs(), "timeout starts on room entry");
        check(bot.waitReady == 1000 + Bot.SETTINGS.readyDelayMs(), "ready delay starts on room entry");
        bot.observeRoom(2000);
        check(bot.waitLeave == 1000 + Bot.SETTINGS.leaveDelayMs(), "ticks do not postpone timeout");
        r.started = true;
        bot.observeRoom(3000);
        r.started = false;
        bot.observeRoom(4000);
        check(bot.waitLeave == 4000 + Bot.SETTINGS.leaveDelayMs(), "new waiting period after match");
        bot.waitLeave = Long.MAX_VALUE;
        readyNow(bot);
        check(bot.starts == 0, "bot-only owner cannot start");
        User human = GSON.fromJson("{\"id\":42}", User.class);
        r.players[1] = human;
        readyNow(bot);
        check(bot.starts == 0, "disconnected human does not permit start");
        human.session = GSON.fromJson("{\"connected\":true}", Session.class);
        readyNow(bot);
        check(bot.starts == 1, "connected human permits start attempt");
        r.userID = human.id;
        readyNow(bot);
        check(bot.readies == 1 && bot.ready, "guest bot readies itself");
        readyNow(bot);
        check(bot.readies == 1, "ready bot does not toggle off");
        r.started = true;
        bot.observeRoom(System.currentTimeMillis());
        bot.waitLeave = 0;
        bot.update();
        check(bot.roomWait == r, "timeout never leaves active match");
        r.started = false;
        human.session = null;
        bot.observeRoom(System.currentTimeMillis());
        bot.waitLeave = 0;
        bot.update();
        check(bot.roomWait == null && r.players[0] == null, "waiting timeout leaves actual room");
        TestBot removed = bot(-2);
        RoomWait removedRoom = room(removed);
        Bot.add(removed);
        try { Bot.add(removed); throw new AssertionError("duplicate bot"); }
        catch (IllegalArgumentException expected) { check(Bot.bots.size() == 1, "duplicate registration rejected"); }
        removed.lock = true;
        removed.remove = true;
        Bot.updateBot();
        check(Bot.bots.isEmpty() && Bot.findById(-2) == null && !Bot.bot_name.containsKey(removed.name), "locked removal clears all registries");
        check(removed.roomWait == null && removedRoom.numPlayer == 0, "removal leaves actual room");
        removed.remove();
        check(Bot.bots.isEmpty(), "repeated removal is safe");
        TestBot idle = bot(-3);
        idle.invited = new Object[]{(byte)0, (byte)0, ""};
        idle.update();
        check(idle.joins == 1 && idle.invited == null, "idle bot accepts invitation once");
        TestBot busy = bot(-4);
        room(busy);
        busy.observeRoom(System.currentTimeMillis());
        busy.invited = new Object[]{(byte)1, (byte)1, ""};
        busy.update();
        check(busy.joins == 0 && busy.invited == null, "busy bot discards conflicting invite");
        Player me = GSON.fromJson("{\"team\":1,\"hp\":100}", Player.class);
        me.mapData = GSON.fromJson("{}", MapData.class);
        Player enemy = GSON.fromJson("{\"team\":2,\"hp\":50}", Player.class);
        Player ally = GSON.fromJson("{\"team\":1,\"hp\":10}", Player.class);
        check(Bot.isTarget(me, enemy) && !Bot.isTarget(me, ally) && !Bot.isTarget(me, me), "PvP targets exclude allies and self");
        enemy.isDie = true;
        check(!Bot.isTarget(me, enemy), "dead target rejected");
        enemy.isDie = false;
        enemy.hp = 0;
        check(!Bot.isTarget(me, enemy), "zero HP target rejected");
        enemy.hp = 50;
        me.mapData.isFightBoss = true;
        check(!Bot.isTarget(me, enemy), "boss mode ignores non-boss enemies");
        enemy.isBoss = true;
        check(Bot.isTarget(me, enemy), "boss target accepted");
        ArrayList<Player> targets = new ArrayList<>(List.of(enemy, ally));
        check(Bot.takeTarget(targets, BotSettings.TargetMode.LOW_HP) == ally && targets.size() == 1, "LOW_HP takes lowest absolute HP");
        check(Bot.takeTarget(targets, BotSettings.TargetMode.RANDOM) == enemy && targets.isEmpty(), "selected targets consumed without repetition");
        TestBot visitor = bot(-5);
        RoomWait publicRoom = new RoomWait((byte)1, (byte)0, (byte)2, new byte[]{0}, 0, 100, (byte)8);
        RoomInfo info = new RoomInfo();
        info.roomWaits = new ArrayList<>(List.of(publicRoom));
        RoomInfo.entrys = new RoomInfo[]{info};
        var joinRandom = Bot.class.getDeclaredMethod("joinRandom");
        joinRandom.setAccessible(true);
        joinRandom.invoke(visitor);
        check(visitor.joins == 0, "auto-join does not populate empty rooms under human policy");
        human.session = GSON.fromJson("{\"connected\":true}", Session.class);
        publicRoom.players[0] = human;
        publicRoom.numPlayer = 1;
        joinRandom.invoke(visitor);
        check(visitor.joins == 1, "auto-join selects available human room");
        publicRoom.pass = "private";
        joinRandom.invoke(visitor);
        check(visitor.joins == 1, "auto-join excludes password-protected room");
        TestBot fighter = bot(-6);
        RoomWait fightRoom = room(fighter);
        fightRoom.started = true;
        fightRoom.mapData = GSON.fromJson("{\"isTurnPlayer\":true,\"indexTurnPlayer\":0,\"nTurn\":1}", MapData.class);
        fighter.index = 0;
        me.mapData = fightRoom.mapData;
        me.isShoot = true;
        fightRoom.mapData.players = new Player[]{me, enemy};
        fighter.update();
        Field selectedField = Bot.class.getDeclaredField("selecteds");
        selectedField.setAccessible(true);
        @SuppressWarnings("unchecked") ArrayList<Player> selected = (ArrayList<Player>) selectedField.get(fighter);
        selected.add(enemy);
        fightRoom.mapData.nTurn++;
        fighter.update();
        check(selected.isEmpty(), "new turn clears stale target queue");
        selected.add(enemy);
        fightRoom.mapData.players[1] = null;
        fighter.update();
        check(selected.isEmpty(), "departed target removed from queue");
        System.out.println(checks + " bot checks passed");
    }
}
