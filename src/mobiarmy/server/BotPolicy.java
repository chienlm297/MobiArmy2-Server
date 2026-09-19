package mobiarmy.server;

import java.util.*;

/** Immutable configuration, snapshotted at the beginning of each turn. */
public record BotPolicy(boolean enabled, boolean movement, boolean items, Preset preset,
                        int healPercent, int maxSteps, int thinkMs, int turnMs, Set<Integer> allowed) {
    public enum Preset { PASSIVE, BALANCED, AGGRESSIVE, SUPPORT }
    public static final Set<Integer> SUPPORTED = Set.of(0,1,2,3,5,6,10,100);
    public BotPolicy {
        Objects.requireNonNull(preset);
        allowed = Set.copyOf(allowed);
        if (healPercent < 1 || healPercent > 90 || maxSteps < 0 || maxSteps > 60
                || thinkMs < 0 || thinkMs > 2000 || turnMs < 3000 || turnMs > 10000
                || !SUPPORTED.containsAll(allowed)) throw new IllegalArgumentException("Cấu hình bot ngoài giới hạn");
    }
    private static final BotPolicy DEFAULT = new BotPolicy(Bot.TACTICAL,true,true,Preset.BALANCED,40,48,300,8000,SUPPORTED);
    public static BotPolicy defaults() { return DEFAULT; }
    public static BotPolicy fromForm(java.util.Map<String,String> f) {
        Set<Integer> ids = new TreeSet<>();
        for (String s : f.getOrDefault("allowed", "0,1,2,3,5,6,10,100").split(","))
            if (!s.isBlank()) ids.add(Integer.parseInt(s.trim()));
        return new BotPolicy(flag(f,"enabled"),flag(f,"movement"),flag(f,"items"),
                Preset.valueOf(f.getOrDefault("preset","BALANCED")),
                Integer.parseInt(f.getOrDefault("heal","40")),Integer.parseInt(f.getOrDefault("steps","48")),
                Integer.parseInt(f.getOrDefault("think","300")),Integer.parseInt(f.getOrDefault("budget","8000")),ids);
    }
    private static boolean flag(java.util.Map<String,String> f,String key) {
        String s=f.getOrDefault(key,"true");
        if (!s.equals("true") && !s.equals("false")) throw new IllegalArgumentException("Cờ bot không hợp lệ: " + key);
        return Boolean.parseBoolean(s);
    }
    public boolean mayMove() { return movement && preset != Preset.PASSIVE; }
    public boolean mayUse(int id) { return items && preset != Preset.PASSIVE && allowed.contains(id); }
}
