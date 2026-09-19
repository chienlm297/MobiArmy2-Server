package mobiarmy.server;
import java.lang.reflect.Field;
import mobiarmy.war.*;
import mobiarmy.war.Boss.bullet.BulletTrajectory;

public class BotDeadTargetTest {
    static int checks;
    static void check(boolean v,String s){if(!v)throw new AssertionError(s);checks++;System.out.println("PASS: "+s);}
    static void set(Object target,String field,Object value)throws Exception {Field f=Bot.class.getDeclaredField(field);f.setAccessible(true);f.set(target,value);}
    public static void main(String[]args)throws Exception {
        var map=BotTacticalTest.terrain();var me=BotTacticalTest.player(map,50,0);var enemy=BotTacticalTest.player(map,180,1);
        map.players=new Player[]{me,enemy};me.isCollision=true;enemy.isCollision=true;
        // Use the real collision method, not Terrain's test override.
        MapData collisions=BotBehaviorTest.GSON.fromJson("{}",MapData.class);collisions.players=map.players;
        check(collisions.isCollisionPlayer(enemy.x,enemy.y-5),"living player collides with bullets");
        enemy.isDie=true;
        check(!collisions.isCollisionPlayer(enemy.x,enemy.y-5),"corpse with stale positive HP does not block bullets");
        enemy.isDie=false;enemy.hp=0;
        check(!collisions.isCollisionPlayer(enemy.x,enemy.y-5),"zero HP without death flag does not block bullets");
        enemy.hp=100;enemy.countInvisible2=1;
        check(!collisions.isCollisionPlayer(enemy.x,enemy.y-5),"invisible player collision rule preserved");enemy.countInvisible2=0;
        var bot=BotBehaviorTest.GSON.fromJson("{\"id\":-900}",BotTacticalTest.CombatBot.class);
        set(bot,"selecteds",new java.util.ArrayList<>());
        var room=BotBehaviorTest.room(bot);room.started=true;room.mapData=map;bot.index=0;me.user=bot;
        bot.observeRoom(System.currentTimeMillis());set(bot,"observedMap",map);set(bot,"observedTurn",map.nTurn);
        set(bot,"legacyAimTarget",enemy);set(bot,"legacyTargetX",(int)enemy.x);set(bot,"legacyTargetY",(int)enemy.y);
        set(bot,"legacyOriginX",(int)me.x);set(bot,"legacyOriginY",(int)me.y);
        check(bot.legacyAimValid(me),"legacy solution valid while target remains alive and still");
        enemy.x++;check(!bot.legacyAimValid(me),"legacy solution invalid after target moves");enemy.x--;
        map.players[1]=null;check(!bot.legacyAimValid(me),"legacy solution invalid after target leaves");map.players[1]=enemy;
        enemy.isDie=true;enemy.hp=0;
        me.trajectory=new BulletTrajectory(map,0,0,50,100,24,24,180,76,24,24,45,1,true,true);
        me.trajectory.place=true;me.trajectory.complate=true;
        bot.update();
        check(bot.shots==0 && me.trajectory==null,"legacy update rejects completed aim at newly dead target");
        bot.policyOverride=BotPolicy.fromForm(java.util.Map.of("think","0","items","false"));
        var controller=new BotTurnController();controller.update(bot,1);controller.update(bot,2);
        check(bot.shots==0 && map.isTurn,"tactical AI finishes instead of aiming at only corpse");
        enemy.hp=100;enemy.isDie=false;map.isTurn=false;map.nTurn++;
        bot.policyOverride=BotPolicy.fromForm(java.util.Map.of("think","0","items","false","movement","false"));
        controller=new BotTurnController();controller.update(bot,10);controller.update(bot,11);
        check(bot.shots==0,"tactical search pending before distant target dies");
        enemy.hp=0;enemy.isDie=true;controller.update(bot,12);
        check(bot.shots==0 && map.isTurn,"tactical cached search discarded when target dies mid-aim");
        System.out.println(checks+" dead-target regression checks passed");
    }
}
