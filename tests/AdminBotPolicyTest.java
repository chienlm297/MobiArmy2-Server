package mobiarmy.admin;
import com.google.gson.Gson;
import java.util.*;
import java.util.Map;
import mobiarmy.server.*;

public class AdminBotPolicyTest {
    static int checks;
    static void check(boolean v,String s){if(!v)throw new AssertionError(s);checks++;System.out.println("PASS: "+s);}
    public static void main(String[]args){
        var owner=new AdminAccounts.Account(1,"owner",AdminAccounts.Role.OWNER,true,1);
        var viewer=new AdminAccounts.Account(2,"viewer",AdminAccounts.Role.VIEWER,true,1);
        try{AdminBots.submit(viewer,Map.of("action","policy","bot_id","-1","reason","test"));throw new AssertionError();}
        catch(SecurityException expected){check(true,"viewer cannot submit bot policy");}
        for(Map<String,String> form:List.of(Map.of("action","policy","bot_id","-5","reason","x","steps","900"),Map.of("action","policy","bot_id","-5","reason","x","allowed","24"),Map.of("action","policy","bot_id","-5","reason",""))){
            try{AdminBots.submit(owner,form);throw new AssertionError();}catch(IllegalArgumentException expected){check(true,"invalid admin policy rejected before queue");}
        }
        var policy=BotPolicy.fromForm(Map.of("preset","SUPPORT","allowed","0,10"));
        String json=new Gson().toJson(policy);
        check(new Gson().fromJson(json,BotPolicy.class).equals(policy),"queued policy roundtrips with validation");
        String job=AdminBots.submit(owner,Map.of("action","policy","bot_id","-5","reason","fixture","preset","SUPPORT"));
        check(AdminBots.jobs().stream().anyMatch(j->j.id().equals(job)&&j.status().equals("QUEUED")),"policy uses existing bounded game-loop queue");
        var row=new AdminBots.Row(-5,"<bot>",0,1,"PLAYING","0/0","LOW_HP",false,policy,"<script>bad</script>","Item=2","slots=0,10");
        var snap=new AdminBots.Snapshot(1,List.of(row));
        String html=BotView.render(snap,List.of(),Map.of(),"csrf","owner");
        check(html.contains("name='allowed'")&&html.contains("name='slots'")&&html.contains("SUPPORT"),"admin renders policy and loadout controls");
        check(!html.contains("<script>bad</script>")&&html.contains("&lt;script&gt;"),"decision text escaped");
        AdminView.setRole(AdminAccounts.Role.VIEWER);
        html=AdminView.bots(snap,List.of(),Map.of(),"csrf","viewer");
        check(!html.contains("<form method='post' action='/admin/bots'"),"viewer has no new mutation forms");
        AdminView.clearRole();
        check(AdminBots.jobs().size()==1,"invalid commands never enter queue");
        System.out.println(checks+" admin policy checks passed");
    }
}
