package mobiarmy.server;
import java.util.*;

/** Explicit admin provisioning in the waiting room, never automatic during combat. */
public final class BotLoadout {
    public static void validate(Bot bot,String slots,int quantity) {
        if (bot.ready || bot.lock || bot.roomWait!=null && bot.roomWait.started)
            throw new IllegalArgumentException("Rời phòng/để bot chưa sẵn sàng trước khi đổi bộ item");
        if(quantity<0 || quantity>99) throw new IllegalArgumentException("Số lượng cấp thêm phải từ 0 đến 99");
        String[] values=slots.split(",",-1);
        if(values.length!=4) throw new IllegalArgumentException("Nhập đúng 4 slot, dùng -1 cho slot trống");
        java.util.Map<Integer,Integer> count=new HashMap<>();
        for(String s:values) {
            int id=Integer.parseInt(s.trim());
            if(id==-1) continue;
            if(id==100 || !BotPolicy.SUPPORTED.contains(id)) throw new IllegalArgumentException("Item không được hỗ trợ");
            count.merge(id,1,Integer::sum);
        }
        for(var e:count.entrySet()) {
            Item item=bot.getItem(e.getKey());
            if(item==null || item.num+(e.getKey()>1?quantity:0)>99 || e.getValue()>item.carryable
                    || e.getValue()>item.num+(e.getKey()>1?quantity:0))
                throw new IllegalArgumentException("Kho/túi item không đủ hoặc vượt giới hạn: "+e.getKey());
        }
    }
    public static void apply(Bot bot,String slots,int quantity) {
        validate(bot,slots,quantity);
        byte[] selected=new byte[]{-1,-1,-1,-1,-1,-1,-1,-1}; Set<Integer> added=new HashSet<>();
        String[] values=slots.split(",");
        for(int i=0;i<4;i++) {
            int id=Integer.parseInt(values[i].trim()); selected[i]=(byte)id;
            if(id>1 && added.add(id)) bot.getItem(id).num+=quantity;
        }
        bot.setItems=selected;
    }
    public static String describe(Bot bot) {
        StringBuilder s=new StringBuilder("Slots: ").append(Arrays.toString(bot.setItems)).append(" · Kho: ");
        if(bot.items!=null) for(Item i:bot.items) if(BotPolicy.SUPPORTED.contains(i.id)) s.append(i.id).append('=').append(i.num).append(' ');
        return s.toString();
    }
    private BotLoadout() {}
}
