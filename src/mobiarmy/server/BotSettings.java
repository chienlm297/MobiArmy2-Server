package mobiarmy.server;

import java.util.Locale;
import java.util.Map;

/** Startup settings; bot game state is owned by the game loop. */
public record BotSettings(int count, boolean autoJoin, boolean requireHuman,
                          int readyDelayMs, int leaveDelayMs, TargetMode targetMode) {
    public enum TargetMode { RANDOM, LOW_HP }

    public static BotSettings fromEnvironment(Map<String, String> env) {
        return new BotSettings(number(env, "BOT_COUNT", 5000, 0, 10000),
                flag(env, "BOT_AUTO_JOIN", false), flag(env, "BOT_REQUIRE_HUMAN", true),
                number(env, "BOT_READY_DELAY_MS", 2000, 100, 60000),
                number(env, "BOT_LEAVE_DELAY_MS", 120000, 1000, 3600000),
                mode(env.getOrDefault("BOT_TARGET_MODE", "RANDOM")));
    }

    private static TargetMode mode(String value) {
        try { return TargetMode.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("BOT_TARGET_MODE phải là RANDOM hoặc LOW_HP", e);
        }
    }

    private static int number(Map<String, String> env, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(env.getOrDefault(key, String.valueOf(fallback)).trim());
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " phải trong khoảng " + min + ".." + max, e);
        }
    }

    private static boolean flag(Map<String, String> env, String key, boolean fallback) {
        String value = env.getOrDefault(key, String.valueOf(fallback)).trim();
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException(key + " phải là true hoặc false");
        return Boolean.parseBoolean(value);
    }
}
