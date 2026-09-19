package mobiarmy.server;

import mobiarmy.war.Player;

/** Selects only carried, unused items backed by real stock. Does not mutate state. */
public final class BotItemPolicy {
    public static boolean available(Player p, BotPolicy policy, int id) {
        if (!policy.mayUse(id) || p.isUseItem || p.isShoot || p.isDie) return false;
        if (id == 100) return p.angry >= 100;
        if (p.items == null || p.findUnusedItemById(id) == null) return false;
        if (id == 0 || id == 1) return true;
        Item stock = p.user == null ? null : p.user.getItem(id);
        return stock != null && stock.num > 0;
    }
    public static int defensive(Player p, BotPolicy policy) {
        int threshold = policy.preset() == BotPolicy.Preset.SUPPORT ? Math.max(60,policy.healPercent())
                : policy.preset() == BotPolicy.Preset.AGGRESSIVE ? Math.min(30,policy.healPercent()) : policy.healPercent();
        if (p.hpMax > 0 && p.hp * 100L <= p.hpMax * (long)threshold && available(p,policy,0)) return 0;
        int missing=0, allies=0;
        for (Player ally:p.mapData.players) if (ally != null && !ally.isDie && ally.isFaction && ally.team==p.team) {
            int deficit=Math.max(0,ally.hpMax-ally.hp);
            missing+=Math.min(300,deficit); if (deficit>=150) allies++;
        }
        if (available(p,policy,10) && (missing>=450 || policy.preset()==BotPolicy.Preset.SUPPORT && allies>0)) return 10;
        return -1;
    }
    public static int offensive(Player p,BotPolicy policy) {
        if (available(p,policy,100)) return 100;
        if (available(p,policy,2)) return 2;
        return -1;
    }
    private BotItemPolicy() {}
}
