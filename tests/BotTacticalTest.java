package mobiarmy.server;

import mobiarmy.war.*;
import mobiarmy.war.Boss.bullet.BulletTrajectory;

public class BotTacticalTest {
    static int checks;
    static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++; System.out.println("PASS: " + name);
    }
    static class Terrain extends MapData {
        boolean pit, wall;
        int broadcasts;
        Terrain(int unused) throws Exception { super(null, (byte)0); }
        @Override public boolean isCollisionMap(int x, int y) {
            return wall && x >= 55 && x <= 60 && y >= 70 || y >= 100 && !(pit && x >= 55);
        }
        @Override public void changeLocation(int index, short x, short y) { broadcasts++; }
        @Override public boolean isIntoTornado(int x, int y) { return false; }
        @Override public void makeHole(int x, int y, int type) { throw new AssertionError("search mutated terrain"); }
        @Override public boolean isCollisionPlayer(int x, int y) { return false; }
    }
    static class CombatBot extends BotBehaviorTest.TestBot {
        int shots, lastBullet=-1;
        CombatBot(int id) { super(id); }
        @Override public Glass glass() { Glass g = new Glass(); g.angle = 10; return g; }
        @Override public void shoot(byte bullet, short x, short y, short angle, byte force, byte force2, byte count) { shots++; lastBullet=bullet; }
    }
    static Terrain terrain() {
        Terrain t = BotBehaviorTest.GSON.fromJson("{\"width\":500,\"height\":200,\"isTurnPlayer\":true,\"indexTurnPlayer\":0}", Terrain.class);
        t.players = new Player[2]; return t;
    }
    static Player player(Terrain map, int x, int team) {
        Player p = BotBehaviorTest.GSON.fromJson("{\"x\":"+x+",\"y\":100,\"hp\":100,\"hpMax\":100,\"width\":24,\"height\":24,\"theluc\":40,\"team\":"+team+"}", Player.class);
        p.mapData = map; return p;
    }
    public static void main(String[] args) throws Exception {
        Terrain map = terrain(); Player me = player(map,50,0), enemy = player(map,350,1);
        map.players = new Player[]{me,enemy};
        MovementStep.Result step = MovementStep.next(map,50,100,true,1,false);
        me.updateXY(51,100);
        check(me.x == step.x() && me.y == step.y() && me.buocdi == 1, "prediction equals actual walk");
        me.theluc = 1; me.updateXY(60,100);
        check(me.x == 51 && me.buocdi == 1, "no extra step at exhausted stamina");
        me.theluc = 40; me.countFreeze = 1; me.updateXY(60,100);
        check(me.x == 51, "freeze prevents actual walking");
        check(BotMovementPlanner.choose(me,enemy).steps() == 0, "planner respects freeze");
        me.countFreeze = 0; me.isRunSpeed = true; me.updateXY(53,100);
        check(me.x == 53 && me.buocdi == 2, "double speed costs one stamina per step");
        map.wall = true; me.updateXY(60,100);
        check(me.x == 53, "wall blocks double speed");
        map.wall = false; map.pit = true;
        BotMovementPlanner.Destination dest = BotMovementPlanner.choose(me,enemy);
        check(dest.x() < 55 && dest.y() <= map.height, "planner refuses pit");
        map.pit = false; me.isRunSpeed = false;
        dest = BotMovementPlanner.choose(me,enemy);
        check(dest.x() > me.x && dest.steps() <= me.theluc - me.buocdi, "advance toward better range within stamina");
        enemy.x = (short)(me.x + 160);
        check(BotMovementPlanner.choose(me,enemy).steps() == 0, "stay at good firing distance");
        check(!MovementStep.next(map,0,100,false,1,false).moved(), "map boundary blocks walking");
        BotBehaviorTest.TestBot bot = BotBehaviorTest.bot(-300);
        RoomWait room = BotBehaviorTest.room(bot); room.started = true; room.mapData = map;
        bot.index = 0; map.timeUntilAction2 = 0;
        bot.policyOverride = BotPolicy.fromForm(java.util.Map.of("think","0"));
        BotTurnController controller = new BotTurnController();
        controller.update(bot,1000); controller.update(bot,10000);
        check(map.isTurn, "deadline finishes turn");
        map.isTurn = false; controller.update(bot,10001);
        check(!map.isTurn, "completed turn does not finish twice");
        map.nTurn++; controller.update(bot,11000); bot.lock = true; controller.update(bot,11001);
        bot.lock = false; map.indexTurnPlayer = 1; controller.update(bot,22000);
        check(!map.isTurn, "locked or other player turn has no action");
        // Cooperative entry point must not start a worker, even with no time budget.
        BulletTrajectory search = new BulletTrajectory(map,0,0,me.x,me.y,24,24,200,76,24,24,45,1,true,true);
        search.stepSearch(1,0);
        check(!search.complate && !search.place, "zero search budget yields without result");
        for (int bullet : new int[]{0,1,2,9,10,11,17,19,21,49}) {
            BulletTrajectory bounded = new BulletTrajectory(map,0,bullet,me.x,me.y,24,24,300,76,24,24,45,30,true,true);
            for (int i = 0; i < 100 && !bounded.complate; i++) bounded.stepSearch(12, 10_000_000);
            check(bounded.complate, "bounded normal weapon search terminates: " + bullet);
        }
        BulletTrajectory legacy = new BulletTrajectory(map,0,0,me.x,me.y,24,24,0,0,500,200,45,30,true,true);
        legacy.calculateTrajectory();
        check(legacy.place, "legacy boss search still finds reachable target");
        BulletTrajectory hit = new BulletTrajectory(map,0,0,me.x,me.y,24,24,0,0,500,200,45,30,true,true);
        hit.stepSearch(12,10_000_000);
        check(hit.place && hit.complate && me.hp == 100, "cooperative hit leaves live HP unchanged");
        Terrain combat = terrain();
        Player shooter = player(combat,50,0), victim = player(combat,350,1);
        combat.players = new Player[]{shooter,victim};
        CombatBot actor = BotBehaviorTest.GSON.fromJson("{\"id\":-400}", CombatBot.class);
        actor.roomWait = new RoomWait((byte)0,(byte)0,(byte)0,new byte[]{0},0,100,(byte)8);
        actor.roomWait.started = true; actor.roomWait.mapData = combat; actor.index = 0; actor.policyOverride = BotPolicy.fromForm(java.util.Map.of("think","0"));
        BotTurnController fight = new BotTurnController();
        fight.update(actor,1);
        combat.timeUntilAction2 = 100;
        fight.update(actor,50);
        check(shooter.x == 50 && actor.shots == 0, "wait for engine action delay");
        for (int tick = 101; tick < 8000; tick += 10) {
            combat.timeUntilAction2 = 0;
            fight.update(actor,tick);
        }
        check(shooter.x > 50 && shooter.buocdi <= shooter.theluc, "controller walks through actual movement engine");
        check(actor.shots == 1, "controller aims after movement and shoots once");
        check(combat.broadcasts > 1, "movement split into broadcast segments");
        fight.update(actor,9000);
        check(actor.shots == 1, "no repeated shot after turn completed");
        System.out.println(checks + " tactical checks passed");
    }
}
