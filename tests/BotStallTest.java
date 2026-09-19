package mobiarmy.server;

import mobiarmy.war.*;
import mobiarmy.war.Boss.bullet.BulletTrajectory;

public class BotStallTest {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS: "+message);}
    static class EndingTerrain extends BotTacticalTest.Terrain {
        int draws, result;
        EndingTerrain(int ignored)throws Exception{super(ignored);}
        @Override public boolean updateComplete(){return false;}
        @Override public void endTheWar(){draws++;result=typeComplete;isWar=false;}
    }
    static class Wall extends BotTacticalTest.Terrain {
        Wall(int ignored)throws Exception{super(ignored);}
        @Override public boolean isCollisionMap(int x,int y){return y>=100 || x>=180 && x<=190;}
    }
    static class SlowTerrain extends BotTacticalTest.Terrain {
        SlowTerrain(int ignored)throws Exception{super(ignored);}
        @Override public boolean isCollisionMap(int x,int y){try{Thread.sleep(2);}catch(InterruptedException e){throw new RuntimeException(e);}return super.isCollisionMap(x,y);}
    }
    public static void main(String[] args)throws Exception {
        var f=new BotItemsTest.Fixture();f.me.isFaction=f.ally.isFaction=true;f.ally.team=1;
        f.ally.user=BotBehaviorTest.bot(-810);f.map.roomWait=f.bot.roomWait;
        User observer=BotBehaviorTest.GSON.fromJson("{}",User.class);
        observer.session=BotBehaviorTest.GSON.fromJson("{\"connected\":true}",Session.class);
        f.map.roomWait.players[2]=observer;
        BotMatchGuard guard=new BotMatchGuard();
        check(guard.observe(f.map,1000)==BotMatchGuard.Reason.NONE,"start grace period with spectator");
        check(guard.observe(f.map,120999)==BotMatchGuard.Reason.NONE,"no premature draw");
        f.me.x++;
        check(guard.observe(f.map,121000)==BotMatchGuard.Reason.NO_PROGRESS,"walking without HP progress does not evade draw deadline");
        guard.reset();guard.observe(f.map,1000);f.me.hp--;
        check(guard.observe(f.map,120000)==BotMatchGuard.Reason.NONE,"real damage resets progress timer");
        check(guard.observe(f.map,239999)==BotMatchGuard.Reason.NONE,"damage grants full interval");
        check(guard.observe(f.map,240000)==BotMatchGuard.Reason.NO_PROGRESS,"draw after new full interval");
        guard.reset();observer.session.connected=false;
        guard.observe(f.map,1000);
        check(guard.observe(f.map,30999)==BotMatchGuard.Reason.NONE,"unattended grace period");
        check(guard.observe(f.map,31000)==BotMatchGuard.Reason.UNATTENDED,"cleanup unattended bot match");
        guard.reset();guard.observe(f.map,1000);observer.session.connected=true;
        check(guard.observe(f.map,31000)==BotMatchGuard.Reason.NONE,"spectator reconnect cancels unattended cleanup");
        f.ally.user=observer;
        check(guard.observe(f.map,999999)==BotMatchGuard.Reason.NONE,"living human is never forced to draw by bot policy");
        f.ally.user=BotBehaviorTest.bot(-810);f.map.isFightBoss=true;
        check(guard.observe(f.map,999999)==BotMatchGuard.Reason.NONE,"boss match excluded");
        f.map.isFightBoss=false;f.ally.isDie=true;
        check(guard.observe(f.map,999999)==BotMatchGuard.Reason.NONE,"normal victory not replaced by draw");
        f.ally.isDie=false;
        var map=BotBehaviorTest.GSON.fromJson("{\"isWar\":true,\"completeWar\":-1}",EndingTerrain.class);
        map.players=f.map.players;map.roomWait=f.map.roomWait;observer.session.connected=false;
        guard=new BotMatchGuard();guard.observe(map,System.currentTimeMillis()-31000);
        var field=MapData.class.getDeclaredField("botMatchGuard");field.setAccessible(true);field.set(map,guard);
        map.update();check(map.draws==1 && map.result==3 && !map.isWar,"map update routes cleanup through existing draw result");
        map.update();check(map.draws==1,"draw is not repeated");
        f=new BotItemsTest.Fixture();f.me.x=200;f.ally.x=360;f.ally.team=1;
        check(BotMovementPlanner.choose(f.me,f.ally).steps()==0,"original heuristic stays at preferred range");
        var move=BotMovementPlanner.explore(f.me,f.ally,32,java.util.List.of(200));
        check(move.steps()>0 && move.steps()<=32 && move.x()!=200,"failed firing position is replaced safely");
        f.me.countFreeze=1;
        check(BotMovementPlanner.explore(f.me,f.ally,32,java.util.List.of(200)).steps()==0,"escape still respects freeze");
        var slow=BotBehaviorTest.GSON.fromJson("{\"width\":500,\"height\":200}",SlowTerrain.class);
        slow.players=new Player[]{BotTacticalTest.player(slow,50,0)};
        BulletTrajectory search=new BulletTrajectory(slow,0,0,50,50,24,24,400,50,24,24,45,20,true,true);
        search.stepSearch(12,1_000_000);
        var active=BulletTrajectory.class.getDeclaredField("candidateActive");active.setAccessible(true);
        check(active.getBoolean(search),"slow candidate yields unfinished");
        int angle=search.ang,force=search.force;
        search.stepSearch(12,1_000_000);
        check(search.ang==angle && search.force==force,"next budget slice resumes same candidate instead of dropping it");
        for(boolean nearAlly:new boolean[]{false,true}) {
            f=new BotItemsTest.Fixture();
            var wall=BotBehaviorTest.GSON.fromJson("{\"width\":500,\"height\":200,\"isTurnPlayer\":true,\"indexTurnPlayer\":0}",Wall.class);
            f.me.mapData=wall;f.ally.mapData=wall;f.me.hp=1000;f.ally.team=1;f.ally.x=350;
            wall.players=nearAlly?new Player[]{f.me,f.ally,BotTacticalTest.player(wall,170,0)}:new Player[]{f.me,f.ally};
            f.bot.roomWait.mapData=wall;
            f.bot.policyOverride=BotPolicy.fromForm(java.util.Map.of("think","0","items","false","movement","false"));
            var controller=new BotTurnController();
            for(int tick=1;tick<10000;tick+=5){BotTurnController.beginTick();wall.timeUntilAction2=0;controller.update(f.bot,tick);}
            check(nearAlly?f.bot.shots==0:f.bot.shots==1 && f.bot.lastBullet==0 && f.bot.usedItems==0,
                    nearAlly?"terrain fallback rejected near living ally":"blocked Gunner clears terrain with ordinary ammo, no item granted");
        }
        System.out.println(checks+" anti-stall checks passed");
    }
}
