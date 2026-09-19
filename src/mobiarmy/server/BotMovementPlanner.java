package mobiarmy.server;

import mobiarmy.war.MovementStep;
import mobiarmy.war.Player;

/** Small bounded candidate set; never mutates the live player or terrain. */
public final class BotMovementPlanner {
    public record Destination(int x, int y, int steps, double score) {}
    public static Destination choose(Player me, Player target) {
        return choose(me,target,48,me.isRunSpeed);
    }
    public static Destination choose(Player me, Player target, int limit, boolean fast) {
        Destination best = new Destination(me.x, me.y, 0, score(me, target, me.x, me.y, 0));
        if (me.countFreeze > 0 || me.isFly) return best;
        int remaining = Math.min(limit, Math.max(0, me.theluc - me.buocdi));
        for (boolean right : new boolean[]{false, true}) {
            int x = me.x, y = me.y;
            for (int used = 1; used <= remaining; used++) {
                MovementStep.Result step = MovementStep.next(me.mapData, x, y, right, fast ? 2 : 1, false);
                if (!step.moved() || step.y() > me.mapData.height || step.y() - y > 24) break;
                x = step.x(); y = step.y();
                if (used % 8 != 0 && used != remaining) continue;
                double value = score(me, target, x, y, used);
                if (value > best.score() + 5) best = new Destination(x, y, used, value);
            }
        }
        return best;
    }
    public static double score(Player me, Player target, int x, int y, int steps) {
        double result = -Math.abs(Math.abs(target.x - x) - 160) * .4 - steps * .1;
        // A clear direct corridor is a cheap prefilter; the real ballistic search follows movement.
        int blocked = 0;
        for (int i = 1; i <= 12; i++) {
            int px = x + (target.x - x) * i / 13;
            int py = y - me.height / 2 + (target.y - target.height / 2 - y + me.height / 2) * i / 13;
            if (me.mapData.isCollisionMap(px, py)) blocked++;
        }
        result -= blocked * 15;
        for (int offset : new int[]{-6, 6}) {
            MovementStep.Result edge = MovementStep.next(me.mapData, x + offset, y, offset > 0, 1, false);
            if (!edge.moved() || edge.y() > y + 24) result -= 30;
        }
        for (Player ally : me.mapData.players)
            if (ally != null && ally != me && !ally.isDie && ally.team == me.team
                    && Math.abs(ally.x - x) < me.width * 2) result -= 40;
        return result;
    }
    /** After failed shots, try another safe foothold even if the range heuristic prefers staying. */
    static Destination explore(Player me, Player target, int limit, java.util.List<Integer> failedX) {
        Destination best = new Destination(me.x,me.y,0,-Double.MAX_VALUE);
        if(me.countFreeze>0 || me.isFly) return best;
        int remaining=Math.min(limit,Math.max(0,me.theluc-me.buocdi));
        for(boolean right:new boolean[]{false,true}) {
            int x=me.x,y=me.y;
            for(int used=1;used<=remaining;used++) {
                var next=MovementStep.next(me.mapData,x,y,right,me.isRunSpeed?2:1,false);
                if(!next.moved() || next.y()>=me.mapData.height || next.y()-y>24)break;
                x=next.x();y=next.y();
                if(used%8!=0 && used!=remaining)continue;
                double value=score(me,target,x,y,used)+Math.min(48,Math.abs(x-me.x));
                for(int old:failedX)value-=Math.max(0,48-Math.abs(x-old))*3;
                if(value>best.score())best=new Destination(x,y,used,value);
            }
        }
        return best;
    }
    private BotMovementPlanner() {}
}
