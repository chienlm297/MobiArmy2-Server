package mobiarmy.server;

import java.util.*;
import mobiarmy.war.*;
import mobiarmy.war.Boss.bullet.*;

public class BotItemsTest {
    static int checks;
    static void check(boolean v,String name) { if(!v) throw new AssertionError(name); checks++; System.out.println("PASS: "+name); }
    static BotPolicy policy(String preset) { return BotPolicy.fromForm(java.util.Map.of("preset",preset,"think","0")); }
    static final class Fixture {
        BotTacticalTest.Terrain map=BotTacticalTest.terrain();
        Player me=BotTacticalTest.player(map,50,0), ally=BotTacticalTest.player(map,140,0);
        BotTacticalTest.CombatBot bot=BotBehaviorTest.GSON.fromJson("{\"id\":-800}",BotTacticalTest.CombatBot.class);
        Fixture() {
            map.players=new Player[]{me,ally}; me.index=0; ally.index=1; me.user=bot;
            bot.items=new ArrayList<>(); bot.policyOverride=policy("BALANCED");
            bot.roomWait=new RoomWait((byte)0,(byte)0,(byte)0,new byte[]{0},0,100,(byte)8);
            bot.roomWait.started=true; bot.roomWait.mapData=map; bot.index=0;
            me.items=new Player.Item[]{me.new Item(0),me.new Item(1),me.new Item(2),me.new Item(3),me.new Item(5),me.new Item(6),me.new Item(10)};
            for(int id:BotPolicy.SUPPORTED) if(id!=100) { Item i=new Item();i.id=id;i.num=id<2?99:3;i.carryable=2;bot.items.add(i); }
            me.hpMax=1000; me.hp=100; ally.hpMax=1000; ally.hp=900;
        }
    }
    public static void main(String[] args) throws Exception {
        Fixture f=new Fixture(); f.me.angry=100;
        check(BotItemPolicy.defensive(f.me,f.bot.effectivePolicy())==0,"critical HP takes priority over POW");
        f.map.useItem(0,(byte)0);
        check(f.me.hp==450 && f.me.isUseItem && f.me.items[0].isUse,"engine heals and consumes carried HP slot");
        f.map.useItem(0,(byte)2);
        check(f.bot.getItem(2).num==3 && f.me.nshoot==0,"one item per turn prevents subsequent x2");
        f=new Fixture();f.bot.getItem(2).num=0;
        check(!BotItemPolicy.available(f.me,f.bot.effectivePolicy(),2),"empty stock excluded by policy");
        f.map.useItem(0,(byte)2);
        check(!f.me.isUseItem && !f.me.items[2].isUse,"empty stock does not consume item or turn");
        f.bot.items.remove(f.bot.getItem(2));f.map.useItem(0,(byte)2);
        check(!f.me.isUseItem,"missing inventory is rejected safely");
        f.map.useItem(-1,(byte)0);f.map.useItem(999,(byte)0);
        check(!f.me.isUseItem,"invalid player index has no effect");
        f.me.items=new Player.Item[]{f.me.new Item(100)};f.map.useItem(0,(byte)100);
        check(!f.me.isUseItem,"fake POW slot cannot bypass rage requirement");
        f.me.angry=100;f.map.useItem(0,(byte)100);
        check(f.me.isPow && f.me.angry==0,"POW consumes rage through engine");
        f=new Fixture();f.me.nshoot=1;f.map.useItem(0,(byte)2);
        check(f.me.nshoot==2 && f.bot.getItem(2).num==2,"x2 consumes real inventory once");
        f.map.useItem(0,(byte)2);check(f.bot.getItem(2).num==2,"repeat item request cannot double debit");
        f=new Fixture();f.map.useItem(0,(byte)3);check(f.me.isRunSpeed,"speed item activates real movement flag");
        f=new Fixture();f.map.windX=50;f.map.windY=20;f.map.useItem(0,(byte)5);
        check(f.map.windX==0 && f.map.windY==0,"wind item changes real wind");
        f=new Fixture();f.map.useItem(0,(byte)6);check(f.me.bulletId==6,"terrain item selects correct projectile");
        f=new Fixture();f.me.hp=900;f.ally.hp=200;f.ally.isFaction=true;
        check(BotItemPolicy.defensive(f.me,policy("SUPPORT"))==10,"support selects wounded teammate heal");
        f.map.useItem(0,(byte)10);check(f.ally.hp==500 && f.bot.getItem(10).num==2,"team heal respects engine and stock");
        f=new Fixture();f.me.hp=1000;f.me.angry=100;
        check(BotItemPolicy.offensive(f.me,policy("BALANCED"))==100,"POW selected for attack even at full HP");
        check(BotItemPolicy.offensive(f.me,policy("PASSIVE"))==-1 && !policy("PASSIVE").mayMove(),"passive disables movement and items");
        check(!BotPolicy.fromForm(java.util.Map.of("allowed","0")).mayUse(1),"allowlist gates flight");
        for(java.util.Map<String,String> bad:List.of(java.util.Map.of("allowed","24"),java.util.Map.of("heal","101"),java.util.Map.of("steps","90"),java.util.Map.of("enabled","yes"))) {
            try {BotPolicy.fromForm(bad);throw new AssertionError("bad config accepted");} catch(IllegalArgumentException expected) {check(true,"invalid config rejected");}
        }
        f=new Fixture();f.bot.roomWait.started=false;f.bot.setItems=new byte[]{0,0,1,1,-1,-1,-1,-1};
        BotLoadout.apply(f.bot,"0,1,2,3",5);
        check(f.bot.getItem(2).num==8 && f.bot.setItems[2]==2,"explicit provisioning prepares carried slots");
        int stock=f.bot.getItem(2).num;
        try {BotLoadout.apply(f.bot,"2,2,2,2",1);throw new AssertionError();} catch(IllegalArgumentException expected) {check(f.bot.getItem(2).num==stock,"invalid loadout is atomic");}
        f.bot.roomWait.started=true;
        try {BotLoadout.apply(f.bot,"0,1,2,3",1);throw new AssertionError();} catch(IllegalArgumentException expected) {check(f.bot.getItem(2).num==stock,"no provisioning during combat");}
        f=new Fixture();
        check(BotTurnController.safeLanding(f.me,250,100),"safe ground accepted for flight");
        f.map.pit=true;check(!BotTurnController.safeLanding(f.me,250,100),"pit rejected for flight");f.map.pit=false;
        check(!BotTurnController.safeLanding(f.me,f.ally.x,f.ally.y),"occupied landing rejected");
        BulletTrajectory flight=new BulletTrajectory(f.map,0,5,50,100,24,24,0,99,500,5,45,10,true,true);
        for(int i=0;i<100 && !flight.complate;i++) flight.stepSearch(12,10_000_000);
        check(flight.place && flight.impactY==100,"flight search requires actual ground collision");
        check(f.me.x==50 && f.me.y==100 && !f.map.disableLuck,"preview flight does not teleport or change luck");
        Gun gun=new Gun(f.map,0,false);
        gun.shootBullet(5,50,100,24,24,flight.ang,flight.force,flight.force2,1,0,-1);gun.fillXY();
        check(f.me.x==flight.impactX && f.me.y==flight.impactY,"actual flight lands at predicted collision");
        f=new Fixture();f.bot.policyOverride=policy("PASSIVE");
        BotTurnController controller=new BotTurnController();controller.update(f.bot,1);
        f.bot.policyOverride=policy("SUPPORT");controller.update(f.bot,2);
        check(!f.me.isUseItem,"configuration remains frozen for current turn");
        f.map.nTurn++;controller.update(f.bot,3);controller.update(f.bot,4);
        check(f.me.isUseItem && f.me.hp==450,"configuration applies on next turn");
        System.out.println(checks+" item and flight checks passed");
    }
}
