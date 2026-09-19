package mobiarmy.war;

import mobiarmy.server.Bot;
import mobiarmy.server.User;

/** Match-wide safety policy for PvP whose surviving combatants are all server bots. */
public final class BotMatchGuard {
    public enum Reason { NONE, NO_PROGRESS, UNATTENDED }
    public static final long NO_PROGRESS_MS=120_000, UNATTENDED_MS=30_000;
    private int[] hp;
    private long lastProgress, unattendedSince=-1;

    public Reason observe(MapData map,long now) {
        int bots=0;
        if(map.isFightBoss){reset();return Reason.NONE;}
        for(Player p:map.players) if(p!=null && p.hp>0 && !p.isDie && p.isFaction) {
            if(!(p.user instanceof Bot)){reset();return Reason.NONE;}
            bots++;
        }
        if(bots<2){reset();return Reason.NONE;}
        boolean changed=hp==null || hp.length!=map.players.length;
        if(changed)hp=new int[map.players.length];
        for(int i=0;i<hp.length;i++) {
            Player p=map.players[i];int value=p==null || p.isDie?0:p.hp;
            if(hp[i]!=value)changed=true;
            hp[i]=value;
        }
        if(changed)lastProgress=now;
        boolean observer=false;
        if(map.roomWait!=null)for(User u:map.roomWait.players)
            if(u!=null && !(u instanceof Bot) && u.session!=null && u.session.connected){observer=true;break;}
        if(observer)unattendedSince=-1;
        else if(unattendedSince<0)unattendedSince=now;
        if(unattendedSince>=0 && now-unattendedSince>=UNATTENDED_MS)return Reason.UNATTENDED;
        if(now-lastProgress>=NO_PROGRESS_MS)return Reason.NO_PROGRESS;
        return Reason.NONE;
    }
    public void reset(){hp=null;lastProgress=0;unattendedSince=-1;}
}
