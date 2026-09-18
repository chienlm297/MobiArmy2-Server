package mobiarmy.admin;

import com.google.gson.*;
import java.lang.reflect.Field;
import java.util.*;
import mobiarmy.server.Bot;
import mobiarmy.server.BotSettings;
import mobiarmy.war.RoomWait;

/** Game-loop command guards and views, with no database/network. */
public class AdminBotsTest {
    static int checks;
    static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++; System.out.println("PASS: " + label);
    }
    static final AdminAccounts.Account OWNER = new AdminAccounts.Account(1,"owner",AdminAccounts.Role.OWNER,true,1);
    static AdminBots.Request request(String action, int id) {
        return new AdminBots.Request("test",OWNER,action,id,"TestBot",0,0,"LOW_HP","test",Long.MAX_VALUE);
    }
    static void rejects(AdminBots.Request r, String label) {
        try { AdminBots.validateCurrent(r); throw new AssertionError(label); }
        catch (IllegalArgumentException expected) { check(true,label); }
    }
    public static void main(String[] args) throws Exception {
        Gson gson = new GsonBuilder().setExclusionStrategies(new ExclusionStrategy() {
            public boolean shouldSkipField(FieldAttributes f) {
                return !f.getDeclaredClass().isPrimitive() && f.getDeclaredClass() != String.class;
            }
            public boolean shouldSkipClass(Class<?> type) { return false; }
        }).create();
        Bot bot = gson.fromJson("{\"id\":-99,\"name\":\"TestBot\"}",Bot.class);
        Field selected = Bot.class.getDeclaredField("selecteds"); selected.setAccessible(true); selected.set(bot,new ArrayList<>());
        Bot.add(bot);
        rejects(request("create",0),"duplicate bot name rejected at execution");
        rejects(request("remove",-1),"missing bot rejected at execution");
        bot.lock=true;
        rejects(request("leave",bot.id),"locked bot cannot leave");
        rejects(request("remove",bot.id),"locked bot cannot be removed");
        bot.lock=false;
        RoomWait room = new RoomWait((byte)0,(byte)5,(byte)0,new byte[]{30},0,100,(byte)8);
        room.players[0]=bot; room.userID=bot.id; room.numPlayer=1; bot.roomWait=room;
        room.started=true;
        rejects(request("leave",bot.id),"match start after enqueue blocks leave");
        rejects(request("remove",bot.id),"match start after enqueue blocks remove");
        AdminBots.validateCurrent(request("target",bot.id));
        AdminBots.apply(request("target",bot.id));
        check(bot.effectiveTargetMode()==BotSettings.TargetMode.LOW_HP,"target override applies without touching match state");
        check(room.started,"target override preserves running match");
        room.started=false;
        AdminBots.validateCurrent(request("leave",bot.id));
        AdminBots.apply(request("leave",bot.id));
        check(bot.roomWait==null && room.numPlayer==0,"leave command uses room lifecycle");
        AdminBots.validateCurrent(request("remove",bot.id));
        AdminBots.apply(request("remove",bot.id));
        check(Bot.findById(-99)==null && Bot.bots.isEmpty(),"remove command clears registry");
        List<AdminBots.Row> rows=new ArrayList<>();
        for(int i=0;i<30;i++) rows.add(new AdminBots.Row(-100-i,"Bot"+i,0,1,"IDLE","—","RANDOM",false));
        rows.add(new AdminBots.Row(-200,"<script>bad</script>",0,1,"PLAYING","1 / 2","LOW_HP",false));
        var snapshot=new AdminBots.Snapshot(1,List.copyOf(rows));
        AdminView.setRole(AdminAccounts.Role.OWNER);
        String html=AdminView.bots(snapshot,List.of(),Map.of("page","2"),"csrf","owner");
        check(html.contains("31 bot · Trang 2 / 2") && html.contains("Bot25") && !html.contains("<strong>Bot0</strong>"),"bot pagination uses 25 rows");
        check(html.contains("&lt;script&gt;bad&lt;/script&gt;") && !html.contains("<script>bad</script>"),"bot names HTML escaped");
        html=AdminView.bots(snapshot,List.of(),Map.of("state","PLAYING"),"csrf","owner");
        check(html.contains("1 bot · Trang 1 / 1") && html.contains("value='remove' disabled"),"playing filter and disabled destructive action");
        AdminView.setRole(AdminAccounts.Role.VIEWER);
        html=AdminView.bots(snapshot,List.of(),Map.of(),"csrf","viewer");
        check(!html.contains("<form method='post' action='/admin/bots'"),"viewer has no bot mutation forms");
        AdminView.clearRole();
        for(int i=0;i<32;i++) AdminBots.submit(OWNER,Map.of("action","remove","bot_id","-99","reason","test"));
        try { AdminBots.submit(OWNER,Map.of("action","remove","bot_id","-99","reason","test")); throw new AssertionError("queue overflow"); }
        catch(IllegalArgumentException expected){check(AdminBots.jobs().size()==32,"bounded queue rejects overflow without ghost jobs");}
        System.out.println(checks+" bot-admin checks passed");
    }
}
