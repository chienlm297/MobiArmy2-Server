package mobiarmy.server;
import java.lang.management.ManagementFactory;
import java.util.*;
import mobiarmy.war.*;

/** Synthetic fixtures, including real Bot.updateBot scheduling; no sockets or database. */
public class BotBenchmark {
    static void sample(String label,int ticks) {
        long[] timings=new long[ticks];
        for(int i=0;i<ticks;i++) {
            for(Bot b:Bot.bots) if(b.roomWait!=null) {
                b.roomWait.mapData.nTurn++; b.roomWait.mapData.timeUntilAction2=0;
            }
            long start=System.nanoTime(); Bot.updateBot();
            // Drive stages against live engine with deterministic virtual timestamps in combat workloads.
            if(label.startsWith("combat")) for(int stage=0;stage<12;stage++) {
                BotTurnController.beginTick();
                for(int j=0;j<controllers.size();j++) {
                    Bot.bots.get(j).roomWait.mapData.timeUntilAction2=0;
                    controllers.get(j).update(Bot.bots.get(j),i*20000L+1+stage*1000);
                }
            }
            timings[i]=System.nanoTime()-start;
        }
        Arrays.sort(timings);
        long memory=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        System.out.printf(Locale.ROOT,"%s samples=%d p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f heap_mb=%.1f threads=%d%n",label,ticks,timings[ticks/2]/1e6,timings[(int)(ticks*.95)]/1e6,timings[(int)(ticks*.99)]/1e6,memory/1048576.0,ManagementFactory.getThreadMXBean().getThreadCount());
    }
    static List<BotTurnController> controllers=new ArrayList<>();
    public static void main(String[] args)throws Exception {
        for(int count:new int[]{10,50,100}) {
            Bot.bots.clear();controllers.clear();
            for(int i=0;i<count;i++) {
                var b=BotBehaviorTest.GSON.fromJson("{\"id\":"+(-1000-i)+"}",BotTacticalTest.CombatBot.class);
                var f=Bot.class.getDeclaredField("selecteds");f.setAccessible(true);f.set(b,new ArrayList<>());
                b.policyOverride=BotPolicy.fromForm(java.util.Map.of("think","0","items","false"));
                var map=BotTacticalTest.terrain();var me=BotTacticalTest.player(map,50,0);var enemy=BotTacticalTest.player(map,350,1);
                map.players=new Player[]{me,enemy};
                b.roomWait=new RoomWait((byte)0,(byte)0,(byte)0,new byte[]{0},0,100,(byte)8);
                b.roomWait.started=true;b.roomWait.mapData=map;b.index=0;
                Bot.bots.add(b);controllers.add(new BotTurnController());
            }
            sample("combat-"+count+"-thirteen-AI-passes",100);
        }
        Bot.bots.clear();controllers.clear();
        for(int i=0;i<5000;i++)Bot.bots.add(BotBehaviorTest.bot(-10000-i));
        sample("idle-5000-one-game-tick",300);
    }
}
