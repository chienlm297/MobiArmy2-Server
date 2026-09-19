package mobiarmy.server;

import java.util.*;
import mobiarmy.war.*;
import mobiarmy.war.Boss.bullet.BulletTrajectory;

/** All decisions and effects belong to the game loop; settings are frozen per turn. */
public final class BotTurnController {
    private static long remainingNanos = Long.MAX_VALUE;
    static boolean budgetExhausted() { return remainingNanos <= 0; }
    static void beginTick() { remainingNanos = 4_000_000; }
    enum State { ITEM, MOVE, AIM, DONE }
    private MapData map;
    private int turn = -1, attempts, movedSteps;
    private State state = State.DONE;
    private long expires, thinkUntil;
    private Player target;
    private final Set<Player> tried = new HashSet<>();
    private BulletTrajectory search;
    private int originX, originY, targetX, targetY, windX, windY, searchBullet;
    private BotMovementPlanner.Destination destination;
    private BotPolicy policy;
    private int specialItem = -1;
    private int landingX, landingY;
    private boolean escapeTried;
    private boolean terrainShot;
    private MapData historyMap;
    private final ArrayList<Integer> failedPositions = new ArrayList<>();

    public boolean active() { return map != null && state != State.DONE; }
    public void reset() { map=null; search=null; target=null; state=State.DONE; }
    public void update(Bot bot,long now) {
        if (remainingNanos<=0) return;
        long started=System.nanoTime();
        try { updateTurn(bot,now); }
        finally { remainingNanos-=System.nanoTime()-started; }
    }
    private void updateTurn(Bot bot,long now) {
        if (bot.remove || bot.roomWait==null || !bot.roomWait.started) { reset(); historyMap=null; failedPositions.clear(); return; }
        if (bot.lock) { reset(); return; }
        MapData current=bot.roomWait.mapData;
        if (current==null || bot.index<0 || bot.index>=current.players.length || current.getTurn()!=bot.index) { reset(); return; }
        Player me=current.players[bot.index];
        if (me==null || me.isDie || me.isShoot) { reset(); return; }
        if (map!=current || turn!=current.nTurn) {
            if(historyMap!=current){historyMap=current;failedPositions.clear();}
            policy=bot.effectivePolicy();
            if (!policy.enabled()) { reset(); return; }
            map=current; turn=current.nTurn; expires=now+policy.turnMs(); thinkUntil=now+policy.thinkMs();
            state=State.ITEM; search=null; target=null; destination=null;
            attempts=0; movedSteps=0; specialItem=-1; escapeTried=false; terrainShot=false; tried.clear();
            bot.decision("Quan sát lượt " + turn + " · " + policy.preset());
        }
        if (state==State.DONE || now<=map.timeUntilAction2 || now<thinkUntil) return;
        if (now>=expires) { bot.timeouts++; finish(bot,"Hết ngân sách lượt"); return; }
        if (state==State.ITEM) {
            state=State.MOVE;
            int item=BotItemPolicy.defensive(me,policy);
            if (item>=0) use(bot,me,item,"Hồi máu");
            return;
        }
        if (!validTarget(me,target)) {
            ArrayList<Player> candidates=new ArrayList<>();
            for (Player p:map.players) if (validTarget(me,p) && !tried.contains(p)) candidates.add(p);
            if (candidates.isEmpty()) { finish(bot,tried.isEmpty()?"Không còn mục tiêu nhìn thấy":"Đã thử mục tiêu sống nhưng không có đường bắn"); return; }
            target=Bot.takeTarget(candidates,bot.effectiveTargetMode()); search=null; destination=null;
            specialItem=-1; terrainShot=false;
        }
        if (state==State.MOVE) {
            if (destination==null) {
                destination=BotMovementPlanner.choose(me,target,policy.mayMove()?policy.maxSteps():0,me.isRunSpeed);
                if(policy.mayMove() && !failedPositions.isEmpty())
                    destination=BotMovementPlanner.explore(me,target,policy.maxSteps(),failedPositions);
                if (policy.mayMove() && !me.isRunSpeed && BotItemPolicy.available(me,policy,3)) {
                    var faster=BotMovementPlanner.choose(me,target,policy.maxSteps(),true);
                    if (faster.score()>destination.score()+8 && faster.steps()>0 && use(bot,me,3,"Đi x2 tới vị trí tốt hơn")) {
                        destination=faster; return;
                    }
                }
                bot.decision(destination.steps()==0?"Giữ vị trí":"Di chuyển tới "+destination.x()+","+destination.y());
            }
            int x=me.x,y=me.y;
            int steps=Math.min(8,Math.min(me.theluc-me.buocdi,policy.maxSteps()-movedSteps));
            for (int i=0;i<steps && x!=destination.x();i++) {
                if (me.countFreeze>0) break;
                var next=MovementStep.next(map,x,y,destination.x()>x,me.isRunSpeed?2:1,me.isFly);
                if (!next.moved() || next.y()>map.height || next.y()-y>24) break;
                x=next.x(); y=next.y();
            }
            if (x!=me.x) {
                int before=me.buocdi;
                bot.moveLocation((short)x,(short)y); movedSteps+=Math.max(0,me.buocdi-before); bot.moves++;
                if (me.x!=x || me.x==destination.x()) state=State.AIM;
                return;
            }
            state=State.AIM;
        }
        // Walking can enter AIM on the preceding tick when the destination is reached.
        // Evaluate wind on both paths into AIM, before starting trajectory search.
        if (search==null && specialItem<0 && BotItemPolicy.available(me,policy,5)
                && Math.abs(map.windX)+Math.abs(map.windY)>=35) {
            use(bot,me,5,"Ngưng gió mạnh trước ngắm"); return;
        }
        int bullet=specialItem==1?5:specialItem==6?6:me.bulletId;
        if (search==null || originX!=me.x || originY!=me.y || targetX!=target.x || targetY!=target.y
                || windX!=map.windX || windY!=map.windY || searchBullet!=bullet) {
            if (++attempts>4) { finish(bot,"Đạt giới hạn tìm góc"); return; }
            originX=me.x; originY=me.y; targetX=target.x; targetY=target.y;
            windX=map.windX; windY=map.windY; searchBullet=bullet;
            boolean point=specialItem>=0 || terrainShot;
            int tx=point?landingX-5:target.x-target.width/2;
            int ty=point?landingY-3:target.y-target.height;
            search=new BulletTrajectory(map,bot.index,bullet,me.x,me.y,me.width,me.height,tx,ty,
                    point?11:target.width,point?7:target.height,
                    specialItem>=0?10:me.glassID==2||me.glassID==3?45:bot.glass().angle,1,true,true);
            search.isPow=me.isPow?1:0;
        }
        long started=System.nanoTime();
        search.stepSearch(12,Math.min(remainingNanos,1_000_000));
        bot.searchNanos+=System.nanoTime()-started; bot.searchCalls++;
        if (!search.complate) return;
        if (search.place) {
            if(terrainShot && !safeDig(me,search.impactX,search.impactY)){finish(bot,"Vật cản quá gần đồng đội");return;}
            if (specialItem>=0) {
                if (specialItem==1 && !safeLanding(me,search.impactX,search.impactY) || !use(bot,me,specialItem,"Dùng item chiến thuật "+specialItem)) {
                    finish(bot,"Điểm đáp/item không còn hợp lệ"); return;
                }
            } else if (!terrainShot) {
                int attack=BotItemPolicy.offensive(me,policy);
                if (attack>=0 && use(bot,me,attack,"Tăng sát thương sau khi tìm được đường bắn")) {
                    // POW may change projectile spread. Recompute with actual post-item state.
                    if (attack==100) { search=null; return; }
                }
            }
            state=State.DONE;
            failedPositions.clear();
            bot.decision(specialItem==1?"Bay tới "+landingX+","+landingY:terrainShot?"Bắn phá vật cản bằng đạn "+me.bulletId:"Bắn đạn "+me.bulletId);
            bot.shoot((byte)me.bulletId,me.x,me.y,(short)search.ang,(byte)search.force,(byte)search.force2,me.nshoot);
            map.isTurn=true; search=null;
        } else {
            search=null;
            if (!escapeTried && specialItem<0) {
                escapeTried=true;
                if (selectEscape(me)) return;
            }
            tried.add(target); target=null; specialItem=-1; terrainShot=false;
            if (attempts>=4) finish(bot,"Không có đường bắn hợp lệ");
        }
    }
    private boolean selectEscape(Player me) {
        if (BotItemPolicy.available(me,policy,1) && policy.mayMove()) {
            double best=BotMovementPlanner.score(me,target,me.x,me.y,0)+10;
            for (int delta:new int[]{-180,-120,-64,64,120,180}) {
                int x=me.x+delta;
                if (x<8 || x>=map.width-8) continue;
                int y=0;
                while(y<map.height && !map.isCollisionMap(x,y)) y++;
                if (!safeLanding(me,x,y)) continue;
                double score=BotMovementPlanner.score(me,target,x,y,0);
                if (score>best) { best=score; landingX=x; landingY=y; specialItem=1; }
            }
            if (specialItem==1) return true;
        }
        boolean digItem=BotItemPolicy.available(me,policy,6);
        if (digItem || me.glassID==0 && me.bulletId==0 && !me.isPow) {
            for (int i=1;i<32;i++) {
                int x=me.x+(target.x-me.x)*i/32;
                int y=me.y-me.height/2+(target.y-target.height/2-me.y+me.height/2)*i/32;
                if (map.isCollisionMap(x,y) && safeDig(me,x,y)) {
                    landingX=x; landingY=y; specialItem=digItem?6:-1; terrainShot=true; return true;
                }
            }
        }
        return false;
    }
    private boolean safeDig(Player me,int x,int y) {
        for(Player p:map.players) if(p!=null && !p.isDie && p.hp>0 && (p==me || p.team==me.team)
                && Math.hypot(p.x-x,p.y-p.height/2-y)<96)return false;
        return true;
    }
    static boolean safeLanding(Player me,int x,int y) {
        MapData m=me.mapData;
        if(x<8 || x>=m.width-8 || y<me.height || y>=m.height || !m.isCollisionMap(x,y)) return false;
        for(int dx:new int[]{-6,0,6}) {
            if(m.isCollisionMap(x+dx,y-5)) return false;
            boolean support=false;
            for(int dy=0;dy<=8;dy++) if(m.isCollisionMap(x+dx,y+dy)) { support=true; break; }
            if(!support) return false;
        }
        for(Player p:m.players) if(p!=null && p!=me && !p.isDie && Math.abs(p.x-x)<me.width && Math.abs(p.y-y)<me.height) return false;
        return true;
    }
    private boolean use(Bot bot,Player me,int id,String reason) {
        if(!BotItemPolicy.available(me,policy,id)) return false;
        bot.useItem((byte)id);
        if(!me.isUseItem) { bot.decision("Item bị engine từ chối: "+id); return false; }
        bot.usedItems++; bot.decision(reason); return true;
    }
    private boolean validTarget(Player me,Player p) {
        return Bot.isTarget(me,p) && p.countInvisible==0 && p.countInvisible2==0 && Arrays.asList(map.players).contains(p);
    }
    private void finish(Bot bot,String reason) {
        if(state!=State.DONE){
            bot.skippedTurns++;
            Player me=map.players[bot.index];
            if(me!=null){if(failedPositions.size()==4)failedPositions.remove(0);failedPositions.add((int)me.x);}
        }
        state=State.DONE; search=null; map.isTurn=true; bot.decision(reason);
    }
}
