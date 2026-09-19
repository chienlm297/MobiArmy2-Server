package mobiarmy.server;

import java.util.*;
import mobiarmy.war.*;

/** Deterministic state-space simulation, not full network matches. */
public class BotSoakTest {
    static class Barrier extends BotTacticalTest.Terrain {
        Barrier(int unused)throws Exception {super(unused);}
        @Override public boolean isCollisionMap(int x,int y){return y>=100 || x>=180 && x<=190;}
    }
    static int checks;
    static void require(boolean ok,String why) { if(!ok) throw new AssertionError(why); checks++; }
    public static void main(String[] args) throws Exception {
        Random random=new Random(20260919L);
        int shots=0, moves=0, items=0;
        for(int trial=0;trial<1000;trial++) {
            BotItemsTest.Fixture f=new BotItemsTest.Fixture();
            f.ally.team=1; f.ally.x=(short)(220+random.nextInt(230));
            f.me.hp=100+random.nextInt(901); f.me.theluc=(byte)random.nextInt(41);
            f.me.nshoot=1; f.me.countFreeze=(byte)(trial%7==0?1:0);
            f.map.pit=trial%5==0; f.map.wall=trial%5==1;
            f.map.windX=(byte)(random.nextInt(101)-50);
            f.map.windY=(byte)(random.nextInt(41)-20);
            f.bot.policyOverride=BotPolicy.fromForm(java.util.Map.of("think","0","preset",new String[]{"BALANCED","AGGRESSIVE","SUPPORT","PASSIVE"}[trial%4]));
            for(Item item:f.bot.items) if(item.id>=2)item.num=trial%3;
            int[] stock=f.bot.items.stream().mapToInt(i->i.num).toArray();
            short initialX=f.me.x;
            BotTurnController controller=new BotTurnController();
            for(int tick=1;tick<=10001;tick+=20) {
                // Model the game loop budget resetting, including concurrent target invalidation.
                BotTurnController.beginTick(); f.map.timeUntilAction2=0;
                if(tick==101 && trial%6==0){f.ally.isDie=true;f.ally.hp=0;}
                controller.update(f.bot,tick);
                require(f.bot.shots<=1,"duplicate shot trial="+trial);
                require(f.me.buocdi<=f.me.theluc,"stamina overflow trial="+trial);
                require(f.me.x>=0 && f.me.x<f.map.width && f.me.y<f.map.height,"unsafe position trial="+trial);
            }
            require(f.map.isTurn,"turn did not terminate trial="+trial);
            if(f.me.countFreeze>0)require(f.me.x==initialX,"frozen bot walked trial="+trial);
            require(f.bot.usedItems<=1,"multiple items trial="+trial);
            for(int i=0;i<stock.length;i++) require(f.bot.items.get(i).num>=0 && f.bot.items.get(i).num<=stock[i],"stock underflow/refill trial="+trial);
            shots+=f.bot.shots;moves+=f.bot.moves;items+=f.bot.usedItems;
        }
        System.out.println("PASS: 1000 deterministic simulated turns seed=20260919 checks="+checks+" shots="+shots+" moveSegments="+moves+" items="+items);
        // An item must still be considered after walking reaches its destination.
        BotItemsTest.Fixture f=new BotItemsTest.Fixture();f.me.hp=1000;f.ally.team=1;f.ally.x=350;
        f.map.windX=50;f.bot.policyOverride=BotPolicy.fromForm(java.util.Map.of("think","0","allowed","5"));
        BotTurnController c=new BotTurnController();
        for(int tick=1;tick<500;tick+=10){BotTurnController.beginTick();f.map.timeUntilAction2=0;c.update(f.bot,tick);}
        require(f.me.x>50,"wind fixture must walk");
        require(f.map.windX==0 && f.bot.getItem(5).num==2,"wind item skipped after reaching walking destination");
        System.out.println("PASS: wind item after movement");
        f=new BotItemsTest.Fixture();
        Barrier barrier=BotBehaviorTest.GSON.fromJson("{\"width\":500,\"height\":200,\"isTurnPlayer\":true,\"indexTurnPlayer\":0}",Barrier.class);
        barrier.players=f.map.players;f.map=barrier;f.me.mapData=barrier;f.ally.mapData=barrier;f.bot.roomWait.mapData=barrier;
        f.me.hp=1000;f.ally.x=350;f.ally.team=1;
        f.bot.policyOverride=BotPolicy.fromForm(java.util.Map.of("think","0","movement","false","allowed","6","budget","10000"));
        c=new BotTurnController();
        for(int tick=1;tick<10000;tick+=5){BotTurnController.beginTick();f.map.timeUntilAction2=0;c.update(f.bot,tick);}
        require(f.bot.shots==1 && f.bot.lastBullet==6 && f.bot.getItem(6).num==2,"blocked bot must select and shoot terrain item");
        System.out.println("PASS: blocked trajectory selects terrain item through controller");
        f=new BotItemsTest.Fixture();
        barrier=BotBehaviorTest.GSON.fromJson("{\"width\":500,\"height\":200,\"isTurnPlayer\":true,\"indexTurnPlayer\":0}",Barrier.class);
        barrier.players=f.map.players;f.map=barrier;f.me.mapData=barrier;f.ally.mapData=barrier;f.bot.roomWait.mapData=barrier;
        f.me.hp=1000;f.ally.x=350;f.ally.team=1;
        f.bot.policyOverride=BotPolicy.fromForm(java.util.Map.of("think","0","movement","false","allowed","6","budget","10000"));
        c=new BotTurnController();
        var special=BotTurnController.class.getDeclaredField("specialItem");special.setAccessible(true);
        boolean killedDuringSearch=false;
        for(int tick=1;tick<10000;tick+=5){
            BotTurnController.beginTick();f.map.timeUntilAction2=0;c.update(f.bot,tick);
            if(special.getInt(c)==6 && !killedDuringSearch){f.ally.isDie=true;f.ally.hp=0;killedDuringSearch=true;}
        }
        require(killedDuringSearch && f.bot.shots==0 && f.bot.getItem(6).num==3,"dead target cancels terrain shot without consuming inventory");
        System.out.println("PASS: target dies during terrain-item planning, no shot or debit");
        f=new BotItemsTest.Fixture();f.me.hp=1000;f.ally.hp=200;f.ally.isFaction=true;
        f.bot.policyOverride=BotPolicy.fromForm(java.util.Map.of("think","0","preset","SUPPORT","allowed","10"));
        c=new BotTurnController();c.update(f.bot,1);
        require(f.ally.hp==500 && f.bot.getItem(10).num==2 && f.bot.usedItems==1,"controller must heal wounded ally");
        System.out.println("PASS: support heals teammate through controller");
    }
}
